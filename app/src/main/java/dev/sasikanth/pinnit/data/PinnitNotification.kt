package dev.sasikanth.pinnit.data

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.zacsweers.redacted.annotations.Redacted
import kotlinx.coroutines.flow.Flow
import kotlinx.parcelize.Parcelize
import java.time.Instant
import java.util.UUID

/** An app with unpinned notifications in the history, for "clear history". */
data class HistoryApp(
  val sourcePackage: String,
  val sourceAppName: String?,
  val count: Int
)

@Keep
@Entity
@Parcelize
data class PinnitNotification(
  @PrimaryKey val uuid: UUID,
  @Redacted val title: String,
  @Redacted val content: String? = null,
  val isPinned: Boolean = true,
  val createdAt: Instant,
  val updatedAt: Instant,
  val deletedAt: Instant? = null,
  @Embedded
  val schedule: Schedule? = null,
  // Filled only for entries captured from other apps' notifications (history).
  val sourcePackage: String? = null,
  val sourceAppName: String? = null,
  // The sender deleted this message (e.g. WhatsApp "This message was deleted"); we kept the text.
  @ColumnInfo(defaultValue = "0")
  val retracted: Boolean = false
) : Parcelable {

  val isFromHistory: Boolean
    get() = sourcePackage != null

  val hasSchedule: Boolean
    get() = schedule != null

  fun equalsTitleAndContent(otherTitle: String?, otherContent: String?) =
    title == otherTitle.orEmpty() && content.orEmpty() == otherContent.orEmpty()

  @Dao
  interface RoomDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(notifications: List<PinnitNotification>)

    @Query("UPDATE PinnitNotification SET isPinned = :isPinned WHERE uuid = :uuid")
    suspend fun updatePinStatus(uuid: UUID, isPinned: Boolean)

    @Query(
      """
        SELECT *
        FROM PinnitNotification
        WHERE deletedAt IS NULL
        ORDER BY isPinned DESC, updatedAt DESC
    """
    )
    fun notifications(): Flow<List<PinnitNotification>>

    @Query(
      """
        SELECT *
        FROM PinnitNotification
        WHERE deletedAt IS NULL AND isPinned = 1
        ORDER BY updatedAt DESC
      """
    )
    suspend fun pinnedNotifications(): List<PinnitNotification>

    @Query("SELECT * FROM PinnitNotification WHERE uuid = :uuid LIMIT 1")
    suspend fun notification(uuid: UUID): PinnitNotification

    @Query("UPDATE PinnitNotification SET scheduleDate = null, scheduleTime = null, scheduleType = null WHERE uuid = :notificationId")
    suspend fun removeSchedule(notificationId: UUID)

    @Query(
      """
        SELECT *
        FROM PinnitNotification
        WHERE sourcePackage = :sourcePackage
        ORDER BY createdAt DESC
        LIMIT 1
      """
    )
    suspend fun latestFromSource(sourcePackage: String): PinnitNotification?

    @Query(
      """
        DELETE FROM PinnitNotification
        WHERE sourcePackage IS NOT NULL AND isPinned = 0 AND createdAt < :before
      """
    )
    suspend fun deleteHistoryOlderThan(before: Instant)

    @Query(
      """
        SELECT *
        FROM PinnitNotification
        WHERE deletedAt IS NOT NULL
        ORDER BY deletedAt DESC
      """
    )
    fun deletedNotifications(): Flow<List<PinnitNotification>>

    @Query("UPDATE PinnitNotification SET deletedAt = NULL WHERE uuid = :uuid")
    suspend fun restore(uuid: UUID)

    @Query("UPDATE PinnitNotification SET deletedAt = NULL WHERE deletedAt = :deletedAt")
    suspend fun restoreDeletedAt(deletedAt: Instant)

    @Query("DELETE FROM PinnitNotification WHERE uuid = :uuid AND deletedAt IS NOT NULL")
    suspend fun deleteForever(uuid: UUID)

    @Query("DELETE FROM PinnitNotification WHERE deletedAt IS NOT NULL")
    suspend fun emptyTrash()

    @Query("DELETE FROM PinnitNotification WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun deleteTrashOlderThan(before: Instant)

    @Query(
      """
        SELECT sourcePackage, MAX(sourceAppName) AS sourceAppName, COUNT(*) AS count
        FROM PinnitNotification
        WHERE sourcePackage IS NOT NULL AND isPinned = 0 AND deletedAt IS NULL
        GROUP BY sourcePackage
        ORDER BY count DESC
      """
    )
    suspend fun historyApps(): List<HistoryApp>

    @Query(
      """
        UPDATE PinnitNotification SET deletedAt = :deletedAt
        WHERE sourcePackage IN (:sourcePackages) AND isPinned = 0 AND deletedAt IS NULL
      """
    )
    suspend fun moveHistoryToTrash(sourcePackages: List<String>, deletedAt: Instant): Int

    @Query(
      """
        SELECT *
        FROM PinnitNotification
        WHERE sourcePackage = :sourcePackage AND title = :title AND retracted = 0 AND createdAt >= :since
        ORDER BY createdAt DESC
        LIMIT 20
      """
    )
    suspend fun recentFromSourceWithTitle(sourcePackage: String, title: String, since: Instant): List<PinnitNotification>

    @Query("SELECT * FROM PinnitNotification ORDER BY createdAt")
    suspend fun allNotifications(): List<PinnitNotification>

    @Query("UPDATE PinnitNotification SET retracted = 1 WHERE uuid = :uuid")
    suspend fun markRetracted(uuid: UUID)
  }
}
