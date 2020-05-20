package bmaso.akka.event_processor

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag
import akka.Done
import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.persistence.query.{Offset, PersistenceQuery, Sequence, TimeBasedUUID}
import akka.persistence.typed.PersistenceId
import akka.stream.KillSwitches
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.RestartSource
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal

/**
 * General purpose event processor infrastructure. Not specific to the ShoppingCart domain.
 *
 * Copied from Akka's official "ShoppingCart" CQRS example
 */
object EventProcessor {

  def init[Event](
                   system: ActorSystem[_],
                   settings: EventProcessorSettings,
                   eventProcessorStream: String => EventProcessorStream[Event]): Unit = {
    val shardedDaemonSettings = ShardedDaemonProcessSettings(system)
      .withKeepAliveInterval(settings.keepAliveInterval)
      .withShardingSettings(ClusterShardingSettings(system).withRole("read-model"))
    ShardedDaemonProcess(system).init[Nothing](
      s"event-processors-${settings.id}",
      settings.parallelism,
      (i: Int) => EventProcessor(eventProcessorStream(s"${settings.tagPrefix}-$i")),
      shardedDaemonSettings,
      None)
  }

  def apply(eventProcessorStream: EventProcessorStream[_]): Behavior[Nothing] = {
    Behaviors.setup[Nothing] { ctx =>
      val killSwitch = KillSwitches.shared("eventProcessorSwitch")
      eventProcessorStream.runQueryStream(killSwitch)
      Behaviors.receiveSignal[Nothing] {
        case (_, PostStop) =>
          killSwitch.shutdown()
          Behaviors.same
      }
    }
  }
}

abstract class EventProcessorStream[Event: ClassTag](
                                                      system: ActorSystem[_],
                                                      executionContext: ExecutionContext,
                                                      eventProcessorId: String,
                                                      tag: String) {

  protected val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val sys: ActorSystem[_] = system
  implicit val ec: ExecutionContext = executionContext

  private val query =
    PersistenceQuery(system.toClassic).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)

  // TODO: Set up Slick connection to underlying database

  protected def processEvent(event: Event, persistenceId: PersistenceId, sequenceNr: Long): Future[Done]

  def runQueryStream(killSwitch: SharedKillSwitch): Unit = {
    RestartSource
      .withBackoff(minBackoff = 500.millis, maxBackoff = 20.seconds, randomFactor = 0.1) { () =>
        Source.futureSource {
          readOffset().map { offset =>
            log.info(s"Starting stream for tag [$tag] from offset [$offset]")
            processEventsByTag(offset)
              // groupedWithin can be used here to improve performance by reducing number of offset writes,
              // with the trade-off of possibility of more duplicate events when stream is restarted
              .mapAsync(1)(writeOffset)
          }
        }
      }
      .via(killSwitch.flow)
      .runWith(Sink.ignore)
  }

  private def processEventsByTag(offset: Offset): Source[Offset, NotUsed] = {
    query.eventsByTag(tag, offset).mapAsync(1) { eventEnvelope =>
      eventEnvelope.event match {
        case event: Event =>
          processEvent(event, PersistenceId.ofUniqueId(eventEnvelope.persistenceId), eventEnvelope.sequenceNr).map(_ =>
            eventEnvelope.offset)
        case other =>
          Future.failed(new IllegalArgumentException(s"Unexpected event [${other.getClass.getName}]"))
      }
    }
  }

  private def readOffset(): Future[Offset] = {
    // TODO read last recorded offset from DB table. If there is none, then use startOffset()
    ???
  }

  // Start from the very begnning of time if no sequence ID is stored. Note that the JDBC journal we
  // are using utilizes sequence # offsets.
  private def startOffset(): Offset = Offset.noOffset

  private def writeOffset(offset: Offset): Future[Done] = {
    offset match {
      case Sequence(sequenceNbr) =>
        // TODO: Write sequence number as new offset for (eventProcessorId, tag) combo
        ???

      case _ =>
        throw new IllegalArgumentException(s"Unexpected offset type $offset")
    }

  }
}

object EventProcessorSettings {

  def apply(system: ActorSystem[_]): EventProcessorSettings = {
    apply(system.settings.config.getConfig("event-processor"))
  }

  def apply(config: Config): EventProcessorSettings = {
    val id: String = config.getString("id")
    val keepAliveInterval: FiniteDuration = config.getDuration("keep-alive-interval").toMillis.millis
    val tagPrefix: String = config.getString("tag-prefix")
    val parallelism: Int = config.getInt("parallelism")
    EventProcessorSettings(id, keepAliveInterval, tagPrefix, parallelism)
  }
}

final case class EventProcessorSettings(
                                         id: String,
                                         keepAliveInterval: FiniteDuration,
                                         tagPrefix: String,
                                         parallelism: Int)
