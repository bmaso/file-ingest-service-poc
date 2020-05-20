package bmaso.file_ingest_service_poc.cluster_node

import cats.data.NonEmptyList
import bmaso.file_ingest_service_poc.protocol._
import java.time.Instant

import akka.actor.typed.{ActorSystem, Behavior, SupervisorStrategy}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import bmaso.akka.event_processor.EventProcessorSettings
import scala.concurrent.duration._

/**
 * This model defines the persistent
 */
object FileIngestionEntity {
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
      if(isCompletedSuccessfully) FileIngestion.Complete(originalOrder_!.cycleId, originalOrder_!.dataFile, Instant.EPOCH, None)
      else if(isCompletedInFailure) FileIngestion.Complete(originalOrder_!.cycleId, originalOrder_!.dataFile, Instant.EPOCH, problems_?)
      else if(isCleansed) FileIngestion.Uploading(originalOrder_!.cycleId, originalOrder_!.dataFile, Instant.EPOCH)
      else if(isBegun) FileIngestion.Cleansing(originalOrder_!.cycleId, originalOrder_!.dataFile, Instant.EPOCH)
      else FileIngestion.EmptyState
  }
  object InternalState {
    def empty: InternalState = InternalState(None, None, None, None)
  }

  sealed trait Event
  case class FileIngestionEnqueuedEvent(originalOrder: FileIngestion.IngestFileOrder, timestamp: Instant)
    extends Event
  case class FileCleanseCompleteEvent(cleanseCompleteOrder: FileIngestion.CleanseCompleteAcknowledgementOrder, timestamp: Instant)
    extends Event
  case class FileCleanseProblemsEvent(problems: NonEmptyList[String], timestamp: Instant)
    extends Event
  case class FileUploadCompleteEvent(uploadCompleteOrder: FileIngestion.UploadCompleteAcknowledgementOrder, timestamp: Instant)
    extends Event
  case class FileUploadProblemsEvent(problems: NonEmptyList[String], timestamp: Instant)
    extends Event

  def handleEvent(state: InternalState, event: Event)  = event match {
    case FileIngestionEnqueuedEvent(order, timestamp) => state.enqueued(order)
    case FileCleanseCompleteEvent(cleanseCompleteOrder, timestamp) => state.cleanseComplete(cleanseCompleteOrder)
    case FileUploadCompleteEvent(uploadCompleteOrder, timestamp) => state.uploadComplete(uploadCompleteOrder)
    case FileCleanseProblemsEvent(problems, timestamp) => state.withProblems(problems)
    case FileUploadProblemsEvent(problems, timestamp) => state.withProblems(problems)
  }

  def handleCommand(state: InternalState, command: FileIngestion.Order): ReplyEffect[Event, InternalState] = {
    println(s"Handling command $command in state $state")

    command match {
      case order @ FileIngestion.IngestFileOrder(cycleId, userId, host, dataFile, dbInfo, fileInfo) =>
        if(state.isBegun) Effect.noReply //...this is a duplicate request for a given (cycleId, dataFile) pair: ignore...
        else
        Effect.persist(FileIngestionEnqueuedEvent(order, Instant.now()))
          .thenNoReply()

      case order @ FileIngestion.IngestFileOrderWithNotification(originalOrder, replyTo) =>
        if(state.isBegun) Effect.reply(replyTo)(FileIngestion.IngestFileOrderRejected(originalOrder.cycleId, originalOrder.dataFile,
          NonEmptyList(s"File upload for file ${originalOrder.dataFile} within cycle ${originalOrder.cycleId} has already been launched", Nil)))
        else
          Effect.persist(FileIngestionEnqueuedEvent(originalOrder, Instant.now()))
            .thenReply(replyTo)(state => FileIngestion.IngestFileOrderAcknowledgement(originalOrder.cycleId, originalOrder.dataFile,
              originalOrder))

      case order @ FileIngestion.CleanseCompleteAcknowledgementOrder(cleasedDataFile) =>
        Effect.persist(FileCleanseCompleteEvent(order, Instant.now()))
          .thenNoReply

      case order @ FileIngestion.UploadCompleteAcknowledgementOrder() =>
        Effect.persist(FileUploadCompleteEvent(order, Instant.now()))
          .thenNoReply

      case FileIngestion.ProblemsAcknowledgementOrder(problems) =>
        //...if cleanse is not complete, then this is a problem with asynchronous cleasning process...
        if(!state.cleanseComplete_?.isDefined)
          Effect.persist(FileCleanseProblemsEvent(problems, Instant.now()))
            .thenNoReply

        //...if cleanse *is* complete, then this is a problem with asynchronous uploading process...
        else
        Effect.persist(FileUploadProblemsEvent(problems, Instant.now()))
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