package bmaso.file_ingest_service_poc.cluster_node

import cats.data.NonEmptyList
import bmaso.file_ingest_service_poc.protocol._
import java.time.Instant

import akka.persistence.typed.scaladsl.Effect

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

  def handleCommand(state: InternalState, command: FileIngestion.Order): Effect[Event, InternalState] = command match {
    case order @ FileIngestion.IngestFileOrder(cycleId, userId, host, dataFile, dbInfo, fileInfo) =>
      if(state.isBegun) Effect.none //...this is a duplicate request for a given (cycleId, dataFile) pair: ignore...
      else
        Effect.persist(FileIngestionEnqueuedEvent(order, Instant.now()))

    case order @ FileIngestion.IngestFileOrderWithNotification(originalOrder, replyTo) =>
      if(state.isBegun) Effect.reply(replyTo)(FileIngestion.IngestFileOrderRejected(originalOrder.cycleId, originalOrder.dataFile,
          NonEmptyList(s"File upload for file ${originalOrder.dataFile} within cycle ${originalOrder.cycleId} has already been launched", Nil)))
      else
        Effect.persist(FileIngestionEnqueuedEvent(originalOrder, Instant.now()))
          .thenReply(replyTo)(state => FileIngestion.IngestFileOrderAcknowledgement(originalOrder.cycleId, originalOrder.dataFile,
            originalOrder))

    case order @ FileIngestion.CleanseCompleteAcknowledgementOrder(cleasedDataFile) =>
      Effect.persist(FileCleanseCompleteEvent(order, Instant.now()))

    case order @ FileIngestion.UploadCompleteAcknowledgementOrder() =>
      Effect.persist(FileUploadCompleteEvent(order, Instant.now()))

    case FileIngestion.ProblemsAcknowledgementOrder(problems) =>
      //...if cleanse is not complete, then this is a problem with asynchronous cleasning process...
      if(!state.cleanseComplete_?.isDefined)
        Effect.persist(FileCleanseProblemsEvent(problems, Instant.now()))

      //...if cleanse *is* complete, then this is a problem with asynchronous uploading process...
      else
        Effect.persist(FileUploadProblemsEvent(problems, Instant.now()))
  }
}