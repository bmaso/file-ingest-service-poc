# Message-protocol Subproject

This subproject defines the messaging protocol that comprises the communications
between the `web-server` and the `cluster-node` nodes. This document also
describes in abstract terms the file ingestion workflow as a finite state machine.

## FileIngestion

The pool of `cluster-node` instances is represented to file ingestion drivers, such as
the `web-server` instance, as a single `ActorRef[FileIngestion.Order]`. There is
likely to be other forms of driver applications used for dev and integration testing
purposes other than this project's `web-server` -- all such applications will have
the file ingestion processor system represented as an `ActorRef[FileIngestion.Order]`
instance.

Driver applications may choose to receive notifications of file ingestion events
by presenting an `ActorRef[FileIngestion.Notification]` instance when issuing
certain file ingestion commands. The actor will receive asynchronous notification
of file ingestion workflow events.

### File Ingestion State Model

The command to ingest a file is represented by a `FileIngestion.IngestFileOrder`,
a type of `FileIngestion.Order`. Receiving a `FileIngestion.IngestFileOrderAcknowledgement`
notification in response indicates that a file ingestion workflow has begun. The workflow
can be described by the following finite state model.

* State `Enqueued`
  ** A driver application will be asynchronously sent a `FileIngestion.IngestFileOrderAcknowledgement`
     when the file ingestion order has been persistently accepted.
  ** Reaching this state indicates the file ingestion order
     has been enqueued for execution.
  ** If an identical order already exists in the system (i.e., with the exact same
     `(cycleId, dataFile)` tuple value), this flow eventually transitions to the
     `Completed` state with completion `result` value `DuplicateIgnored`.
  ** If a duplicate does not exist the workflow will eventually transition to
     `Cleansing` state automatically as the file ingestion engine bandwidth allows.
* State `Cleansing`
  ** Files may need to be cleansed or somehow "fixed-up" to conform to acceptable
     ingestion standards. This process is knowns as *cleansing*
  ** A driver application will be asynchronously sent a `FileIngestion.CleansingStageEntered`
     notification when a workflow enters this state.
  ** Cleansing is an asynchronous process, possibly multi-step. It is modeled in
     the `cluster-node` internally as a `Future[_]` (The future parameter type
     is specific to different file ingestion implementations.)
  ** If the cleansing process ends in error, then the workflow transitions
     to the `Completed` state with completion result value of type
     `UnexpectedCleansingStageProblems`
  ** Upon successful cleansing the cleansed file is assumed to be ready for ingestion.
     The workflow transitions to state `StagedForIngestion`. A driver application will
     asynchronously receive a `FileIngestion.CleansingStageExited` notification.
* State `StagedForIngestion`
  ** This is a temporary state with no failure cases, representing an indeterminate
     period while the workflow waits for available resources to perform file ingestion.
  ** A driver application will be asynchronously sent a `FileIngestion.StagedForIngestionEntered`
     notification when a workflow enters this state.
  ** Eventually a workflow in this state will transition to the `Ingesting` state
     without exception.
* State `Ingesting`
  ** This is a temporary state which succeeds upon successfully completing ingestion,
     and fails if the ingestion step fails for any reason.
  ** A driver application will be asynchronously sent a `FileIngestion.IngestingEntered`
     notification when a workflow enters this state.
  ** The ingestion process is represented in the `cluster-node` internally as a `Future[_]`.
     (The future parameter type is specific to different file ingestion implementations.)
  ** If the ingestion process ends in error, then the workflow transitions
     to the `Completed` state with completion result value of type `UnexpectedIngestingStageProblem`
* State "Completed"
  ** This is a permanent state which is entered when the workflow completes successfully
     or when any unrecoverable problem is encountered during the execution of the workflow.
  ** A driver application will be asynchronously sent a `FileIngestion.CompletedEntered`
     notification when a workflow enters this state. The notification may include processor
     implementation-specific data about the circumstances of entering this state, especially
     if the state was entered in error.
