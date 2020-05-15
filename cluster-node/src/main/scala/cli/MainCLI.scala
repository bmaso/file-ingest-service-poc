package bmaso.file_ingest_service_poc.cluster_node

/**
 * Akka Cluster node which publishes a single behavior able to
 * fulfill the mesaging protocol defined by `ActorRef[FileIngestion.Order]`.
 *
 * This particular implementation of the begavior utilizes:
 * * Akka Persistence to ensure actor failure and recovery will continue the various
 *   file ingestion workflows where they left off.
 * * Some DB or longer-term persistence in order to record the status of
 *   individual file ingestion so that the same file cannot be re-ingested
 *   multiple times.
 **/
object MainCLI {
  def main(args: Array[String]): Unit = {
    println("/glancing angsty, brooding and side-ways at the world...")
  }
}
