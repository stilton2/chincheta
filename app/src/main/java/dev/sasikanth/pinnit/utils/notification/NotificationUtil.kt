package dev.sasikanth.pinnit.utils.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import androidx.navigation.NavDeepLinkBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sasikanth.pinnit.R
import dev.sasikanth.pinnit.activity.MainActivity
import dev.sasikanth.pinnit.background.receivers.RepostNotificationReceiver
import dev.sasikanth.pinnit.data.PinnitNotification
import dev.sasikanth.pinnit.editor.EditorScreenArgs
import dev.sasikanth.pinnit.editor.EditorTransition.SharedAxis
import dev.sasikanth.pinnit.widget.PinnedWidgetProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationUtil @Inject constructor(
  @ApplicationContext private val context: Context,
  private val sourceAppIcons: SourceAppIcons
) {

  companion object {
    private const val CHANNEL_ID = "dev.sasikanth.pinnit.utils.notification:NotificationUtil:pinned_notifications"
  }

  private val notificationManager = NotificationManagerCompat.from(context)
  private val systemNotificationManager = context.getSystemService<NotificationManager>()

  fun showNotification(pinnitNotification: PinnitNotification) {
    createNotificationChannel()
    val notification =
      buildSystemNotification(pinnitNotification)

    with(notificationManager) {
      notify(pinnitNotification.uuid.hashCode(), notification)
    }
    PinnedWidgetProvider.refresh(context)
  }

  fun dismissNotification(pinnitNotification: PinnitNotification) {
    notificationManager.cancel(pinnitNotification.uuid.hashCode())
    PinnedWidgetProvider.refresh(context)
  }

  /**
   * This will check for system notification visibility of pinned notifications. If the
   * notification is not visible for a pinned pinnit notification. It will show the system
   * notification for it.
   *
   * We are doing this because of certain system actions that will remove the notification
   * from system tray.
   *
   * - When app is updated: This will stop the app causing the system notifications
   * to be dismissed.
   * - When app is force stopped: This will cause the system notifications to be dismissed
   *
   * When those scenarios happen, we want the app to re-show the system notifications when
   * app is opened again.
   */
  fun checkNotificationsVisibility(pinnedNotifications: List<PinnitNotification>) {
    if (systemNotificationManager != null) {
      val activeNotifications = systemNotificationManager.activeNotifications
      for (notification in pinnedNotifications) {
        val id = notification.uuid.hashCode()
        val isNotificationVisible = activeNotifications.any { it.id == id }
        if (isNotificationVisible.not()) {
          showNotification(notification)
        }
      }
    }
  }

  /**
   * Check if the app has permission to post notifications on devices running Android version >= 13
   */
  fun hasPermissionToPostNotifications() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  } else {
    true
  }

  private fun buildSystemNotification(notification: PinnitNotification): Notification {
    val content = notification.content.orEmpty()
    val editorScreenArgs = EditorScreenArgs(
      notificationUuid = notification.uuid,
      editorTransition = SharedAxis
    ).toBundle()

    val editorPendingIntent = NavDeepLinkBuilder(context)
      .setGraph(R.navigation.main_nav_graph)
      .setComponentName(MainActivity::class.java)
      .setDestination(R.id.editorScreen)
      .setArguments(editorScreenArgs)
      .createPendingIntent()

    // Fired when the user swipes the notification away: pinned ones come back.
    val repostIntent = Intent(context, RepostNotificationReceiver::class.java).apply {
      action = RepostNotificationReceiver.ACTION_REPOST
      putExtra(RepostNotificationReceiver.EXTRA_NOTIFICATION_UUID, notification.uuid.toString())
    }
    val repostPendingIntent = PendingIntent.getBroadcast(
      context,
      notification.uuid.hashCode(),
      repostIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // No actions on purpose: pinned notes can only be unpinned or deleted from inside the app.
    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_pinnit_notification)
      .setContentTitle(notification.title)
      .setContentText(content)
      .setContentIntent(editorPendingIntent)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setStyle(
        NotificationCompat.BigTextStyle().bigText(
          notification.content
        )
      )
      .setOngoing(true)
      .setDeleteIntent(repostPendingIntent)

    val sourcePackage = notification.sourcePackage
    if (sourcePackage != null) {
      // Look like the original notification: its icons, its app name, and tapping opens that app.
      sourceAppIcons.smallIcon(sourcePackage)?.let { builder.setSmallIcon(IconCompat.createWithBitmap(it)) }
      sourceAppIcons.appIcon(sourcePackage)?.let { builder.setLargeIcon(it) }
      builder.setSubText(notification.sourceAppName ?: sourcePackage)
      sourceAppPendingIntent(sourcePackage, notification)?.let { builder.setContentIntent(it) }
    }

    return builder.build()
  }

  private fun sourceAppPendingIntent(sourcePackage: String, notification: PinnitNotification): PendingIntent? {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(sourcePackage) ?: return null
    return PendingIntent.getActivity(
      context,
      notification.uuid.hashCode(),
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun createNotificationChannel() {
    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val name = context.getString(R.string.channel_name)
      val importance = NotificationManager.IMPORTANCE_DEFAULT
      val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
        setShowBadge(false)
        enableLights(false)
      }
      // Register the channel with the system
      val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.createNotificationChannel(channel)
    }
  }
}
