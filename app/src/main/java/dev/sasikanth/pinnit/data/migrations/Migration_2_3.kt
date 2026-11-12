package dev.sasikanth.pinnit.data.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sasikanth.pinnit.utils.inTransaction

/**
 * Adds the source app of notifications captured by the listener (notification history).
 */
@Suppress("ClassName")
object Migration_2_3 : Migration(2, 3) {

  override fun migrate(db: SupportSQLiteDatabase) {
    with(db) {
      inTransaction {
        execSQL(""" ALTER TABLE PinnitNotification ADD COLUMN sourcePackage TEXT DEFAULT NULL """)
        execSQL(""" ALTER TABLE PinnitNotification ADD COLUMN sourceAppName TEXT DEFAULT NULL """)
      }
    }
  }
}
