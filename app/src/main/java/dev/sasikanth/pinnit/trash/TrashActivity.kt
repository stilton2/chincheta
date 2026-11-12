package dev.sasikanth.pinnit.trash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import dev.sasikanth.pinnit.R
import dev.sasikanth.pinnit.data.PinnitNotification
import dev.sasikanth.pinnit.databinding.ActivityTrashBinding
import dev.sasikanth.pinnit.databinding.TrashListItemBinding
import dev.sasikanth.pinnit.notifications.NotificationRepository
import dev.sasikanth.pinnit.utils.AppLock
import dev.sasikanth.pinnit.utils.DispatcherProvider
import dev.sasikanth.pinnit.utils.notification.NotificationUtil
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Deleted notes and history entries. They can be restored or deleted forever;
 * the listener deletes them for good after 30 days.
 */
@AndroidEntryPoint
class TrashActivity : AppCompatActivity() {

  companion object {
    fun intent(context: Context) = Intent(context, TrashActivity::class.java)
  }

  @Inject
  lateinit var repository: NotificationRepository

  @Inject
  lateinit var notificationUtil: NotificationUtil

  @Inject
  lateinit var dispatcherProvider: DispatcherProvider

  @Inject
  lateinit var appLock: AppLock

  private lateinit var binding: ActivityTrashBinding

  private val adapter = TrashAdapter(::onItemClicked)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityTrashBinding.inflate(layoutInflater)
    setContentView(binding.root)

    binding.toolbar.setNavigationOnClickListener { finish() }
    binding.toolbar.inflateMenu(R.menu.trash)
    binding.toolbar.setOnMenuItemClickListener { item ->
      if (item.itemId == R.id.emptyTrash) confirmEmptyTrash()
      true
    }

    binding.trashRecyclerView.adapter = adapter

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        repository.deletedNotifications().collect { items ->
          adapter.submitList(items)
          binding.trashEmptyTextView.isVisible = items.isEmpty()
          binding.toolbar.menu.findItem(R.id.emptyTrash)?.isVisible = items.isNotEmpty()
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    appLock.guard(this, binding.root)
  }

  private fun onItemClicked(notification: PinnitNotification) {
    MaterialAlertDialogBuilder(this)
      .setTitle(notification.title)
      .setMessage(notification.content)
      .setPositiveButton(R.string.trash_restore) { _, _ -> restore(notification) }
      .setNegativeButton(R.string.trash_delete_forever) { _, _ -> deleteForever(notification) }
      .setNeutralButton(android.R.string.cancel, null)
      .show()
  }

  private fun restore(notification: PinnitNotification) {
    lifecycleScope.launch {
      withContext(dispatcherProvider.io) { repository.restore(notification.uuid) }
      if (notification.isPinned) {
        notificationUtil.showNotification(notification.copy(deletedAt = null))
      }
    }
  }

  private fun deleteForever(notification: PinnitNotification) {
    lifecycleScope.launch {
      withContext(dispatcherProvider.io) { repository.deleteForever(notification.uuid) }
    }
  }

  private fun confirmEmptyTrash() {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.trash_empty_action)
      .setMessage(R.string.trash_empty_confirm)
      .setPositiveButton(R.string.trash_empty_action) { _, _ ->
        lifecycleScope.launch {
          withContext(dispatcherProvider.io) { repository.emptyTrash() }
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }
}

private class TrashAdapter(
  private val onItemClicked: (PinnitNotification) -> Unit
) : ListAdapter<PinnitNotification, TrashAdapter.ViewHolder>(Diff) {

  object Diff : DiffUtil.ItemCallback<PinnitNotification>() {
    override fun areItemsTheSame(oldItem: PinnitNotification, newItem: PinnitNotification) = oldItem.uuid == newItem.uuid
    override fun areContentsTheSame(oldItem: PinnitNotification, newItem: PinnitNotification) = oldItem == newItem
  }

  class ViewHolder(val binding: TrashListItemBinding) : RecyclerView.ViewHolder(binding.root)

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val binding = TrashListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return ViewHolder(binding).apply {
      binding.root.setOnClickListener {
        @Suppress("DEPRECATION") val position = adapterPosition
        if (position != RecyclerView.NO_POSITION) onItemClicked(getItem(position))
      }
    }
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val item = getItem(position)
    val context = holder.binding.root.context
    val source = item.sourceAppName ?: context.getString(R.string.app_name)
    val deletedAgo = item.deletedAt?.let {
      DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    }
    holder.binding.trashItemMeta.text = if (deletedAgo != null) {
      context.getString(R.string.trash_item_meta, source, deletedAgo)
    } else {
      source
    }
    holder.binding.trashItemTitle.text = item.title
    holder.binding.trashItemContent.text = item.content
    holder.binding.trashItemContent.isVisible = !item.content.isNullOrBlank()
  }
}
