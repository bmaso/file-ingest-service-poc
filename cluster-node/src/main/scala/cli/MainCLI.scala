package bmaso.file_ingest_service_poc.cluster_node

// imports for command-line parsing features
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.AskPattern._
import bmaso.akka.event_processor.{EventProcessor, EventProcessorSettings}
import cats.implicits._
import com.monovore.decline._
import com.typesafe.config.{Config, ConfigFactory}
import org.h2.tools.Server
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.H2Profile.api._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

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
 * * `--clear-h2-database` -- if this node is hosting the H2 database (see `--host-h2=database-server`) and if this
 *   option is present, then the H2 database tables will be completely dropped and recreated. This basically resets
 *   the cluster into a "new" state w.r.t. file ingestion jobs.
 **/
object MainCLI extends CommandApp(
  name = "file-ingestion-cluster-node",
  header = "Runs an Akka Cluster node publishing file ingestion web services",
  main =  {
    val httpPortOpt = Opts.option[Int]("http-port", help = "Port to open for HTTP requests; must be unique for each node on the same host machine")
    val clusterPortOpt = Opts.option[Int]("cluster-port", help = "Port to use for internal cluster communications; must be unique for each node on the same host machine")
    val dbServerOpt = Opts.flag("host-h2-database-server", help = "If present, this node will host an H2 database server for other nodes to use").orFalse
    val cleanDbOpt = Opts.flag("clear-h2-database", help = "If present AND the --host-h2-database-server flag also present, then the database is completely cleared out before the server starts" ).orFalse

    (httpPortOpt, clusterPortOpt, dbServerOpt, cleanDbOpt).mapN { (httpPort, clusterPort, dbServerFlag, clearDbFlag) =>
      MainExecutable.execute(MainExecutable.Options(httpPort, clusterPort, dbServerFlag, clearDbFlag))
    }
  }
)

object MainExecutable {
  case class Options(httpPort: Int, clusterPort: Int, dbServerFlag: Boolean, clearDbFlag: Boolean)

  def execute(options: Options) = {
    import options._

    val log: Logger = LoggerFactory.getLogger(this.getClass)

    val system = ActorSystem(ActorSystemRootActor(), "FileIngestion", configWithClusterPort(clusterPort))

    //...this this node is hosting the H2 TCP server, create it and capture reference so we can register it
    //   for shutdown when node comes down...
    val h2_server_? : Option[Server] =
    if(dbServerFlag) {
      val ret = Some(startDatabaseServerSync(system, clearDbFlag))
      log.info("H2 Database Server started within this VM")
      ret
    } else {
      log.info("-- relying on an H2 server hosted by a separate process")
      None
    }

    //...start this node...
    startNode(system) {
      log.info(s"Node has been initialized and is now running in the cluster on port $clusterPort")
    }

    //...start the web server; note the server constructs a coordinated shutdown with the actor system...
    new FileIngestionServer(new ClusterNodeRoutes()(system).fileIngestRoutes, httpPort, system).start()
    log.info(s"Web server up and running on port $httpPort")

    //...set up shutdown functions for the H2 database and to bring the VM down when the actor system terminates...
    system.whenTerminated.onComplete(_ => h2_server_?.foreach(_.shutdown()))(system.executionContext)

    system.whenTerminated.onComplete ({
      case Success(_) => System.exit(0)
      case Failure(ex) =>
        ex.printStackTrace
        System.exit(-1)
    })(system.executionContext)

    //...invite the user to shut this server down...
    println("Node Up! Press [ENTER] to quit")
    Console.in.readLine()

    log.info("Shutting down")
    system.terminate
  }

  def configWithClusterPort(clusterPort: Int): Config =
    ConfigFactory.parseString(s"""
      akka.remote.artery.canonical.port = $clusterPort
       """).withFallback(ConfigFactory.load())


  /**
   * Synchronously start a local H2 TCP database server. Should only be invoked by a single node in the cluster. All
   * nodes then should be configured with "localhost" in the slick DB URL. E.g.: jdbc:h2:tcp://localhost/~/test
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
    })(system.executionContext)

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

        //...register the "sharded daemon" event processor, which monitors FileIngestionEntity evvents and carries
        //   out file cleansing and file ingests automatically when the correct entity events are observed...
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
