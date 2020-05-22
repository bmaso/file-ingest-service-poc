package bmaso.akka.event_processor

import java.sql.SQLException

import scala.concurrent.{Await, ExecutionContext, Future}
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
import akka.persistence.query.{Offset, PersistenceQuery, Sequence}
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
import slick.jdbc.H2Profile.api._

/**
 * General purpose event processor infrastructure. Not specific to the any domain. *Is* specific
 * to Slick/H2.
 *
 * Note: mostly instsapired by Akka's official "ShoppingCart" CQRS example
 */
object EventProcessor {

  def init[Event](
                   system: ActorSystem[_],
                   settings: EventProcessorSettings,
                   eventProcessorStream: String => EventProcessorStream[Event]): Unit = {
    val shardedDaemonSettings = ShardedDaemonProcessSettings(system)
      .withKeepAliveInterval(settings.keepAliveInterval)
      .withShardingSettings(ClusterShardingSettings(system))
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

/**
 * Processes all events emitted within the cluster that are tagged with `tag`. Intended use is for the entire set
 * of all entities of a specific type are sharded into N processor streams. That is, the entities automatically
 * tag their events with one of N well-known tags. N event processes, run as a sharded daemon process, process the
 * tagged events.
 *
 * A special database table maintains the current offset of each sharded event processor in the
 * event stream. Thus when the event processor is re-started after migration to new node or passivate/activation
 * cycle, it can pick up processing events where it left off.
 */
abstract class EventProcessorStream[Event: ClassTag](
                                                      system: ActorSystem[_],
                                                      executionContext: ExecutionContext,
                                                      eventProcessorId: String,
                                                      tag: String) {

  val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val sys: ActorSystem[_] = system
  implicit val ec: ExecutionContext = executionContext

  lazy val query =
    PersistenceQuery(system.toClassic).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
  lazy val db = Database.forConfig("slick.db", system.settings.config)

  protected def processEvent(event: Event, persistenceId: PersistenceId, sequenceNr: Long): Future[Done]

  def runQueryStream(killSwitch: SharedKillSwitch): Unit = {
    // TODO: Find a less clumsy way to make sure there is a line with offset value for each processor when
    // it needs to run its query stream.
    val initialInsertOffsetStatement =
      sqlu"""
        INSERT INTO "processor_offsets" ("processor_id", "tag", "offset_val") values ($eventProcessorId, $tag, ${startOffset.asInstanceOf[Sequence].value})
          """
    val initial_insert_fut = db.run(initialInsertOffsetStatement)
    try {
      val result = Await.result(initial_insert_fut, 10 seconds)
      println(s"Result from inserting initial offset -- its OK if this fails: $result")
    } catch {
      case ex: SQLException =>
        log.info(s"Ignoring SQL exception $ex indicating that event processor $eventProcessorId already has an offset for tag $tag")
      case ex: Throwable =>
        log.info(s"Ignoring?? general exception $ex, possibly indicating that event processor $eventProcessorId already has an offset for tag $tag")
    }

    RestartSource
      .withBackoff(minBackoff = 500.millis, maxBackoff = 20.seconds, randomFactor = 0.1) { () =>
        Source.futureSource {
          readOffset().map { offset =>
            log.info(s"Starting stream for tag [$tag] from offset [$offset]")
            processEventsByTag(Sequence(offset))
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

  private def readOffset(): Future[Long] = {
    // TODO: Need to read and write Long values, not Ints. Emply Slick much better, please.
    val readOffsetStatement =
      sql"""
        SELECT "offset_val" from "processor_offsets" WHERE "processor_id" = $eventProcessorId and "tag" = $tag
          """.as[Long]
    db.run(readOffsetStatement).map(_.headOption.getOrElse(startOffset.asInstanceOf[Sequence].value))
  }

  // Start from the very beginning of time if no sequence ID is stored. Note that the JDBC journal we
  // are using utilizes sequence # offsets.
  lazy val startOffset: Offset = Offset.sequence(0L)

  private def writeOffset(offset: Offset): Future[Unit] = {
    // TODO: Need to read and write Long values, not Ints. Emply Slick much better, please.
    offset match {
      case Sequence(sequenceNbr) =>
        val insertStatement =
          sqlu"""
            UPDATE "processor_offsets" SET "offset_val" = ${offset.asInstanceOf[Sequence].value} WHERE "processor_id" = $eventProcessorId AND "tag" = $tag
              """
        // TODO: Need to check response, make sure this works correctly.
        db.run(insertStatement).map(_ => ())


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

final case class EventProcessorSettings(id: String, keepAliveInterval: FiniteDuration, tagPrefix: String, parallelism: Int)
