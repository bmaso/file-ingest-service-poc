# Cluster Node

## General Description of the Internals
The file ingestion service's system design employs a resiliency-first strategy. The features of this design include:
* CQRS-ES design for persistence and efficient recovery when nodes fail
  * See [This video](https://akka.io/blog/news/2020/01/07/akka-event-sourcing-video) for a quick
    introduction to CQRS with Akka
  * Individual File Ingestion processes are represented by individual actors in which walk
    through the file ingestion workflow (finite state model). These actors comprise the
    *write-model* of the design.
  * Akka Cluster Sharding takes care of the even distribution of actors on available cluster
    nodes; if a node goes down, Akka Cluster Sharding automatically recreates individual actors and
    their respective state on available nodes as well
  * File ingestion actors don't perform any of the side-effects from workflow transitions, such
    as file cleansing or file upload. These actors are only responsible for walking through the
    workflow, generating state change events in reaction to commands.
    * The system employs a separate pool of *stream processors*, described below,
      to carry out side-effects (cleanse files, physically upload files, etc.)
    * Stream processors comprise the *read-model* portion of the design.
  * The *read-model* provides the implementation of *stream processor* actors which
    react to change events in the workflow entity states: executing cleansing processes,
    executing file uploads, etc.
* Akka Persistence to keep file ingestion workflow entities and entity stream processors
  eventually consistent
  * File ingestion entities receive *commands*
  * File ingestion entities react to *commands* by firing *events*
    * The current state if a file ingestion workflow can be defined as a fold over the
      ordered collection of its previous events.
    * During passivation/activation cycles and also during catastrophic recovery the
       system re-creates the state of individual file ingestion workflows by simply
       "playing back" each file ingestion workflow's events, which automatically re-creates
       the current state of the workflow. 
  * File ingestion stream processors (the *read-model*) react to file ingestion *events* as well
    * For example: the reaction to a file ingestion workflow event signalling the workflow has
      transitioned to the *Cleansing* state is to begin the asynchronous cleansing process for the
    * Continuing this example: eventually, when the cleansing function completes, the stream
      processor sends a `CleasingResultAcknowledgementOrder` command to the file ingestion entity
    * The workflow entity processes this command by entering the *Uploading* state and
      firing off associated events (ehich are then reacted to, and so on)
  * During recovery/restart scenarios, the stream of events for each file ingestion workflow
    is replayed to re-create both the current entity state within the *write-model*, but
    also is replayed to start/resume side-effects in the *read-model*
    * Note that the queued of unprocessed events for the *write-model* entities and the
      *read-model* stream processors are independent.
 
 ## Resiliency Features
 * HA achieved by keeping multiple nodes running. So long as at least one node is running,
   the system is available and eventually consistent.
 * Dynamic number of nodes hosting workflows and side-effecting processes; want more nodes?
   Just start them: Akka Cluster Sharding automatically (re-)distributes workflow actors evenly
   to available cluster nodes.
  
 ## Integration with the Web Server Node(s)
 
 One or more web-server nodes run as part of the same Akka Cluster. Akka Cluster Sharding
 abstracts away the location of individual workflow actors within the cluster. The web-server
 uses the logical identity of the workflow, the `(cycleId, dataFile)` tuple, to address the
 flow.
 
 The web-server node translates HTTP requests into appropriate workflow commands, and translates
 command responses to HTTP responses. The web-server communicates solely with workflow entities. The
 set of workflow commands the web-server can generate is limited to just `FileIngestOrder`
 and `RetrieveFileIngestStatusOrder`.
 