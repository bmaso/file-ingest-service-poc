# file-ingest-service-poc

File ingesting web service POC
* implemented with Akka Stream + Alpakka Http, Akka Cluster
* `gradle` build, with copy of `gradle` included so you don't have to install locally
   if you don't already have `gradle`
* Docker image definitions for web server and individual Akka Cluster nodes

## Local Build and Packaging

Clone with `git`:
```
git clone https://github.com/bmaso/file-ingest-service-poc.git
```

Build and test locally:
```
# From the cloned project directory

# MacOS or Unix
./gradlew clean test jar

# Windows
gradlew.bat clean test jar
```

## Running the Servers

### Running and Configuring the Web Server

***TBD***

### Running and Configuring the Cluster (Ingest Worker) Nodes

***TBD***

## Additional Information

The `web-server` and each `cluster-node` application instance all participate in
an Akka Cluster.

The `web-server` listens for HTTP POST requests (as described below), validates
each request and encodes the request as a message. Each request represents an order
to ingest a data file. Messages are sent to a sharded pool of fiule ingesting actors
for handling.

The `cluster-nodes` exist to provide a runtime platform for the Actors that make
up the sharded pool of file ingesting actors. The Akka Clustering runtime distributes
file ingest requests in a fair manor to the pool members. Members employ a CQRS
strategy for ensuring no orders to ingest files are lost, even under node failure
conditions.

File ingest actor pool members also collectively employ an as-of-yet undefined strategy
for ensuring at-most-once handling of file ingesting orders.

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

The HTTP response to submitted order request is will be a `202 Accepted`. When this
response is received, the system guarantees that the order will be executed at
some time in the future.

**Assumption:*** File ingest orders are uniquely identified by the combination
of the `(cycleId, dataFile)` tuple value. Orders for the same `(cycleId,
dataFile)` are assumed to be idempotent. It is guaranteed that only one order for
any given `(cycleId, dataFile)` pair will be executed. When multiple orders for
the same `(cycleId, dataFile)` pair are submitted it is indeterminate which will
be executed, and which will be silently ignored.

### File Ingest Order Status Inquiry Web Service

***TBD***

It makes sense that we would want to expose the status of file ingest order execution.
Once an ingest order has been accepted, the system supports status inquiry through a
HTTP GET request which references the `(cycleId, dataFile)` pair submitted with the
original request.

The form of the inquiry request, the state model of file ingest, how to represent
failure conditions, and a host of other details are all **TBD***.
