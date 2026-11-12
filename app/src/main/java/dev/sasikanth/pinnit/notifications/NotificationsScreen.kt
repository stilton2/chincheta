package dev.sasikanth.pinnit.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnPreDraw
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.sasikanth.pinnit.trash.TrashActivity
import dev.sasikanth.pinnit.utils.DispatcherProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.Navigator
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.Hold
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.insetter.applySystemWindowInsetsToPadding
import dev.sasikanth.pinnit.R
import dev.sasikanth.pinnit.about.AboutBottomSheet
import dev.sasikanth.pinnit.data.PinnitNotification
import dev.sasikanth.pinnit.databinding.FragmentNotificationsBinding
import dev.sasikanth.pinnit.di.DateTimeFormat
import dev.sasikanth.pinnit.editor.EditorTransition
import dev.sasikanth.pinnit.editor.EditorTransition.SharedAxis
import dev.sasikanth.pinnit.notifications.adapter.NotificationPinItemAnimator
import dev.sasikanth.pinnit.notifications.adapter.NotificationsItemTouchHelper
import dev.sasikanth.pinnit.notifications.adapter.NotificationsListAdapter
import dev.sasikanth.pinnit.options.OptionsBottomSheet
import dev.sasikanth.pinnit.utils.UserClock
import dev.sasikanth.pinnit.utils.UtcClock
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class NotificationsScreen : Fragment(), NotificationsScreenUi {

  @Inject
  lateinit var utcClock: UtcClock

  @Inject
  lateinit var userClock: UserClock

  @Inject
  lateinit var effectHandler: NotificationsScreenEffectHandler.Factory

  @Inject
  lateinit var notificationRepository: NotificationRepository

  @Inject
  lateinit var dispatcherProvider: DispatcherProvider

  @Inject
  @DateTimeFormat(DateTimeFormat.Type.ScheduleDateFormat)
  lateinit var scheduleDateFormatter: DateTimeFormatter

  @Inject
  @DateTimeFormat(DateTimeFormat.Type.ScheduleTimeFormat)
  lateinit var scheduleTimeFormatter: DateTimeFormatter

  private val viewModel: NotificationsScreenViewModel by viewModels()

  private val uiRender = NotificationsScreenUiRender(this)

  private val adapterDataObserver = object : RecyclerView.AdapterDataObserver() {
    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
      binding.notificationsRecyclerView.smoothScrollToPosition(0)
    }
  }

  private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
    // At the moment we are not handling permission results. Since this is a notification app
    // expecting users to provide the notification permission for app to work
  }

  private var _binding: FragmentNotificationsBinding? = null
  private val binding get() = _binding!!

  private lateinit var adapter: NotificationsListAdapter

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
    _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
    return _binding?.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    postponeEnterTransition()

    binding.toolbar.applySystemWindowInsetsToPadding(top = true, left = true, right = true)
    binding.toolbar.inflateMenu(R.menu.notifications_screen)
    binding.toolbar.setOnMenuItemClickListener { item ->
      when (item.itemId) {
        R.id.clearHistory -> showClearHistoryDialog()
        R.id.openTrash -> startActivity(TrashActivity.intent(requireContext()))
      }
      true
    }

    adapter = NotificationsListAdapter(
      utcClock = utcClock,
      userClock = userClock,
      scheduleDateFormatter = scheduleDateFormatter,
      scheduleTimeFormatter = scheduleTimeFormatter,
      onToggleNotificationPinClicked = ::onToggleNotificationPinClicked,
      onNotificationClicked = ::onNotificationClicked,
      onEditNotificationScheduleClicked = ::onEditNotificationScheduleClicked,
      onRemoveNotificationScheduleClicked = ::onRemoveNotificationScheduleClicked
    )
    adapter.registerAdapterDataObserver(adapterDataObserver)
    binding.notificationsRecyclerView.adapter = adapter
    binding.notificationsRecyclerView.itemAnimator = NotificationPinItemAnimator()
    binding.notificationsRecyclerView.applySystemWindowInsetsToPadding(bottom = true, left = true, right = true)
    binding.notificationsRecyclerView.doOnPreDraw { startPostponedEnterTransition() }

    val itemTouchHelperCallback = NotificationsItemTouchHelper(requireContext(), adapter) {
      viewModel.dispatchEvent(NotificationSwiped(it))
    }
    ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(binding.notificationsRecyclerView)

    viewModel.models.observe(viewLifecycleOwner, uiRender::render)

    viewModel.viewEffects.setObserver(
      viewLifecycleOwner,
      ::viewEffectsHandler
    ) { pausedViewEffects -> pausedViewEffects.forEach(::viewEffectsHandler) }

    binding.bottomBar.setNavigationOnClickListener {
      OptionsBottomSheet.show(requireActivity().supportFragmentManager)
    }
    binding.bottomBar.setContentActionOnClickListener {
      openNotificationEditor(
        notification = null,
        navigatorExtras = null,
        editorTransition = SharedAxis
      )
    }
    binding.bottomBar.setActionOnClickListener {
      showAbout()
    }
  }

  private fun viewEffectsHandler(viewEffect: NotificationScreenViewEffect?) {
    when (viewEffect) {
      is UndoNotificationDeleteViewEffect -> {
        Snackbar.make(binding.notificationsRoot, R.string.notification_deleted, Snackbar.LENGTH_LONG)
          .setAnchorView(binding.bottomBar)
          .setAction(R.string.undo) {
            viewModel.dispatchEvent(UndoNotificationDelete(viewEffect.notificationUuid))
          }
          .show()
      }

      RequestNotificationPermissionViewEffect -> requestNotificationPermission()

      null -> throw NullPointerException()
    }
  }

  override fun onDestroyView() {
    adapter.unregisterAdapterDataObserver(adapterDataObserver)
    binding.notificationsRecyclerView.adapter = null
    binding.notificationsRecyclerView.itemAnimator = null
    super.onDestroyView()
  }

  override fun showNotifications(notifications: List<PinnitNotification>) {
    binding.notificationsRecyclerView.isVisible = true
    adapter.submitList(notifications)
  }

  override fun showNotificationsEmptyError() {
    binding.noNotificationsTextView.isVisible = true
    binding.noNotificationsImageView.isVisible = true
  }

  override fun hideNotificationsEmptyError() {
    binding.noNotificationsTextView.isGone = true
    binding.noNotificationsImageView.isGone = true
  }

  override fun hideNotifications() {
    binding.notificationsRecyclerView.isGone = true
    adapter.submitList(null)
  }

  @SuppressLint("InlinedApi")
  private fun requestNotificationPermission() {
    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  private fun onToggleNotificationPinClicked(notification: PinnitNotification) {
    viewModel.dispatchEvent(TogglePinStatusClicked(notification))
  }

  private fun onNotificationClicked(view: View, notification: PinnitNotification) {
    openNotificationEditor(
      notification = notification,
      navigatorExtras = FragmentNavigatorExtras(view to view.transitionName),
      editorTransition = EditorTransition.ContainerTransform(view.transitionName)
    )
  }

  private fun onEditNotificationScheduleClicked(pinnitNotification: PinnitNotification) {
    openNotificationEditor(
      notification = pinnitNotification,
      navigatorExtras = null,
      editorTransition = SharedAxis
    )
  }

  private fun onRemoveNotificationScheduleClicked(pinnitNotification: PinnitNotification) {
    viewModel.dispatchEvent(RemoveNotificationScheduleClicked(pinnitNotification.uuid))
  }

  private fun openNotificationEditor(
    notification: PinnitNotification?,
    navigatorExtras: Navigator.Extras?,
    editorTransition: EditorTransition
  ) {
    val navDirections = NotificationsScreenDirections
      .actionNotificationsScreenToEditorScreen(
        notificationUuid = notification?.uuid,
        editorTransition = editorTransition
      )

    if (navigatorExtras != null) {
      // In order to display the content behind when the
      // container transform is expanding
      exitTransition = Hold()

      findNavController().navigate(navDirections, navigatorExtras)
    } else {
      val backward = MaterialSharedAxis(MaterialSharedAxis.Y, false).apply {
        duration = 300
        addTarget(R.id.notificationsRoot)
        addTarget(R.id.editorRoot)
      }
      reenterTransition = backward

      val forward = MaterialSharedAxis(MaterialSharedAxis.Y, true).apply {
        duration = 300
        addTarget(R.id.notificationsRoot)
        addTarget(R.id.editorRoot)
      }
      exitTransition = forward
      findNavController().navigate(navDirections)
    }
  }

  /**
   * Lists the apps with unpinned history, all ticked. Untick the ones to keep;
   * the rest goes to the trash (with undo). Pinned notifications are never touched.
   */
  private fun showClearHistoryDialog() {
    viewLifecycleOwner.lifecycleScope.launch {
      val apps = withContext(dispatcherProvider.io) { notificationRepository.historyApps() }
      if (apps.isEmpty()) {
        Snackbar.make(binding.notificationsRoot, R.string.clear_history_empty, Snackbar.LENGTH_SHORT)
          .setAnchorView(binding.bottomBar)
          .show()
        return@launch
      }

      val labels = apps.map { "${it.sourceAppName ?: it.sourcePackage} (${it.count})" }.toTypedArray()
      val checked = BooleanArray(apps.size) { true }

      MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.clear_history_title)
        .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
        .setPositiveButton(R.string.clear_history_confirm) { _, _ ->
          val packages = apps.filterIndexed { index, _ -> checked[index] }.map { it.sourcePackage }
          if (packages.isNotEmpty()) moveHistoryToTrash(packages)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }
  }

  private fun moveHistoryToTrash(packages: List<String>) {
    viewLifecycleOwner.lifecycleScope.launch {
      val (count, deletedAt) = withContext(dispatcherProvider.io) {
        notificationRepository.moveHistoryToTrash(packages)
      }
      val message = resources.getQuantityString(R.plurals.clear_history_done, count, count)
      Snackbar.make(binding.notificationsRoot, message, Snackbar.LENGTH_LONG)
        .setAnchorView(binding.bottomBar)
        .setAction(R.string.undo) {
          viewLifecycleOwner.lifecycleScope.launch(dispatcherProvider.io) {
            notificationRepository.undoMoveHistoryToTrash(deletedAt)
          }
        }
        .show()
    }
  }

  private fun showAbout() {
    AboutBottomSheet.show(requireActivity().supportFragmentManager)
  }
}
