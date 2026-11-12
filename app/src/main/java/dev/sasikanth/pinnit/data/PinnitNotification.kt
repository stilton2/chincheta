package dev.sasikanth.pinnit.data

import android.os.Parcelable
import androidx.annotation.Keep
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
  val sourceAppName: String? = null
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
  }
}
