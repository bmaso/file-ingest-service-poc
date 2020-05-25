package bmaso.file_ingest_service_poc.cluster_node

import scala.concurrent.duration._
import cats.data.NonEmptyList
import java.time.Instant

import akka.actor.typed.{ActorSystem, Behavior, SupervisorStrategy}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import bmaso.akka.JsonSerialization
import bmaso.file_ingest_service_poc.protocol._
import bmaso.akka.event_processor.EventProcessorSettings
import org.slf4j.{Logger, LoggerFactory}

/**
 * This model defines the persistent
 */
object FileIngestionEntity {
  val log: Logger = LoggerFactory.getLogger(getClass)

  /** The current state of the entity lifecycle is represented at any time by an instance of this class */
  final case class InternalState(originalOrder_? : Option[FileIngestion.IngestFileOrder],
                                 cleanseComplete_? : Option[FileIngestion.CleanseCompleteAcknowledgementOrder],
                                 uploadComplete_? : Option[FileIngestion.UploadCompleteAcknowledgementOrder],
                                 problems_? : Option[NonEmptyList[String]]) {
    def isBegun = originalOrder_?.isDefined
    def isCompletedSuccessfully = uploadComplete_?.isDefined  && problems_?.isEmpty
    def isCompletedInFailure = problems_?.isDefined
    def isInProgress = !(isCompletedSuccessfully || isCompletedInFailure)
    def isCleansed = cleanseComplete_?.isDefined

    def enqueued(order: FileIngestion.IngestFileOrder) =
      this.copy(originalOrder_? = Some(order))

    def cleanseComplete(cleanseComplete: FileIngestion.CleanseCompleteAcknowledgementOrder) =
      this.copy(cleanseComplete_? = Some(cleanseComplete))

    def uploadComplete(uploadComplete: FileIngestion.UploadCompleteAcknowledgementOrder) =
      this.copy(uploadComplete_? = Some(uploadComplete))

    def withProblems(problems: NonEmptyList[String]) =
      this.copy(problems_? = Some(problems))

    /** Unsafe get -- for use when we are guaranteed original order is populated */
    def originalOrder_! = originalOrder_?.get
    /** Unsafe get -- for use when we are guaranteed clease completed successfully */
    def cleanseComplete_! = cleanseComplete_?.get
    /** Unsafe get -- for use when we are guaranteed upload completed successfully */
    def uploadComplete_! = uploadComplete_?.get

    def toFileIngestionProtocolState: FileIngestion.State =
      if(isCompletedSuccessfully) FileIngestion.Complete(originalOrder_!.cycleId, originalOrder_!.fileInfo.dataFile, Instant.EPOCH, None)
      else if(isCompletedInFailure) FileIngestion.Complete(originalOrder_!.cycleId, originalOrder_!.fileInfo.dataFile, Instant.EPOCH, problems_?)
      else if(isCleansed) FileIngestion.Uploading(originalOrder_!.cycleId, originalOrder_!.fileInfo.dataFile, Instant.EPOCH)
      else if(isBegun) FileIngestion.Cleansing(originalOrder_!.cycleId, originalOrder_!.fileInfo.dataFile, Instant.EPOCH)
      else FileIngestion.EmptyState
  }
  object InternalState {
    def empty: InternalState = InternalState(None, None, None, None)
  }

  sealed trait Event extends JsonSerialization {
    def entityId: String
  }
  case class FileIngestionEnqueuedEvent(originalOrder: FileIngestion.IngestFileOrder, override val entityId: String, timestamp: Instant)
    extends Event
  case class FileCleanseCompleteEvent(cleanseCompleteOrder: FileIngestion.CleanseCompleteAcknowledgementOrder,
                                      override val entityId: String, timestamp: Instant)
    extends Event
  case class FileCleanseProblemsEvent(problems: NonEmptyList[String], override val entityId: String, timestamp: Instant)
    extends Event
  case class FileUploadCompleteEvent(uploadCompleteOrder: FileIngestion.UploadCompleteAcknowledgementOrder,
                                     override val entityId: String, timestamp: Instant)
    extends Event
  case class FileUploadProblemsEvent(problems: NonEmptyList[String], override val entityId: String, timestamp: Instant)
    extends Event

  def handleEvent(state: InternalState, event: Event)  = event match {
    case FileIngestionEnqueuedEvent(order, _, timestamp) => state.enqueued(order)
    case FileCleanseCompleteEvent(cleanseCompleteOrder, _, timestamp) => state.cleanseComplete(cleanseCompleteOrder)
    case FileUploadCompleteEvent(uploadCompleteOrder, _, timestamp) => state.uploadComplete(uploadCompleteOrder)
    case FileCleanseProblemsEvent(problems, _, timestamp) => state.withProblems(problems)
    case FileUploadProblemsEvent(problems, _, timestamp) => state.withProblems(problems)
  }

  def handleCommand(state: InternalState, command: FileIngestion.Order): ReplyEffect[Event, InternalState] = {
    log.info(s"Handling command $command in state $state")

    command match {
      case order @ FileIngestion.IngestFileOrder(cycleId, userId, host, dbInfo, fileInfo) =>
        if(state.isBegun) Effect.noReply //...this is a duplicate request for a given (cycleId, dataFile) pair: ignore...
        else
        Effect.persist(FileIngestionEnqueuedEvent(order, entityIdFor(cycleId, fileInfo.dataFile), Instant.now()))
          .thenNoReply

      case order @ FileIngestion.IngestFileOrderWithNotification(originalOrder, replyTo) =>
        if(state.isBegun) Effect.reply(replyTo)(FileIngestion.IngestFileOrderRejected(originalOrder.cycleId, originalOrder.fileInfo.dataFile,
          NonEmptyList(s"File upload for file ${originalOrder.fileInfo.dataFile} within cycle ${originalOrder.cycleId} has already been launched", Nil)))
        else
          Effect.persist(FileIngestionEnqueuedEvent(originalOrder, entityIdFor(originalOrder.cycleId, originalOrder.fileInfo.dataFile), Instant.now()))
            .thenReply(replyTo)(state => FileIngestion.IngestFileOrderAcknowledgement(originalOrder.cycleId, originalOrder.fileInfo.dataFile,
              originalOrder))

      case order @ FileIngestion.CleanseCompleteAcknowledgementOrder(cleasedDataFile) =>
        Effect.persist(FileCleanseCompleteEvent(order, entityIdFor(state.originalOrder_!.cycleId, state.originalOrder_!.fileInfo.dataFile), Instant.now()))
          .thenNoReply

      case order @ FileIngestion.UploadCompleteAcknowledgementOrder() =>
        Effect.persist(FileUploadCompleteEvent(order, entityIdFor(state.originalOrder_!.cycleId, state.originalOrder_!.fileInfo.dataFile), Instant.now()))
          .thenNoReply

      case FileIngestion.ProblemsAcknowledgementOrder(problems) =>
        //...if cleanse is not complete, then this is a problem with asynchronous cleasning process...
        if(!state.cleanseComplete_?.isDefined)
          Effect.persist(FileCleanseProblemsEvent(problems, entityIdFor(state.originalOrder_!.cycleId, state.originalOrder_!.fileInfo.dataFile), Instant.now()))
            .thenNoReply

        //...if cleanse *is* complete, then this is a problem with asynchronous uploading process...
        else
        Effect.persist(FileUploadProblemsEvent(problems, entityIdFor(state.originalOrder_!.cycleId, state.originalOrder_!.fileInfo.dataFile), Instant.now()))
          .thenNoReply

      case FileIngestion.FileIngestStateRetrieveOrder(replyTo) =>
        Effect.reply(replyTo)(FileIngestion.CurrentState(state.toFileIngestionProtocolState))
    }
  }

  val EntityKey: EntityTypeKey[FileIngestion.Order] = EntityTypeKey[FileIngestion.Order]("FileIngestion")

  def entityIdFor(cycleId: String, dataFile: String) = s"[$cycleId]$dataFile"

  def init(system: ActorSystem[_], eventProcessorSettings: EventProcessorSettings): Unit = {
    ClusterSharding(system).init(Entity(EntityKey) { entityContext =>
      val n = math.abs(entityContext.entityId.hashCode % eventProcessorSettings.parallelism)
      val eventProcessorTag = eventProcessorSettings.tagPrefix + "-" + n
      FileIngestionEntity(entityContext.entityId, Set(eventProcessorTag))
    })
  }

  def apply(fileIngestionId: String, eventProcessorTags: Set[String]): Behavior[FileIngestion.Order] = {
    EventSourcedBehavior
      .withEnforcedReplies[FileIngestion.Order, Event, InternalState](
        PersistenceId(EntityKey.name, fileIngestionId),
        InternalState.empty,
        (state, command) => handleCommand(state, command),
        (state, event) => handleEvent(state, event))
      .withTagger(_ => eventProcessorTags)
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }
}