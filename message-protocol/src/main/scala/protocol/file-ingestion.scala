package bmaso.file_ingest_service_poc.protocol

import cats.data.NonEmptyList
import akka.actor.typed.ActorRef

/**
 * Describes the Akka messages exchanged between a file ingestion processor,
 * typically represented as a `ActorRef[FileIngestion.Command]`, and a driver
 * application.
 **/
object FileIngestion {
  sealed trait Message

  /**
   * The subtype of this sealed trait comprse all of the notification messages
   * sent from the file ingestion processor about workflow FSM progress.
   **/
  trait Notification extends Message
  case class IngestFileOrderAcknowledgement(cycleId: String, dataFile: String,
    fsmId: String, order: IngestFileOrder) extends Notification
  case class IngestFileOrderRejected(cycleId: String, dataFile: String, problems: NonEmptyList[String])
    extends Notification
  case class CleansingStageEntered(cycleId: String, dataFile: String, fsmId: String)
    extends Notification
  case class CleansingStageExited(cycleId: String, dataFile: String, fsmId: String)
    extends Notification
  case class IngestingStageEntered(cycleId: String, dataFile: String, fsmId: String)
    extends Notification
  case class IngestingStageExited(cycleId: String, dataFile: String, fsmId: String)
    extends Notification
  case class Completed(cycleId: String, dataFile: String, fsmId: String,
    order: IngestFileOrder, result: Completed.Result) extends Notification
  object Completed {
    sealed trait Result

    trait Successful extends Result
    case object Successfully extends Successful

    trait Unsuccessful extends Result
    case object IgnoredDuplicate extends Unsuccessful
    case class UnexpectedCleansingStageProblems(problems: NonEmptyList[String]) extends Unsuccessful
    case class UnexpectedIngestingStageProblems(problems: NonEmptyList[String]) extends Unsuccessful

    case object Indeterminant extends Result
  }

  trait Order extends Message
  case class IngestFileOrder(
    cycleId: String,
    userId: String,
    host: String,
    dataFile: String,
    dbInfo: FileIngestionOrder.DBInfo,
    fileInfo: FileIngestionOrder.FileInfo
  ) extends Order
  case class IngestFileOrderWithNotification(
    order: IngestFileOrder,
    notificationActor: ActorRef[FileIngestion.Notification]
  ) extends Order
  object FileIngestionOrder {
    case class DBInfo(targetDatabase: String, targetSchema: String, targetTable: String)
    case class FileInfo(delimiter: String, columns: List[ColumnInfo])
    case class ColumnInfo(columnName: String, protegrityDataType: String)
  }
}
