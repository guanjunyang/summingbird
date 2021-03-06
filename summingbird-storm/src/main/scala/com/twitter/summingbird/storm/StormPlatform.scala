/*
 Copyright 2013 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.twitter.summingbird.storm

import Constants._
import backtype.storm.{Config => BacktypeStormConfig, LocalCluster, StormSubmitter}
import backtype.storm.generated.StormTopology
import backtype.storm.topology.{BoltDeclarer, TopologyBuilder}
import backtype.storm.tuple.Fields
import backtype.storm.tuple.Tuple

import com.twitter.bijection.{Base64String, Injection}
import com.twitter.algebird.Monoid
import com.twitter.chill.IKryoRegistrar
import com.twitter.storehaus.algebra.MergeableStore
import com.twitter.storehaus.algebra.MergeableStore.enrich
import com.twitter.summingbird._
import com.twitter.summingbird.viz.VizGraph
import com.twitter.summingbird.chill._
import com.twitter.summingbird.batch.{BatchID, Batcher}
import com.twitter.summingbird.storm.option.{AnchorTuples, IncludeSuccessHandler}
import com.twitter.summingbird.util.CacheSize
import com.twitter.tormenta.spout.Spout
import com.twitter.summingbird.planner._
import com.twitter.summingbird.online.FlatMapOperation
import com.twitter.summingbird.storm.planner._
import com.twitter.util.Future
import scala.annotation.tailrec
import backtype.storm.tuple.Values
import org.slf4j.LoggerFactory

/*
 * Batchers are used for partial aggregation. We never aggregate past two items which are not in the same batch.
 * This is needed/used everywhere we partially aggregate, summer's into stores, map side partial aggregation before summers, etc..
 */

sealed trait StormStore[-K, V] {
  def batcher: Batcher
}

object MergeableStoreSupplier {
  def from[K, V](store: => MergeableStore[(K, BatchID), V])(implicit batcher: Batcher): MergeableStoreSupplier[K, V] =
    MergeableStoreSupplier(() => store, batcher)

   def fromOnlineOnly[K, V](store: => MergeableStore[K, V]): MergeableStoreSupplier[K, V] = {
    implicit val batcher = Batcher.unit
    from(store.convert{k: (K, BatchID) => k._1})
  }
}


case class MergeableStoreSupplier[K, V](store: () => MergeableStore[(K, BatchID), V], batcher: Batcher) extends StormStore[K, V]

sealed trait StormService[-K, +V]
case class StoreWrapper[K, V](store: StoreFactory[K, V]) extends StormService[K, V]

sealed trait StormSource[+T]
case class SpoutSource[+T](spout: Spout[(Long, T)], parallelism: Option[option.SpoutParallelism]) extends StormSource[T]

object Storm {
  def local(options: Map[String, Options] = Map.empty): LocalStorm =
    new LocalStorm(options, identity, List())

  def remote(options: Map[String, Options] = Map.empty): RemoteStorm =
    new RemoteStorm(options, identity, List())

  def store[K, V](store: => MergeableStore[(K, BatchID), V])(implicit batcher: Batcher): MergeableStoreSupplier[K, V] =
    MergeableStoreSupplier.from(store)

  def toStormSource[T](spout: Spout[T], defaultSourcePar: Option[Int] = None)(implicit timeOf: TimeExtractor[T]) =
    SpoutSource(spout.map(t => (timeOf(t), t)), defaultSourcePar.map(option.SpoutParallelism(_)))

  implicit def spoutAsStormSource[T](spout: Spout[T])(implicit timeOf: TimeExtractor[T]): StormSource[T] = toStormSource(spout, None)(timeOf)

  def source[T](spout: Spout[T], defaultSourcePar: Option[Int] = None)(implicit timeOf: TimeExtractor[T]) =
    Producer.source[Storm, T](toStormSource(spout, defaultSourcePar))

  implicit def spoutAsSource[T](spout: Spout[T])(implicit timeOf: TimeExtractor[T]): Producer[Storm, T] = source(spout, None)(timeOf)
}

case class PlannedTopology(config: BacktypeStormConfig, topology: StormTopology)

abstract class Storm(options: Map[String, Options], transformConfig: SummingbirdConfig => SummingbirdConfig, passedRegistrars: List[IKryoRegistrar]) extends Platform[Storm] {
  @transient private val logger = LoggerFactory.getLogger(classOf[Storm])

  type Source[+T] = StormSource[T]
  type Store[-K, V] = StormStore[K, V]
  type Sink[-T] = () => (T => Future[Unit])
  type Service[-K, +V] = StormService[K, V]
  type Plan[T] = PlannedTopology

  private type Prod[T] = Producer[Storm, T]

  private def getOrElse[T <: AnyRef : Manifest](dag: Dag[Storm], node: StormNode, default: T): T = {
    val producer = node.members.last

    val namedNodes = dag.producerToPriorityNames(producer)
    val maybePair = (for {
      id <- namedNodes
      stormOpts <- options.get(id)
      option <- stormOpts.get[T]
    } yield (id, option)).headOption

    maybePair match {
      case None =>
          logger.debug("Node ({}): Using default setting {}", dag.getNodeName(node), default)
          default
      case Some((namedSource, option)) =>
          logger.info("Node {}: Using {} found via NamedProducer \"{}\"", Array[AnyRef](dag.getNodeName(node), option, namedSource))
          option
    }
  }

  private def scheduleFlatMapper(stormDag: Dag[Storm], node: StormNode)(implicit topologyBuilder: TopologyBuilder) = {
    /**
     * Only exists because of the crazy casts we needed.
     */
    def foldOperations(producers: List[Producer[Storm, _]]): FlatMapOperation[Any, Any] = {
      producers.foldLeft(FlatMapOperation.identity[Any]) {
        case (acc, p) =>
          p match {
            case LeftJoinedProducer(_, wrapper) =>
              val newService = wrapper.asInstanceOf[StoreWrapper[Any, Any]].store
              FlatMapOperation.combine(
                acc.asInstanceOf[FlatMapOperation[Any, (Any, Any)]],
                newService.asInstanceOf[StoreFactory[Any, Any]]).asInstanceOf[FlatMapOperation[Any, Any]]
            case OptionMappedProducer(_, op) => acc.andThen(FlatMapOperation[Any, Any](op.andThen(_.iterator).asInstanceOf[Any => TraversableOnce[Any]]))
            case FlatMappedProducer(_, op) => acc.andThen(FlatMapOperation(op).asInstanceOf[FlatMapOperation[Any, Any]])
            case WrittenProducer(_, sinkSupplier) => acc.andThen(FlatMapOperation.write(sinkSupplier.asInstanceOf[() => (Any => Future[Unit])]))
            case IdentityKeyedProducer(_) => acc
            case MergedProducer(_, _) => acc
            case NamedProducer(_, _) => acc
            case AlsoProducer(_, _) => acc
            case Source(_) => sys.error("Should not schedule a source inside a flat mapper")
            case Summer(_, _, _) => sys.error("Should not schedule a Summer inside a flat mapper")
            case KeyFlatMappedProducer(_, op) => acc.andThen(FlatMapOperation.keyFlatMap[Any, Any, Any](op).asInstanceOf[FlatMapOperation[Any, Any]])
          }
      }
    }
    val nodeName = stormDag.getNodeName(node)
    val operation = foldOperations(node.members.reverse)
    val metrics = getOrElse(stormDag, node, DEFAULT_FM_STORM_METRICS)
    val anchorTuples = getOrElse(stormDag, node, AnchorTuples.default)

    val summerOpt:Option[SummerNode[Storm]] = stormDag.dependantsOf(node).collect{case s: SummerNode[Storm] => s}.headOption

    val bolt = summerOpt match {
      case Some(s) =>
        val summerProducer = s.members.collect { case s: Summer[_, _, _] => s }.head.asInstanceOf[Summer[Storm, _, _]]
        new FinalFlatMapBolt(
          operation.asInstanceOf[FlatMapOperation[Any, (Any, Any)]],
          getOrElse(stormDag, node, DEFAULT_FM_CACHE),
          getOrElse(stormDag, node, DEFAULT_FM_STORM_METRICS),
          anchorTuples)(summerProducer.monoid.asInstanceOf[Monoid[Any]], summerProducer.store.batcher)
      case None =>
        new IntermediateFlatMapBolt(operation, metrics, anchorTuples, stormDag.dependenciesOf(node).size > 0)
    }

    val parallelism = getOrElse(stormDag, node, DEFAULT_FM_PARALLELISM).parHint
    val declarer = topologyBuilder.setBolt(nodeName, bolt, parallelism)


    val dependenciesNames = stormDag.dependenciesOf(node).collect { case x: StormNode => stormDag.getNodeName(x) }
    dependenciesNames.foreach { declarer.shuffleGrouping(_) }
  }

  private def scheduleSpout[K](stormDag: Dag[Storm], node: StormNode)(implicit topologyBuilder: TopologyBuilder) = {
    val (spout, parOpt) = node.members.collect { case Source(SpoutSource(s, parOpt)) => (s, parOpt) }.head
    val nodeName = stormDag.getNodeName(node)

    val stormSpout = node.members.reverse.foldLeft(spout.asInstanceOf[Spout[(Long, Any)]]) { (spout, p) =>
      p match {
        case Source(_) => spout // The source is still in the members list so drop it
        case OptionMappedProducer(_, op) => spout.flatMap {case (time, t) => op.apply(t).map { x => (time, x) }}
        case NamedProducer(_, _) => spout
        case IdentityKeyedProducer(_) => spout
        case AlsoProducer(_, _) => spout
        case _ => sys.error("not possible, given the above call to span.\n" + p)
      }
    }.getSpout

    val parallelism = getOrElse(stormDag, node, parOpt.getOrElse(DEFAULT_SPOUT_PARALLELISM)).parHint
    topologyBuilder.setSpout(nodeName, stormSpout, parallelism)
  }

  private def scheduleSummerBolt[K, V](stormDag: Dag[Storm], node: StormNode)(implicit topologyBuilder: TopologyBuilder) = {
    val summer: Summer[Storm, K, V] = node.members.collect { case c: Summer[Storm, K, V] => c }.head
    implicit val monoid = summer.monoid
    val nodeName = stormDag.getNodeName(node)

    val supplier = summer.store match {
      case MergeableStoreSupplier(contained, _) => contained
    }
    val anchorTuples = getOrElse(stormDag, node, AnchorTuples.default)

    val sinkBolt = new SummerBolt[K, V](
      supplier,
      getOrElse(stormDag, node, DEFAULT_ONLINE_SUCCESS_HANDLER),
      getOrElse(stormDag, node, DEFAULT_ONLINE_EXCEPTION_HANDLER),
      getOrElse(stormDag, node, DEFAULT_SINK_CACHE),
      getOrElse(stormDag, node, DEFAULT_SINK_STORM_METRICS),
      getOrElse(stormDag, node, DEFAULT_MAX_WAITING_FUTURES),
      getOrElse(stormDag, node, IncludeSuccessHandler.default),
      anchorTuples,
      stormDag.dependantsOf(node).size > 0)

    val parallelism = getOrElse(stormDag, node, DEFAULT_SINK_PARALLELISM).parHint
    val declarer =
      topologyBuilder.setBolt(
        nodeName,
        sinkBolt,
        parallelism
        )
    val dependenciesNames = stormDag.dependenciesOf(node).collect { case x: StormNode => stormDag.getNodeName(x) }
    dependenciesNames.foreach { parentName =>
      declarer.fieldsGrouping(parentName, new Fields(AGG_KEY))
    }

  }

  /**
   * The following operations are public.
   */

  /**
   * Base storm config instances used by the Storm platform.
   */

  def genConfig(dag: Dag[Storm]) = {
    val config = new BacktypeStormConfig
    config.setFallBackOnJavaSerialization(false)
    config.setKryoFactory(classOf[com.twitter.chill.storm.BlizzardKryoFactory])
    config.setMaxSpoutPending(1000)
    config.setNumAckers(12)
    config.setNumWorkers(12)

    val initialStormConfig = StormConfig(config)
    val stormConfig = SBChillRegistrar(initialStormConfig, passedRegistrars)
    logger.debug("Serialization config changes:")
    logger.debug("Removes: {}", stormConfig.removes)
    logger.debug("Updates: {}", stormConfig.updates)


    val inj = Injection.connect[String, Array[Byte], Base64String]
    logger.debug("Adding serialized copy of graphs")
    val withViz = stormConfig.put("producer_dot_graph_base64", inj.apply(VizGraph(dag.tail)).str)
                            .put("planned_dot_graph_base64", inj.apply(VizGraph(dag)).str)
    val transformedConfig = transformConfig(withViz)

    logger.debug("Config diff to be applied:")
    logger.debug("Removes: {}", transformedConfig.removes)
    logger.debug("Updates: {}", transformedConfig.updates)

    transformedConfig.removes.foreach(config.remove(_))
    transformedConfig.updates.foreach(kv => config.put(kv._1, kv._2))
    config
  }

  def withRegistrars(registrars: List[IKryoRegistrar]): Storm

  def withConfigUpdater(fn: SummingbirdConfig => SummingbirdConfig): Storm

  def plan[T](tail: TailProducer[Storm, T]): PlannedTopology = {
    val stormDag = OnlinePlan(tail)
    implicit val topologyBuilder = new TopologyBuilder
    implicit val config = genConfig(stormDag)


    stormDag.nodes.foreach { node =>
      node match {
        case _: SummerNode[_] => scheduleSummerBolt(stormDag, node)
        case _: FlatMapNode[_] => scheduleFlatMapper(stormDag, node)
        case _: SourceNode[_] => scheduleSpout(stormDag, node)
      }
    }
    PlannedTopology(config, topologyBuilder.createTopology)
  }
  def run(tail: TailProducer[Storm, _], jobName: String): Unit = run(plan(tail), jobName)
  def run(plannedTopology: PlannedTopology, jobName: String): Unit
}

class RemoteStorm(options: Map[String, Options], transformConfig: SummingbirdConfig => SummingbirdConfig, passedRegistrars: List[IKryoRegistrar]) extends Storm(options, transformConfig, passedRegistrars) {

  override def withConfigUpdater(fn: SummingbirdConfig => SummingbirdConfig) =
    new RemoteStorm(options, transformConfig.andThen(fn), passedRegistrars)

  override def run(plannedTopology: PlannedTopology, jobName: String): Unit = {
    val topologyName = "summingbird_" + jobName
    StormSubmitter.submitTopology(topologyName, plannedTopology.config, plannedTopology.topology)
  }

  override def withRegistrars(registrars: List[IKryoRegistrar]) =
    new RemoteStorm(options, transformConfig, passedRegistrars ++ registrars)
}

class LocalStorm(options: Map[String, Options], transformConfig: SummingbirdConfig => SummingbirdConfig, passedRegistrars: List[IKryoRegistrar])
  extends Storm(options, transformConfig, passedRegistrars) {
  lazy val localCluster = new LocalCluster

  override def withConfigUpdater(fn: SummingbirdConfig => SummingbirdConfig) =
    new LocalStorm(options, transformConfig.andThen(fn), passedRegistrars)

  override def run(plannedTopology: PlannedTopology, jobName: String): Unit = {
    val topologyName = "summingbird_" + jobName
    localCluster.submitTopology(topologyName, plannedTopology.config, plannedTopology.topology)
  }

  override def withRegistrars(registrars: List[IKryoRegistrar]) =
    new LocalStorm(options, transformConfig, passedRegistrars ++ registrars)
}
