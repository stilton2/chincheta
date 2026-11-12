package dev.sasikanth.pinnit.data.backup

import dev.sasikanth.pinnit.data.PinnitNotification
import dev.sasikanth.pinnit.data.Schedule
import dev.sasikanth.pinnit.data.ScheduleType
import dev.sasikanth.pinnit.data.preferences.HistorySettings
import dev.sasikanth.pinnit.notifications.NotificationRepository
import dev.sasikanth.pinnit.scheduler.PinnitNotificationScheduler
import dev.sasikanth.pinnit.utils.notification.NotificationUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports everything (history, pinned notes, trash and history settings) to a JSON file and
 * imports it back. Import merges: entries with the same id are replaced, the rest are kept.
 */
@Singleton
class HistoryBackup @Inject constructor(
  private val repository: NotificationRepository,
  private val historySettings: HistorySettings,
  private val notificationUtil: NotificationUtil,
  private val scheduler: PinnitNotificationScheduler
) {

  companion object {
    private const val FORMAT = "pinnit-backup"
    private const val VERSION = 1
  }

  /** Returns how many notifications were written. Call off the main thread. */
  suspend fun export(output: OutputStream): Int {
    val notifications = repository.allNotifications()
    val json = JSONObject().apply {
      put("format", FORMAT)
      put("version", VERSION)
      put("exportedAt", Instant.now().toString())
      put("settings", JSONObject().apply {
        put("ignoredPackages", JSONArray(historySettings.ignoredPackages.toList()))
        put("autoPinRules", JSONArray(historySettings.autoPinRules.toList()))
        put("historyRetentionDays", historySettings.historyRetentionDays)
      })
      put("notifications", JSONArray().apply {
        notifications.forEach { put(it.toJson()) }
      })
    }
    output.bufferedWriter().use { it.write(json.toString(2)) }
    return notifications.size
  }

  /** Returns how many notifications were restored. Throws if the file isn't a Pinnit backup. */
  suspend fun import(input: InputStream): Int {
    val json = JSONObject(input.bufferedReader().use { it.readText() })
    require(json.optString("format") == FORMAT) { "Not a Pinnit backup" }

    json.optJSONObject("settings")?.let { settings ->
      historySettings.ignoredPackages = historySettings.ignoredPackages + settings.optJSONArray("ignoredPackages").strings()
      historySettings.autoPinRules = historySettings.autoPinRules + settings.optJSONArray("autoPinRules").strings()
      if (settings.has("historyRetentionDays")) {
        historySettings.historyRetentionDays = settings.getInt("historyRetentionDays")
      }
    }

    val array = json.getJSONArray("notifications")
    val notifications = (0 until array.length()).map { array.getJSONObject(it).toNotification() }
    repository.save(notifications)

    val active = notifications.filter { it.deletedAt == null }
    notificationUtil.checkNotificationsVisibility(active.filter { it.isPinned })
    active.forEach { scheduler.scheduleNotification(it) }
    return notifications.size
  }

  private fun PinnitNotification.toJson() = JSONObject().apply {
    put("uuid", uuid.toString())
    put("title", title)
    putOpt("content", content)
    put("isPinned", isPinned)
    put("createdAt", createdAt.toString())
    put("updatedAt", updatedAt.toString())
    putOpt("deletedAt", deletedAt?.toString())
    putOpt("scheduleDate", schedule?.scheduleDate?.toString())
    putOpt("scheduleTime", schedule?.scheduleTime?.toString())
    putOpt("scheduleType", schedule?.scheduleType?.name)
    putOpt("sourcePackage", sourcePackage)
    putOpt("sourceAppName", sourceAppName)
    put("retracted", retracted)
  }

  private fun JSONObject.toNotification(): PinnitNotification {
    val scheduleDate = optStringOrNull("scheduleDate")?.let(LocalDate::parse)
    val scheduleTime = optStringOrNull("scheduleTime")?.let(LocalTime::parse)
    val scheduleType = optStringOrNull("scheduleType")?.let(ScheduleType::valueOf)
    return PinnitNotification(
      uuid = UUID.fromString(getString("uuid")),
      title = getString("title"),
      content = optStringOrNull("content"),
      isPinned = optBoolean("isPinned", false),
      createdAt = Instant.parse(getString("createdAt")),
      updatedAt = Instant.parse(getString("updatedAt")),
      deletedAt = optStringOrNull("deletedAt")?.let(Instant::parse),
      schedule = if (scheduleDate != null || scheduleTime != null) Schedule(scheduleDate, scheduleTime, scheduleType) else null,
      sourcePackage = optStringOrNull("sourcePackage"),
      sourceAppName = optStringOrNull("sourceAppName"),
      retracted = optBoolean("retracted", false)
    )
  }

  private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null

  private fun JSONArray?.strings(): Set<String> =
    if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()
}
