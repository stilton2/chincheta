package dev.sasikanth.pinnit.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.chrisbanes.insetter.setEdgeToEdgeSystemUiFlags
import dev.sasikanth.pinnit.R
import dev.sasikanth.pinnit.data.preferences.AppPreferences
import dev.sasikanth.pinnit.databinding.ActivityMainBinding
import dev.sasikanth.pinnit.oemwarning.OemWarningDialog
import dev.sasikanth.pinnit.oemwarning.shouldShowWarningForOEM
import dev.sasikanth.pinnit.utils.DispatcherProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

  @Inject
  lateinit var appPreferencesStore: DataStore<AppPreferences>

  @Inject
  lateinit var dispatcherProvider: DispatcherProvider

  private var navController: NavController? = null

  private lateinit var binding: ActivityMainBinding

  private var listenerAccessDialog: AlertDialog? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    binding.mainRoot.setEdgeToEdgeSystemUiFlags()

    val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_container) as NavHostFragment
    navController = navHostFragment.navController

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        val isOemWarningDialogShown = withContext(dispatcherProvider.io) {
          appPreferencesStore.data.first().oemWarningDialog
        }
        showOemWarningDialog(isOemWarningDialogShown)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    showListenerAccessDialogIfNeeded()
  }

  // Notification history needs notification access; without it nothing is saved.
  private fun showListenerAccessDialogIfNeeded() {
    val hasAccess = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    if (hasAccess) {
      listenerAccessDialog?.dismiss()
      listenerAccessDialog = null
      return
    }
    if (listenerAccessDialog?.isShowing == true) return

    listenerAccessDialog = MaterialAlertDialogBuilder(this)
      .setTitle(R.string.listener_access_title)
      .setMessage(R.string.listener_access_message)
      .setPositiveButton(R.string.listener_access_open_settings) { _, _ ->
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
      }
      .setNegativeButton(R.string.listener_access_later, null)
      .show()
  }

  private suspend fun showOemWarningDialog(isOemWarningDialogShown: Boolean) {
    val brandName = Build.BRAND.lowercase(Locale.getDefault())
    if (isOemWarningDialogShown.not() && shouldShowWarningForOEM(brandName)) {
      appPreferencesStore.updateData { currentData ->
        currentData.toBuilder()
          .setOemWarningDialog(true)
          .build()
      }

      OemWarningDialog.show(supportFragmentManager)
    }
  }

  override fun onDestroy() {
    navController = null
    super.onDestroy()
  }
}
