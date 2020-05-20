package bmaso.file_ingest_service_poc.cluster_node

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.persistence.typed.PersistenceId
import bmaso.akka.event_processor.EventProcessorStream

class FileIngestionEventProcessorStream(
                                        system: ActorSystem[_],
                                        executionContext: ExecutionContext,
                                        eventProcessorId: String,
                                        tag: String)
  extends EventProcessorStream[FileIngestionEntity.Event](system, executionContext, eventProcessorId, tag) {

  def processEvent(event: FileIngestionEntity.Event, persistenceId: PersistenceId, sequenceNr: Long): Future[Done] = {
    log.info(s"FileIngestionEventProcessor($tag) consumed $event from $persistenceId with seqNr $sequenceNr")

    // TBD: When file enqueued, asynchronously start cleanse process
    //   TBD: When cleanse finishes successfully, send FileIngestion.CleanseCompleteAcknowledgementOrder
    //   TBD: When cleanse finishes in failure, send FileIngestion.ProblemsAcknowledgementOrder

    // TBD: When file cleansed, asynchronously start upload process
    //   TBD: When upload finishes successfully, send FileIngestion.UploadCompleteAcknowledgementOrder
    //   TBD: When upload finishes in failure, send FileIngestion.ProblemsAcknowledgementOrder

    Future.successful(Done)
  }
}

