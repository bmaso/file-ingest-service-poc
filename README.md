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
