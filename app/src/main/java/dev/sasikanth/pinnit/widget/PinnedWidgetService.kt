package dev.sasikanth.pinnit.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dagger.hilt.android.AndroidEntryPoint
import dev.sasikanth.pinnit.R
import dev.sasikanth.pinnit.data.PinnitNotification
import dev.sasikanth.pinnit.notifications.NotificationRepository
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class PinnedWidgetService : RemoteViewsService() {

  @Inject
  lateinit var repository: NotificationRepository

  override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = PinnedWidgetFactory(applicationContext, repository)
}

private class PinnedWidgetFactory(
  private val context: Context,
  private val repository: NotificationRepository
) : RemoteViewsService.RemoteViewsFactory {

  private var items: List<PinnitNotification> = emptyList()

  override fun onCreate() = Unit

  // Runs on a binder thread, so blocking on the database is allowed here.
  override fun onDataSetChanged() {
    items = runBlocking { repository.pinnedNotifications() }
  }

  override fun onDestroy() {
    items = emptyList()
  }

  override fun getCount() = items.size

  override fun getViewAt(position: Int): RemoteViews {
    val item = items.getOrNull(position)
    val views = RemoteViews(context.packageName, R.layout.widget_pinned_item)
    if (item == null) return views

    views.setTextViewText(R.id.widgetItemApp, item.sourceAppName ?: context.getString(R.string.app_name))
    views.setTextViewText(R.id.widgetItemTitle, item.title)
    views.setTextViewText(R.id.widgetItemContent, item.content.orEmpty())
    views.setViewVisibility(
      R.id.widgetItemContent,
      if (item.content.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
    )
    views.setOnClickFillInIntent(R.id.widgetItem, Intent())
    return views
  }

  override fun getLoadingView(): RemoteViews? = null

  override fun getViewTypeCount() = 1

  override fun getItemId(position: Int) = items.getOrNull(position)?.uuid?.hashCode()?.toLong() ?: position.toLong()

  override fun hasStableIds() = true
}
