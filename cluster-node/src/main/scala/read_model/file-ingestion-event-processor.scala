package bmaso.file_ingest_service_poc.cluster_node

import akka.Done
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.PersistenceId
import bmaso.akka.event_processor.EventProcessorStream
import bmaso.file_ingest_service_poc.protocol.{FileIngestion => FileIngestion_protocol}
import cats.data.NonEmptyList

import scala.util.{Failure, Success}
import scala.concurrent.{ExecutionContext, Future, Promise}

class FileIngestionEventProcessorStream(system: ActorSystem[_],
                                        executionContext: ExecutionContext,
                                        eventProcessorId: String,
                                        tag: String)
  extends EventProcessorStream[FileIngestionEntity.Event](system, executionContext, eventProcessorId, tag) {

  /**
   * For now we mock file cleansing using a future that always completes successfully in 15 seconds. The future value
   * is the "cleansed" file name.
   */
  def cleanseFileAsync(dataFile: String): Future[String] = {
    val p = Promise[String]

    executionContext.execute(() => {
      log.trace(s"...beginning cleansing of file $dataFile")
      Thread.sleep(15000)
      p.complete(Success(dataFile + "/cleansed"))
      log.trace(s"...completed cleansing of file $dataFile")
    })

    p.future
  }

  /**
   * For now we mock file uploading using a future that always completes successfully in 5 seconds. The future value
   * is just a unitary value.
   */
  def uploadFileAsync(file: String): Future[Unit] = {
    val p = Promise[Unit]

    executionContext.execute(() => {
      log.trace(s"...beginning uploading of file $file")
      Thread.sleep(5000)
      p.complete(Success(()))
      log.trace("...completed upload of file $file")
    })

    p.future
  }

  def processEvent(event: FileIngestionEntity.Event, persistenceId: PersistenceId, sequenceNr: Long): Future[Done] = {
    lazy val entityRef = ClusterSharding(system).entityRefFor(FileIngestionEntity.EntityKey, event.entityId)

    log.debug(s"FileIngestionEventProcessor($tag) consumed $event from $persistenceId with seqNr $sequenceNr")

    event match {
      case FileIngestionEntity.FileIngestionEnqueuedEvent(originalOrder, _, timestamp) =>
        val cleanse_fut = cleanseFileAsync(originalOrder.dataFile)
        cleanse_fut.onComplete ({
          case Success(cleansedFile) =>
            log.info(s"Completed cleansing of file ${originalOrder.dataFile} => ${cleansedFile}")
            entityRef ! FileIngestion_protocol.CleanseCompleteAcknowledgementOrder(cleansedFile)

          case Failure(exception) =>
            log.warn("Problem cleansing file $originalOrder.dataFile", exception)
            entityRef ! FileIngestion_protocol.ProblemsAcknowledgementOrder(NonEmptyList(exception.getMessage, List.empty))
        })

      case FileIngestionEntity.FileCleanseCompleteEvent(cleanseCompleteOrder, _,timestamp) =>
        val upload_fut = uploadFileAsync(cleanseCompleteOrder.cleansedDataFile)
        upload_fut.onComplete ({
          case Success(_) =>
            log.info(s"Completed uploading of file ${cleanseCompleteOrder.cleansedDataFile}")
            entityRef ! FileIngestion_protocol.UploadCompleteAcknowledgementOrder()

          case Failure(exception) =>
            log.warn("Problem uploading file $originalOrder.dataFile", exception)
            entityRef ! FileIngestion_protocol.ProblemsAcknowledgementOrder(NonEmptyList(exception.getMessage, List.empty))
        })

      case _  =>
        //...all other events are ignorable
        log.debug(s"Processor ignoring event $event")
    }

    // TBD: When file cleansed, asynchronously start upload process
    //   TBD: When upload finishes successfully, send FileIngestion.UploadCompleteAcknowledgementOrder
    //   TBD: When upload finishes in failure, send FileIngestion.ProblemsAcknowledgementOrder

    Future.successful(Done)
  }
}

