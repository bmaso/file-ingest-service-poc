package bmaso.file_ingest_service_poc.cluster_node

/**
 * Akka Cluster node which joins cluster sharding file ingestion entities and
 * file cleansing and uploading stream processors.
 *
 * CLI options:
 * * `--http-port <port>` -- server opens HTTP port for HTTP requests (see overall project documentation
 *   for exact HTTP/JSON protocol. Each node hosts HTTP services, and must use a different port.
 * * `--cluster-port <port>` -- indicates which port this node uses for cluster communications. Each node must use a
 *   different port.
 * * `--host-h2-database-server` -- if present, this node will host an H2 TCP database server. This is a convenience
 *   in case the operator does not want to run a stand-alone SQL server in a separate process. Useful only for testing
 *   purposes; a more robust DB setup should be employed in production deployments.
 **/
object MainCLI {
}
