package dev.sasikanth.pinnit.background.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.sasikanth.pinnit.notifications.NotificationRepository
import dev.sasikanth.pinnit.utils.DispatcherProvider
import dev.sasikanth.pinnit.utils.notification.NotificationUtil
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Since Android 14 users can swipe away ongoing notifications. A pinned notification
 * must stay in the tray, so when the user dismisses it (swipe or "clear all") we post
 * it again. Unpin/Delete actions cancel it from the app, which doesn't trigger this.
 */
@AndroidEntryPoint
class RepostNotificationReceiver : BroadcastReceiver() {

  companion object {
    private const val TAG = "RepostNotification"
    private const val REPOST_DELAY_MS = 500L

    const val ACTION_REPOST = "dev.sasikanth.pinnit.system.action.RepostNotification"
    const val EXTRA_NOTIFICATION_UUID = "dev.sasikanth.system.RepostNotificationReceiver:notificationUuid"
  }

  @Inject
  lateinit var repository: NotificationRepository

  @Inject
  lateinit var notificationUtil: NotificationUtil

  @Inject
  lateinit var dispatcherProvider: DispatcherProvider

  private val job = SupervisorJob()
  private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.d(TAG, "Failed to repost the notification.", throwable)
  }
  private val mainScope by lazy {
    CoroutineScope(job + dispatcherProvider.main + coroutineExceptionHandler)
  }

  override fun onReceive(context: Context?, intent: Intent?) {
    val notificationUuid = intent?.getStringExtra(EXTRA_NOTIFICATION_UUID)

    if (context != null && intent != null && intent.action == ACTION_REPOST && notificationUuid != null) {
      val asyncResult = goAsync()

      mainScope.launch {
        try {
          // Let the dismiss animation finish before showing it again.
          delay(REPOST_DELAY_MS)
          val notification = repository.notification(UUID.fromString(notificationUuid))
          @Suppress("SENSELESS_COMPARISON") // Room returns null if the row no longer exists
          if (notification != null && notification.isPinned && notification.deletedAt == null) {
            notificationUtil.showNotification(notification)
          }
        } finally {
          asyncResult.finish()
        }
      }
    }
  }
}
