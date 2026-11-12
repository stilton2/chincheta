package dev.sasikanth.pinnit.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import dev.sasikanth.pinnit.R
import dev.sasikanth.pinnit.activity.MainActivity

/**
 * Home screen widget listing the pinned notifications. Tapping it opens the app.
 */
class PinnedWidgetProvider : AppWidgetProvider() {

  companion object {
    /** Re-reads the pinned list in every placed widget. Cheap when there is no widget. */
    fun refresh(context: Context) {
      val manager = AppWidgetManager.getInstance(context) ?: return
      val ids = manager.getAppWidgetIds(ComponentName(context, PinnedWidgetProvider::class.java))
      if (ids.isNotEmpty()) {
        @Suppress("DEPRECATION")
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetList)
      }
    }
  }

  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    val openApp = PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    for (appWidgetId in appWidgetIds) {
      val serviceIntent = Intent(context, PinnedWidgetService::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
      }

      val views = RemoteViews(context.packageName, R.layout.widget_pinned).apply {
        @Suppress("DEPRECATION")
        setRemoteAdapter(R.id.widgetList, serviceIntent)
        setEmptyView(R.id.widgetList, R.id.widgetEmpty)
        setOnClickPendingIntent(R.id.widgetTitle, openApp)
        setOnClickPendingIntent(R.id.widgetEmpty, openApp)
        setPendingIntentTemplate(R.id.widgetList, openApp)
      }
      appWidgetManager.updateAppWidget(appWidgetId, views)
    }
    @Suppress("DEPRECATION")
    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widgetList)
  }
}
