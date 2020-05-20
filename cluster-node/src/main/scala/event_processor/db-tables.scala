package bmaso.akka.event_processor

import slick.lifted.Tag
import slick.jdbc.H2Profile.api._
/**
 * Slick table definition of event processor offset table. For any `(processorId, tag)` there is an offset value
 * indicating the last completely processed offset in the event stream of events consumed by the event processor.
 * This table is intended to sit alongside Akka Persistence JDBC Journal tables, within the same database.
 *
 * Each event processor reads its current offset once upon startup. The event processor updates the offset after
 * each event it handles, overwriting the current offset with the offset of the event that was just handled.
 **/
abstract class EventProcessorOffsetTable[ColTypes](tag: Tag, tableName: String) extends Table[ColTypes](tag, tableName) {
  def id: Rep[Long] = column[Long]("ID", O.PrimaryKey, O.AutoInc)

  // TODO: Finish filling this out
  ???
}