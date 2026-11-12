package dev.sasikanth.pinnit.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.content.getSystemService
import dev.sasikanth.pinnit.data.preferences.HistorySettings
import dev.sasikanth.pinnit.notifications.adapter.DayHeaderDecoration
import android.net.Uri
import android.widget.FrameLayout
import com.google.android.material.textfield.TextInputEditText
import dev.sasikanth.pinnit.data.backup.HistoryBackup
import dev.sasikanth.pinnit.utils.AppLock
import dev.sasikanth.pinnit.utils.normalizedForSearch
import java.time.LocalDate
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

  companion object {
    private const val NOTES_FILTER = ""
  }

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
  lateinit var historySettings: HistorySettings

  @Inject
  lateinit var historyBackup: HistoryBackup

  @Inject
  lateinit var appLock: AppLock

  private val exportBackupLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(::exportBackup) }

  private val importBackupLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importBackup) }

  // Everything the screen model gives us; the adapter gets this list after search and app filter.
  private var allNotifications: List<PinnitNotification> = emptyList()
  private var searchQuery: String = ""

  // null = all apps, NOTES_FILTER = only my own notes, otherwise a source package.
  private var appFilter: String? = null
  private var appFilterName: String? = null

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
        R.id.filterByApp -> showFilterByAppDialog()
        R.id.clearHistory -> showClearHistoryDialog()
        R.id.openTrash -> startActivity(TrashActivity.intent(requireContext()))
        R.id.ignoredApps -> showIgnoredAppsDialog()
        R.id.retention -> showRetentionDialog()
        R.id.autoPin -> showAutoPinDialog()
        R.id.backup -> showBackupDialog()
        R.id.appLock -> toggleAppLock(item)
      }
      true
    }
    binding.toolbar.menu.findItem(R.id.appLock).isChecked = historySettings.appLockEnabled
    setUpSearch(binding.toolbar.menu.findItem(R.id.search))

    adapter = NotificationsListAdapter(
      utcClock = utcClock,
      userClock = userClock,
      scheduleDateFormatter = scheduleDateFormatter,
      scheduleTimeFormatter = scheduleTimeFormatter,
      onToggleNotificationPinClicked = ::onToggleNotificationPinClicked,
      onNotificationClicked = ::onNotificationClicked,
      onEditNotificationScheduleClicked = ::onEditNotificationScheduleClicked,
      onRemoveNotificationScheduleClicked = ::onRemoveNotificationScheduleClicked,
      onNotificationLongClicked = ::onNotificationLongClicked
    )
    adapter.registerAdapterDataObserver(adapterDataObserver)
    binding.notificationsRecyclerView.adapter = adapter
    binding.notificationsRecyclerView.itemAnimator = NotificationPinItemAnimator()
    binding.notificationsRecyclerView.addItemDecoration(
      DayHeaderDecoration(requireContext()) { position -> adapter.currentList.getOrNull(position) }
    )
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
    allNotifications = notifications
    applyFilters()
  }

  private fun applyFilters() {
    val query = searchQuery.normalizedForSearch()
    val filtered = allNotifications.filter { notification ->
      val matchesApp = when (val filter = appFilter) {
        null -> true
        NOTES_FILTER -> notification.sourcePackage == null
        else -> notification.sourcePackage == filter
      }
      val matchesQuery = query.isEmpty() || listOfNotNull(notification.title, notification.content, notification.sourceAppName)
        .any { it.normalizedForSearch().contains(query) }
      matchesApp && matchesQuery
    }

    adapter.submitList(filtered) {
      _binding?.notificationsRecyclerView?.invalidateItemDecorations()
    }

    val noResults = filtered.isEmpty() && allNotifications.isNotEmpty()
    if (allNotifications.isNotEmpty()) {
      binding.noNotificationsTextView.setText(if (noResults) R.string.no_results else R.string.no_notifications)
      binding.noNotificationsTextView.isVisible = noResults
      binding.noNotificationsImageView.isVisible = noResults
    }
    binding.toolbarTitleTextView.text = appFilterName ?: getString(R.string.toolbar_title_notifications)
  }

  private fun setUpSearch(searchItem: MenuItem) {
    val searchView = searchItem.actionView as SearchView
    searchView.queryHint = getString(R.string.menu_search)
    searchView.maxWidth = Int.MAX_VALUE
    searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
      override fun onQueryTextSubmit(query: String?): Boolean {
        searchView.clearFocus()
        return true
      }

      override fun onQueryTextChange(newText: String?): Boolean {
        searchQuery = newText.orEmpty()
        applyFilters()
        return true
      }
    })
    searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
      override fun onMenuItemActionExpand(item: MenuItem): Boolean {
        binding.toolbarTitleTextView.isGone = true
        return true
      }

      override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
        binding.toolbarTitleTextView.isVisible = true
        searchQuery = ""
        applyFilters()
        return true
      }
    })
  }

  private fun appNamesInList(): List<Pair<String, String>> {
    return allNotifications
      .filter { it.sourcePackage != null }
      .groupBy { it.sourcePackage!! }
      .entries
      .sortedByDescending { it.value.size }
      .map { (sourcePackage, items) -> sourcePackage to (items.first().sourceAppName ?: sourcePackage) }
  }

  private fun showFilterByAppDialog() {
    val options = mutableListOf<Pair<String?, String>>(null to getString(R.string.filter_all_apps))
    if (allNotifications.any { it.sourcePackage == null }) {
      options += NOTES_FILTER to getString(R.string.filter_my_notes)
    }
    options += appNamesInList()

    val labels = options.map { it.second }.toTypedArray()
    val selected = options.indexOfFirst { it.first == appFilter }.coerceAtLeast(0)
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.menu_filter_app)
      .setSingleChoiceItems(labels, selected) { dialog, which ->
        setAppFilter(options[which].first, options[which].second)
        dialog.dismiss()
      }
      .show()
  }

  private fun setAppFilter(filter: String?, name: String?) {
    appFilter = filter
    appFilterName = if (filter == null) null else name
    applyFilters()
  }

  private fun onNotificationLongClicked(notification: PinnitNotification) {
    val text = listOfNotNull(notification.title, notification.content).joinToString("\n")
    val sourcePackage = notification.sourcePackage
    val appName = notification.sourceAppName ?: sourcePackage

    val actions = mutableListOf<Pair<String, () -> Unit>>(
      getString(R.string.action_copy) to { copyToClipboard(text) },
      getString(R.string.action_share) to { share(text) }
    )
    if (sourcePackage != null && appName != null) {
      actions += getString(R.string.action_only_app, appName) to { setAppFilter(sourcePackage, appName) }
      actions += getString(R.string.action_ignore_app, appName) to { ignoreApp(sourcePackage, appName) }
    }

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(notification.title)
      .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
      .show()
  }

  private fun copyToClipboard(text: String) {
    val clipboard = requireContext().getSystemService<ClipboardManager>() ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    showSnackbar(getString(R.string.copied))
  }

  private fun share(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, null))
  }

  private fun ignoreApp(sourcePackage: String, appName: String) {
    historySettings.ignore(sourcePackage)
    Snackbar.make(binding.notificationsRoot, getString(R.string.app_ignored, appName), Snackbar.LENGTH_LONG)
      .setAnchorView(binding.bottomBar)
      .setAction(R.string.undo) {
        historySettings.ignoredPackages = historySettings.ignoredPackages - sourcePackage
      }
      .show()
  }

  /** Apps seen in the history plus the ones already ignored; ticked = not saved. */
  private fun showIgnoredAppsDialog() {
    val ignored = historySettings.ignoredPackages
    val apps = appNamesInList().toMutableList()
    val packageManager = requireContext().packageManager
    ignored.filter { ignoredPackage -> apps.none { it.first == ignoredPackage } }.forEach { ignoredPackage ->
      val label = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(ignoredPackage, 0)).toString()
      } catch (e: PackageManager.NameNotFoundException) {
        ignoredPackage
      }
      apps += ignoredPackage to label
    }
    if (apps.isEmpty()) {
      showSnackbar(getString(R.string.ignored_apps_empty))
      return
    }
    apps.sortWith(compareByDescending<Pair<String, String>> { it.first in ignored }.thenBy { it.second.lowercase() })

    val checked = BooleanArray(apps.size) { apps[it].first in ignored }
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.ignored_apps_title)
      .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
      .setPositiveButton(android.R.string.ok) { _, _ ->
        historySettings.ignoredPackages = apps.filterIndexed { index, _ -> checked[index] }.map { it.first }.toSet()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showRetentionDialog() {
    val choices = HistorySettings.RETENTION_CHOICES
    val labels = choices.map { days ->
      when (days) {
        7 -> getString(R.string.retention_7)
        30 -> getString(R.string.retention_30)
        90 -> getString(R.string.retention_90)
        365 -> getString(R.string.retention_365)
        else -> getString(R.string.retention_never)
      }
    }.toTypedArray()
    val selected = choices.indexOf(historySettings.historyRetentionDays).coerceAtLeast(0)

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.retention_title)
      .setSingleChoiceItems(labels, selected) { dialog, which ->
        historySettings.historyRetentionDays = choices[which]
        dialog.dismiss()
        showSnackbar(getString(R.string.retention_saved))
      }
      .show()
  }

  /** Words or names that pin a new notification automatically. Tap one to remove it. */
  private fun showAutoPinDialog() {
    val rules = historySettings.autoPinRules.sortedBy { it.lowercase() }
    val builder = MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.auto_pin_title)
      .setPositiveButton(R.string.auto_pin_add) { _, _ -> showAddAutoPinRuleDialog() }
      .setNegativeButton(R.string.close, null)
    if (rules.isEmpty()) {
      builder.setMessage(R.string.auto_pin_explanation)
    } else {
      builder.setItems(rules.toTypedArray()) { _, which -> confirmRemoveAutoPinRule(rules[which]) }
    }
    builder.show()
  }

  private fun showAddAutoPinRuleDialog() {
    val context = requireContext()
    val input = TextInputEditText(context).apply {
      hint = getString(R.string.auto_pin_hint)
      isSingleLine = true
    }
    val padding = (20 * resources.displayMetrics.density).toInt()
    val container = FrameLayout(context).apply {
      setPadding(padding, padding / 2, padding, 0)
      addView(input)
    }
    MaterialAlertDialogBuilder(context)
      .setTitle(R.string.auto_pin_add)
      .setMessage(R.string.auto_pin_explanation)
      .setView(container)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val rule = input.text?.toString()?.trim().orEmpty()
        if (rule.isNotEmpty()) {
          historySettings.autoPinRules = historySettings.autoPinRules + rule
          showSnackbar(getString(R.string.auto_pin_added, rule))
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun confirmRemoveAutoPinRule(rule: String) {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(rule)
      .setMessage(R.string.auto_pin_remove_confirm)
      .setPositiveButton(R.string.delete) { _, _ ->
        historySettings.autoPinRules = historySettings.autoPinRules - rule
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun showBackupDialog() {
    val options = arrayOf(getString(R.string.backup_export), getString(R.string.backup_import))
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.menu_backup)
      .setItems(options) { _, which ->
        when (which) {
          0 -> exportBackupLauncher.launch("chincheta-copia-${LocalDate.now()}.json")
          1 -> importBackupLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
        }
      }
      .show()
  }

  private fun exportBackup(uri: Uri) {
    val resolver = requireContext().contentResolver
    viewLifecycleOwner.lifecycleScope.launch {
      val result = withContext(dispatcherProvider.io) {
        runCatching { resolver.openOutputStream(uri)!!.use { historyBackup.export(it) } }
      }
      showSnackbar(
        result.fold(
          onSuccess = { getString(R.string.backup_exported, it) },
          onFailure = { getString(R.string.backup_failed) }
        )
      )
    }
  }

  private fun importBackup(uri: Uri) {
    val resolver = requireContext().contentResolver
    viewLifecycleOwner.lifecycleScope.launch {
      val result = withContext(dispatcherProvider.io) {
        runCatching { resolver.openInputStream(uri)!!.use { historyBackup.import(it) } }
      }
      showSnackbar(
        result.fold(
          onSuccess = { getString(R.string.backup_imported, it) },
          onFailure = { getString(R.string.backup_invalid) }
        )
      )
    }
  }

  private fun toggleAppLock(item: MenuItem) {
    val enable = !historySettings.appLockEnabled
    if (enable && !appLock.isAvailable(requireContext())) {
      showSnackbar(getString(R.string.app_lock_unavailable))
      return
    }
    appLock.confirm(this) {
      historySettings.appLockEnabled = enable
      item.isChecked = enable
      showSnackbar(getString(if (enable) R.string.app_lock_on else R.string.app_lock_off))
    }
  }

  private fun showSnackbar(message: String) {
    Snackbar.make(binding.notificationsRoot, message, Snackbar.LENGTH_SHORT)
      .setAnchorView(binding.bottomBar)
      .show()
  }

  override fun showNotificationsEmptyError() {
    binding.noNotificationsTextView.setText(R.string.no_notifications)
    binding.noNotificationsTextView.isVisible = true
    binding.noNotificationsImageView.isVisible = true
  }

  override fun hideNotificationsEmptyError() {
    binding.noNotificationsTextView.isGone = true
    binding.noNotificationsImageView.isGone = true
  }

  override fun hideNotifications() {
    binding.notificationsRecyclerView.isGone = true
    allNotifications = emptyList()
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
