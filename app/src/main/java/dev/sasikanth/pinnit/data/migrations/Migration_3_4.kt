package dev.sasikanth.pinnit.data.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sasikanth.pinnit.utils.inTransaction

/**
 * Marks history entries whose message the sender deleted afterwards.
 */
@Suppress("ClassName")
object Migration_3_4 : Migration(3, 4) {

  override fun migrate(db: SupportSQLiteDatabase) {
    with(db) {
      inTransaction {
        execSQL(""" ALTER TABLE PinnitNotification ADD COLUMN retracted INTEGER NOT NULL DEFAULT 0 """)
      }
    }
  }
}
