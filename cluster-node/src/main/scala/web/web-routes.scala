package bmaso.file_ingest_service_poc.cluster_node

import scala.concurrent.Future
import cats.data.NonEmptyList
import spray.json._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.{Directives, Route}
import akka.util.{ByteString, Timeout}
import bmaso.file_ingest_service_poc.protocol.FileIngestion
import org.slf4j.{Logger, LoggerFactory}

/**
 * Spray JSON declarations of how to (un)marshal message types
 */
object JsonFormats extends DefaultJsonProtocol {
  implicit val fileColumnFormat = jsonFormat2(ClusterNodeRoutes.IngestFileColumn)
  implicit val ingestFileFormat = jsonFormat10(ClusterNodeRoutes.IngestFile)
  implicit val problemsFormat = jsonFormat1(ClusterNodeRoutes.ProblemList)
}

/**
 * Akka HTTP web routing for the cluster node. The companion object defines the JSON-serializable case classes
 * that make up the payloads of all types of requests and responses.
 **/
object ClusterNodeRoutes {

  /**
   * Per the project spec, the web service for ordering a file ingest must support the follow example JSON input:
   * ```
   * {
   *   "cycleId": "123",
   *   "userId": "serviceAccount",
   *   "host": "whatismycallit",
   *   "dataFile": "/opt/project/daily/blah-2020-040-23.dat",
   *   "notifications": "abc.defg@xxx.com,blah.blah@uxx.com",
   *   "targetDatabase": "TARGETDB",
   *   "targetSchema": "WHATEVER",
   *   "targetTable": "MY_TABLE",
   *   "delimiter": "~",
   *   "columns": [
   *     { "columnName": "COLUMN1", "protegrityDataType": "NA" },
   *     { "columnName": "COLUMN2", "protegrityDataType": "DeAccountNum" },
   *     { "columnName": "COLUMN3", "protegrityDataType": "NA" },
   *     { "columnName": "COLUMN4", "protegrityDataType": "DeSSN" },
   *     { "columnName": "COLUMN5", "protegrityDataType": "NA" }
   *   ]
   * }
   * ```
   */
  case class IngestFile(
      cycleId: String,
      userId: String,
      host: String,
      dataFile: String,
      notifications: String,
      targetDatabase: String,
      targetSchema: String,
      targetTable: String,
      delimiter: String,
      columns: List[IngestFileColumn])
  case class IngestFileColumn(columnName: String, protegrityDataType: String)

  case class ProblemList(problems: List[String])
}

class ClusterNodeRoutes()(implicit system: ActorSystem[_]) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit private val timeout: Timeout =
    Timeout.create(system.settings.config.getDuration("file-ingest.http.internalResponseTimeout"))
  private val sharding = ClusterSharding(system)

  import ClusterNodeRoutes._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import akka.http.scaladsl.server.Directives._
  import JsonFormats._

  def ingestFileOrderWithAck(replyTo: ActorRef[FileIngestion.Notification], data: IngestFile) = {
    val o = FileIngestion.IngestFileOrder(data.cycleId, data.userId, data.host,
      FileIngestion.IngestFileOrder.DBInfo(data.targetDatabase, data.targetSchema, data.targetTable),
      FileIngestion.IngestFileOrder.FileInfo(data.dataFile, data.delimiter,
        data.columns.map(col => FileIngestion.IngestFileOrder.ColumnInfo(col.columnName, col.protegrityDataType))))

    FileIngestion.IngestFileOrderWithNotification(o, replyTo)
  }

  def problemListFromNel(nel: NonEmptyList[String]): ProblemList = ProblemList(nel.head +: nel.tail)

  val fileIngestRoutes: Route =
    pathPrefix("file-ingest") {
      concat(
        post{
          entity(as[IngestFile]) { data =>

            // TODO: should do some validation here...

            log.info(s"Handling POST request with payload: $data")

            val entityRef = sharding.entityRefFor(FileIngestionEntity.EntityKey,
              FileIngestionEntity.entityIdFor(data.cycleId, data.dataFile))

            val reply: Future[FileIngestion.Notification] =
              entityRef.ask[FileIngestion.Notification](ingestFileOrderWithAck(_, data))

              onSuccess(reply) {
                case FileIngestion.IngestFileOrderAcknowledgement(_, _, _) =>
                  complete(StatusCodes.Accepted)
                case FileIngestion.IngestFileOrderRejected(_, _, nelProblems) =>
                  complete(StatusCodes.BadRequest)
                  // TODO: Figure out how to get the problems list into the response body
                  // complete(StatusCodes.BadRequest, problemListFromNel(nelProblems))
              }
          }
        },
        pathPrefix(Segment) { cycleId =>
          log.debug(s"Parsed path segment for cycleId '$cycleId'")

          pathPrefix(Segment) { dataFile =>
            log.debug(s"Parsed path segment for dataFile '$dataFile'")
            log.debug(s"\tentityId for this ingest is '${FileIngestionEntity.entityIdFor(cycleId, dataFile)}'")

            get {
              val entityRef = sharding.entityRefFor(FileIngestionEntity.EntityKey,
                FileIngestionEntity.entityIdFor(cycleId, dataFile))

              val reply = entityRef.ask[FileIngestion.CurrentState](FileIngestion.FileIngestStateRetrieveOrder(_))

              onSuccess(reply) {
                case FileIngestion.CurrentState(FileIngestion.EmptyState) =>
                  complete(HttpResponse(status = StatusCodes.OK, entity = HttpEntity(`application/json`, ByteString("""{"status": "outside the horizon of knowledge"}"""))))
                case FileIngestion.CurrentState(_: FileIngestion.Enqueued) =>
                  complete(HttpResponse(status = StatusCodes.OK, entity = HttpEntity(`application/json`, ByteString("""{"status": "enqueued"}"""))))
                case FileIngestion.CurrentState(_: FileIngestion.Cleansing) =>
                  complete(HttpResponse(status = StatusCodes.OK, entity = HttpEntity(`application/json`, ByteString("""{"status": "cleansing"}"""))))
                case FileIngestion.CurrentState(_: FileIngestion.Uploading) =>
                  complete(HttpResponse(status = StatusCodes.OK, entity = HttpEntity(`application/json`, ByteString("""{"status": "uploading"}"""))))
                case FileIngestion.CurrentState(_: FileIngestion.Complete) =>
                  complete(HttpResponse(status = StatusCodes.OK, entity = HttpEntity(`application/json`, ByteString("""{"status": "complete"}"""))))
              }
            }
          }
        }
      )
    }
}
