# file-ingest-service-poc

File ingesting web service POC
* POC of reference architecture for publishing stateful, persistent
  web services emphasizing resilient, elastic server clusters
* Akka Cluster, Akka Cluster Sharding, Akka Cluster Sharded Daemon, Akka Streaming,
  Akka HTTP
* Build right out of the box: `gradle` build, with of `gradle` wrapper included, You
  don't have to install locally if you don't already have `gradle`.

## Local Build and Packaging

Clone with `git`:
```
git clone https://github.com/bmaso/file-ingest-service-poc.git
```

Build and test locally:
```
# From the cloned project directory

# MacOS, Unix or WSL
./gradlew clean test jar

# Windows
gradlew.bat clean test jar
```

## Cluster Nodes

The system architecture is  cluster of identical, co-reachable nodes on a network, coordinating through a common data
source.
* Each node publishes identical web services one thier own local port address
* The nodes automatically communicate with each other, distributing workload to available servers; Communication is done
  through a unique local TCP port allocated ot each node
* The cluster remains functional in the face of nodes suddenly going down (on purpose or catastrophically)
* Work gets distributed throughout the cluster when nodes leave or join

Notes:
* Exactly one node must host the H2 database server, or a local H2 database server must be running at localhost
* Exactly one node must be assigned to port 2551, and that must be the first node started
 
### Running and Configuring the Cluster Nodes

#### From Command-line

Use the gradle wrapper already present in the project to start each node.

To start a node running at HTTP port 8051 and cluster communications port 2551  (the port defaults), and also
hosting the shared H2 database:

```
./gradlew :cluster-node:runNode -Phost-h2-database-server=true
```

To start a node without hosting the H2 database on HTTP port 8052 and cluster communications poer 2552:

```
./gradlew :cluster-node:runNode -Phttp-port=8052 -Pcluster-port=2552

```

### From Within IDE

The Scala object `bmaso.file_ingest_service_poc.cluster_node.MainCLI` (file location `cluster-node/src/main/scala/cli/MainCLI.scala`)
defines the main cluster node executable entry point. Here is the command-line help docs, which describe what's needed to
start a node:

```
Usage: file-ingestion-cluster-node --http-port <integer> --cluster-port <integer> [--host-h2-database-server] [--clear-h2-database]

Runs an Akka Cluster node publishing file ingestion web services

Options and flags:
    --help
        Display this help text.
    --http-port <integer>
        Port to open for HTTP requests; must be unique for each node on the same host machine
    --cluster-port <integer>
        Port to use for internal cluster communications; must be unique for each node on the same host machine
    --host-h2-database-server
        If present, this node will host an H2 database server for other nodes to use
    --clear-h2-database
        If present AND the --host-h2-database-server flag also present, then the database is completely cleared out before the server starts
```

## Additional Information

The cluster nodes exist to provide a runtime platform for the Actors that make
up the sharded pool of file ingesting actors. Akka clustering automatically manages the lifecycle
of these actors. Alla clustering manages the lifecycle by:
* creating an actor on an available node at beginning of life
* passivating the actor if it does not receive commands within 2 minutes
* re-activating actors that have been previous passivated

The cluster nodes also host a set of *N* **file ingestion stream event processors**. These objects are responsible
for carrying out long-running processes in the file ingestion lifecycle:
* file cleansing
* file uploading

Each file ingestion actor is assigned to one of the *N* stream event processors. When the file ingestion actor state changes,
the associated stream event processor is sent an *event object* describing the change.

Like the fil eingestion actor's themselves, the *N* stream event processors are autoamtically evently distributed through
the cluster. When new nodes are brought online, or existing nodes are brought down, Akka clustering autoamtically re-creates
migrate event stream processors to even spread the workload across the cluster node members.  

### File Order Submission Web Service

File ingest orders are encoded as JSON documents, and submitted to the system through
HTTP POST requests. Here is an example of a file ingest order in JSON form:

```
{
  "cycleId": "123",
  "userId": "serviceAccount",
  "host": "whatismycallit",
  "dataFile": "/opt/project/daily/blah-2020-040-23.dat",
  "notifications": "abc.defg@xxx.com,blah.blah@uxx.com",
  "targetDatabase": "TARGETDB",
  "targetSchema": "WHATEVER",
  "targetTable": "MY_TABLE",
  "delimiter": "~",
  "columns": [
    { "columnName": "COLUMN1", "protegrityDataType": "NA" },
    { "columnName": "COLUMN2", "protegrityDataType": "DeAccountNum" },
    { "columnName": "COLUMN3", "protegrityDataType": "NA" },
    { "columnName": "COLUMN4", "protegrityDataType": "DeSSN" },
    { "columnName": "COLUMN5", "protegrityDataType": "NA" }
  ]
}
```

The HTTP resource path of the POST submission is `/file-ingest'.

The HTTP response to submitted order request is `202 Accepted`. When this
response is received, the system guarantees that the fil eingestion order will be executed at
some time in the future.

***Assumption:*** File ingest orders are uniquely identified by the combination
of the `(cycleId, dataFile)` tuple value. Orders for the same `(cycleId,
dataFile)` are assumed to be idempotent. It is guaranteed that only one order for
any given `(cycleId, dataFile)` pair will be executed. When multiple orders for
the same `(cycleId, dataFile)` pair are submitted it is indeterminate which will
be executed, and which will be silently ignored.

### File Ingest Order Status Inquiry Web Service

It makes sense that we would want to expose the status of file ingest order execution.
Once an ingest order has been accepted, the system supports status inquiry through a
HTTP GET request which references the `(cycleId, dataFile)` pair submitted with the
original request.

The fil eingestion status query HTTP resource path is `/file-ingest/`***`cycleId`***`/`***`dataFile`***. Note that
the `dataFile` value will have to be completely URL-encoded, including any '`/`' characters.
