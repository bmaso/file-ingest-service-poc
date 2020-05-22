package bmaso.file_ingest_service_poc.testcli

import java.util.concurrent.Executors

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.dispatch.ExecutionContexts
import bmaso.akka.event_processor._
import bmaso.file_ingest_service_poc.cluster_node.{FileIngestionEntity, FileIngestionEventProcessorStream}
import bmaso.file_ingest_service_poc.protocol.FileIngestion
import org.h2.tools.Server
import slick.jdbc.H2Profile.api._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Intended to be run as a single node, performing a complete file upload one the node has started. Inits both the
 * `FileIngestEntity` and `FileIngestionEventProcessorStream` types. Tests that events are being properly tagged,
 * and event processor stream receives events, and interactions b/w entities and processor streams works as intended
 * in the happy path.
 */
object EntityAndEventProcessorIntegrationTestCLI {
  /** Single-thread executor used for one-off futures side-effects and mapping/processing */
  implicit val oneOffEC = ExecutionContexts.fromExecutor(Executors.newSingleThreadExecutor())

  def main(args: Array[String]): Unit = {
    val cycleId = args(0)
    val dataFile = args(1)

    if(cycleId == null || cycleId.length == 0) throw new IllegalArgumentException("First CLI arg must be the cycle ID")
    if(dataFile == null || dataFile.length == 0) throw new IllegalArgumentException("Second CLI arg must be the data file")

    val system = ActorSystem(ActorSystemRootActor(), "FileIngestion")

    //...this node is always self-hosts its H2 TCP server; create it and capture reference so we can register it
    //   for shutdown when node comes down...
    val h2_server = startDatabaseServerSync(system, true)

    //...start this node...
    startNode(system) {
      val fileIngestOrder = FileIngestion.IngestFileOrder(cycleId, "someUser", "someHosts",
        FileIngestion.IngestFileOrder.DBInfo("targetDatabase", "targetSchema", "targetTable"),
        FileIngestion.IngestFileOrder.FileInfo(dataFile, "~", List(
          FileIngestion.IngestFileOrder.ColumnInfo("col1", "type1"),
          FileIngestion.IngestFileOrder.ColumnInfo("col2", "type2"),
          FileIngestion.IngestFileOrder.ColumnInfo("col3", "type3")
        )))
      val cleanseCompleteAckOrder = FileIngestion.CleanseCompleteAcknowledgementOrder(dataFile + "/cleansed")
      val uploadCompleteAckOrder = FileIngestion.UploadCompleteAcknowledgementOrder()

      val entityRef =
        ClusterSharding(system).entityRefFor[FileIngestion.Order](FileIngestionEntity.EntityKey, FileIngestionEntity.entityIdFor(cycleId, dataFile))

      entityRef ! fileIngestOrder
    }

    system.whenTerminated.onComplete(_ => h2_server.shutdown())

    println("Node Up! Press [ENTER] to quit")
    Console.in.readLine()

    system.whenTerminated.onComplete {
      case Success(_) => System.exit(0)
      case Failure(ex) =>
        ex.printStackTrace
        System.exit(-1)
    }

    system.terminate
  }

  /**
   * Synchronously start a local H2 TCP database server. Should only be invoked by a single node in the cluster. All
   * nodes then should be configured with "localhost" in the slick DB URL.
   */
  def startDatabaseServerSync(system: ActorSystem[ActorSystemRootActor.StartUp], dropTables: Boolean = false): Server = {
    //...Start H2 server; The '-ifNotExists' flag tells H2 to create any databases when anyone attempt to open
    //   one. Without this flag then the database must be created ahead of time using the H2 Sheel or some other
    //   mechanism...
    val h2_server = Server.createTcpServer("-ifNotExists").start()

    //...create journal, snapshot, and offset tables if they don't exist yet. Akka config already
    //    includes H2 database configuration we can use to create DB connection...
    val db = Database.forConfig("slick.db", system.settings.config)

    val drops =
      if(dropTables)  {
        List(
          sqlu"""
                  DROP TABLE IF EXISTS "journal";
                """,
          sqlu"""
                  DROP TABLE IF EXISTS "snapshot";
                """,
          sqlu"""
                  DROP TABLE IF EXISTS "processor_offsets";
                """,
        )
      } else List.empty[DBIO[Int]]

    val inserts =
      List(
        sqlu"""
                CREATE TABLE IF NOT EXISTS "journal" (
                "ordering" BIGINT AUTO_INCREMENT,
                "persistence_id" VARCHAR(255) NOT NULL,
                "sequence_number" BIGINT NOT NULL,
                "deleted" BOOLEAN DEFAULT FALSE NOT NULL,
                "tags" VARCHAR(255) DEFAULT NULL,
                "message" BYTEA NOT NULL,
                PRIMARY KEY("persistence_id", "sequence_number")
              );
              """,
        sqlu"""
                CREATE UNIQUE INDEX "journal_ordering_idx" ON "journal"("ordering");
              """,
        sqlu"""
                CREATE TABLE IF NOT EXISTS "snapshot" (
                "persistence_id" VARCHAR(255) NOT NULL,
                "sequence_number" BIGINT NOT NULL,
                "created" BIGINT NOT NULL,
                "snapshot" BYTEA NOT NULL,
                PRIMARY KEY("persistence_id", "sequence_number")
              );
              """,
        sqlu"""
                CREATE TABLE IF NOT EXISTS "processor_offsets" (
                "processor_id" VARCHAR(255) NOT NULL,
                "tag" VARCHAR(255) NOT NULL,
                "offset_val" BIGINT NOT NULL,
                PRIMARY KEY("processor_id", "tag")
              );
              """,
      )

    val db_fut = db.run(DBIO.sequence(drops ++ inserts))
    val result = Await.result(db_fut, 5 seconds)
    println(s"Database creation result: $result")

    h2_server
  }

  def startNode(system: ActorSystem[ActorSystemRootActor.StartUp])(postNodeStart_f: => Unit): Unit = {
    val startup_fut =
      system.ask[ActorSystemRootActor.StartUpComplete.type](ref => ActorSystemRootActor.StartUp(ref))(5 seconds, system.scheduler)
    startup_fut.onComplete({
      case Success(ActorSystemRootActor.StartUpComplete) =>
        postNodeStart_f
      case Failure(exception) =>
        exception.printStackTrace
        system.terminate
    })(oneOffEC)

    Await.ready(startup_fut, 5 seconds)
  }

  object ActorSystemRootActor {
    case class StartUp(replyTo: ActorRef[StartUpComplete.type])
    case object StartUpComplete

    def apply(): Behavior[StartUp] = {
      Behaviors.setup[StartUp] { context =>
        val system = context.system
        val settings = EventProcessorSettings(system)

        //...register the FileIngestionEntity type in the cluster so that this entity type is addressable...
        FileIngestionEntity.init(system, settings)

        EventProcessor.init(
          system,
          settings,
          tag => new FileIngestionEventProcessorStream(system, system.executionContext, settings.id, tag))

        Behaviors.receiveMessage {
          case StartUp(replyTo) =>
            replyTo ! StartUpComplete
            Behaviors.same
          case _ =>
            println("Whuzza???")
            Behaviors.same
        }
      }
    }
  }
}
