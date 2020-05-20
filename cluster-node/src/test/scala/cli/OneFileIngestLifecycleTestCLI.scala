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
import com.typesafe.config.Config
import org.h2.tools.Server
import slick.jdbc.H2Profile.api._
import slick.sql.SqlAction

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}


/**
 * This is a standalone application, intended to be a member of a single-node cluster
 */
object OneFileIngestLifecycleTestCLI {
  /** Single-thread executor used for one-off futures side-effects and mapping/processing */
  implicit val oneOffEC = ExecutionContexts.fromExecutor(Executors.newSingleThreadExecutor())

  def main(args: Array[String]): Unit = {
    //...this this node is hosting the H2 TCP server, create it and capture reference so we can register it
    //   for shutdown when node comes down...
//    val h2_server_? : Option[Server] =
//      if(args.length >= 1 && args(0) == "h2-database") {
//        Some(Server.createTcpServer().start())
//      } else None

    //...start this node...
    startNode() { system =>
      //...if command-line includes directions to do so, start file ingest of a single file...
      if(args.length >= 3 && args(0) == "do-lifecycle") {
        val cycleId = args(1)
        val dataFile = args(2)

        val fileIngestOrder = FileIngestion.IngestFileOrder(cycleId, "someUser", "someHosts", dataFile,
          FileIngestion.IngestFileOrder.DBInfo("targetDatabase", "targetSchema", "targetTable"),
          FileIngestion.IngestFileOrder.FileInfo("~", List(
            FileIngestion.IngestFileOrder.ColumnInfo("col1", "type1"),
            FileIngestion.IngestFileOrder.ColumnInfo("col2", "type2"),
            FileIngestion.IngestFileOrder.ColumnInfo("col3", "type3")
          )))
        val cleanseCompleteAckOrder = FileIngestion.CleanseCompleteAcknowledgementOrder(dataFile + "/cleansed")
        val uploadCompleteAckOrder = FileIngestion.UploadCompleteAcknowledgementOrder()

        val entityRef =
          ClusterSharding(system).entityRefFor[FileIngestion.Order](FileIngestionEntity.EntityKey, FileIngestionEntity.entityIdFor(cycleId, dataFile))

        entityRef ! fileIngestOrder

        //...artificially wait a second to allow ingest process to start, the print out state...
        Thread.sleep(1000)
        retrieveAndPrintState(entityRef)

        entityRef ! cleanseCompleteAckOrder

        //...artificially wait a second to allow ingest process to start, the print out state...
        Thread.sleep(1000)
        retrieveAndPrintState(entityRef)

        entityRef ! uploadCompleteAckOrder

        //...artificially wait a second to allow ingest process to start, the print out state...
        Thread.sleep(1000)
        retrieveAndPrintState(entityRef)

        println("Should have completely walked through file upload lifecycle by now. Press [ENTER] to quit.")
        Console.in.readLine()
        system.terminate()

        system.whenTerminated.andThen({
          case Success(_) =>
            System.exit(0)
          case Failure(exception) =>
            exception.printStackTrace
            System.exit(-1)
        })
      }

      //...if this node is the host of the H2 TCP server then register it for shutdown when the actor system shuts down...
//      h2_server_?.foreach { server =>
//        //...single-thread executor runs the H2 server callback
//        implicit val ec = ExecutionContexts.fromExecutor(Executors.newSingleThreadExecutor())
//        system.whenTerminated.onComplete(_ => server.shutdown())
//      }
    }
  }

  def retrieveAndPrintState(entityRef: EntityRef[FileIngestion.Order]): Unit = {
    val status_fut =
      entityRef.ask[FileIngestion.CurrentState](ref => FileIngestion.FileIngestStateRetrieveOrder(ref))(5 seconds)

    val state = Await.result(status_fut, 5 seconds)
    println(s"Status 1, response to initial file order: $state")
  }

  def startNode()(postNodeStart_f: (ActorSystem[_]) => Unit): Unit = {
    val system = ActorSystem(ActorSystemRootActor(), "FileIngestion")

    val startup_fut =
      system.ask[ActorSystemRootActor.StartUpComplete.type](ref => ActorSystemRootActor.StartUp(ref))(5 seconds, system.scheduler)
    startup_fut.onComplete({
      case Success(ActorSystemRootActor.StartUpComplete) =>
        postNodeStart_f(system)
      case Failure(exception) =>
        exception.printStackTrace
        system.terminate
    })(oneOffEC)

    Await.ready(startup_fut, 5 seconds)

    println("Node started")
  }

}

object ActorSystemRootActor {
  case class StartUp(replyTo: ActorRef[StartUpComplete.type])
  case object StartUpComplete

  def apply(): Behavior[StartUp] = {
    println("Starting root actor...")

    Behaviors.setup[StartUp] { context =>
      val system = context.system
      val settings = EventProcessorSettings(system)

      //...create journal, snapshot, and offset tables if they don't exist yet. Akka config already
      //    includes H2 database configuration we can use to create DB connection...
      val db = Database.forConfig("slick.db", system.settings.config)
      val inserts = {
        val ddls = List(
          sqlu"""
                DROP TABLE IF EXISTS PUBLIC."journal";
              """,
          sqlu"""
                CREATE TABLE IF NOT EXISTS PUBLIC."journal" (
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
                CREATE UNIQUE INDEX "journal_ordering_idx" ON PUBLIC."journal"("ordering");
              """,
          sqlu"""
                DROP TABLE IF EXISTS PUBLIC."snapshot";
              """,
          sqlu"""
                CREATE TABLE IF NOT EXISTS PUBLIC."snapshot" (
                "persistence_id" VARCHAR(255) NOT NULL,
                "sequence_number" BIGINT NOT NULL,
                "created" BIGINT NOT NULL,
                "snapshot" BYTEA NOT NULL,
                PRIMARY KEY("persistence_id", "sequence_number")
              );
              """
        )

        DBIO.sequence(ddls)
      }
      val db_fut = db.run(inserts)
      Await.ready(db_fut, 5 seconds)

      //...register the FileIngestionEntity type in the cluster so that this entity type is addressable...
      FileIngestionEntity.init(system, settings)

      // TODO: start sharded read-model actors to handle entity event streams...
      //EventProcessor.init(
      //  system,
      //  settings,
      //  tag => new FileIngestionEventProcessorStream(system, system.executionContext, settings.id, tag))

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
