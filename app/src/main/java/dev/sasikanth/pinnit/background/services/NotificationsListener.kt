package dev.sasikanth.pinnit.background.services

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.sasikanth.pinnit.notifications.NotificationRepository
import dev.sasikanth.pinnit.utils.DispatcherProvider
import dev.sasikanth.pinnit.utils.notification.SourceAppIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Notification history: saves every notification posted by other apps as an
 * unpinned entry, so it can be read later and pinned from the app.
 */
@AndroidEntryPoint
class NotificationsListener : NotificationListenerService() {

  companion object {
    private val HISTORY_RETENTION: Duration = Duration.ofDays(30)
    private val PURGE_INTERVAL: Duration = Duration.ofHours(12)

    // Same app posting the same text again within this window is an update, not a new notification.
    private val DUPLICATE_WINDOW: Duration = Duration.ofMinutes(10)

    private val IGNORED_CATEGORIES = setOf(
      Notification.CATEGORY_PROGRESS,
      Notification.CATEGORY_TRANSPORT,
      Notification.CATEGORY_SERVICE
    )
  }

  @Inject
  lateinit var repository: NotificationRepository

  @Inject
  lateinit var dispatcherProvider: DispatcherProvider

  @Inject
  lateinit var sourceAppIcons: SourceAppIcons

  private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcherProvider.io) }

  // Serialises saves so the duplicate check sees the previous insert.
  private val saveMutex = Mutex()

  private var lastPurge: Instant = Instant.EPOCH

  override fun onListenerConnected() {
    super.onListenerConnected()
    purgeOldHistoryIfNeeded()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn == null || !shouldSave(sbn)) return

    val (title, content) = extractText(sbn.notification) ?: return
    val sourcePackage = sbn.packageName
    val appName = appLabel(sourcePackage)
    val postedAt = Instant.ofEpochMilli(sbn.postTime)

    val smallIcon = sbn.notification.smallIcon

    scope.launch {
      sourceAppIcons.saveSmallIcon(sourcePackage, smallIcon)
      saveMutex.withLock {
        val latest = repository.latestFromSource(sourcePackage)
        val isDuplicate = latest != null &&
          latest.equalsTitleAndContent(title.ifBlank { appName }, content) &&
          Duration.between(latest.createdAt, postedAt).abs() < DUPLICATE_WINDOW
        if (isDuplicate) return@withLock

        repository.saveFromHistory(
          title = title.ifBlank { appName },
          content = content,
          sourcePackage = sourcePackage,
          sourceAppName = appName,
          postedAt = postedAt
        )
      }
      purgeOldHistoryIfNeeded()
    }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun shouldSave(sbn: StatusBarNotification): Boolean {
    val notification = sbn.notification ?: return false
    if (sbn.packageName == packageName) return false
    if (sbn.isOngoing) return false
    if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
    if (notification.category in IGNORED_CATEGORIES) return false
    return true
  }

  /**
   * Returns title and content, or null when the notification has no readable text.
   * For chats (MessagingStyle) uses the last message, prefixed with the sender in group chats.
   */
  private fun extractText(notification: Notification): Pair<String, String?>? {
    val extras = notification.extras

    val messagingStyle = NotificationCompat.MessagingStyle
      .extractMessagingStyleFromNotification(notification)
    val lastMessage = messagingStyle?.messages?.lastOrNull()

    val title: String
    val content: String?
    if (lastMessage != null && !lastMessage.text.isNullOrBlank()) {
      val conversationTitle = messagingStyle.conversationTitle?.toString()
      val sender = lastMessage.person?.name?.toString()
      if (messagingStyle.isGroupConversation && !conversationTitle.isNullOrBlank()) {
        title = conversationTitle
        content = if (sender.isNullOrBlank()) lastMessage.text.toString() else "$sender: ${lastMessage.text}"
      } else {
        title = sender ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        content = lastMessage.text.toString()
      }
    } else {
      title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
      content = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
    }

    if (title.isBlank() && content.isNullOrBlank()) return null
    return title.trim() to content?.trim()?.ifBlank { null }
  }

  private fun appLabel(sourcePackage: String): String {
    return try {
      val appInfo = packageManager.getApplicationInfo(sourcePackage, 0)
      packageManager.getApplicationLabel(appInfo).toString()
    } catch (e: PackageManager.NameNotFoundException) {
      sourcePackage
    }
  }

  private fun purgeOldHistoryIfNeeded() {
    val now = Instant.now()
    if (Duration.between(lastPurge, now) < PURGE_INTERVAL) return
    lastPurge = now
    scope.launch {
      repository.deleteHistoryOlderThan(now.minus(HISTORY_RETENTION))
    }
  }
}
