package dev.sasikanth.pinnit.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dev.sasikanth.pinnit.R
import dev.sasikanth.pinnit.data.preferences.HistorySettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional fingerprint / screen lock for the app. Once unlocked it stays unlocked until the app
 * has been in the background for more than [GRACE_PERIOD_MS] (so the share sheet or the file
 * picker don't ask again).
 */
@Singleton
class AppLock @Inject constructor(
  private val settings: HistorySettings
) : Application.ActivityLifecycleCallbacks {

  companion object {
    private const val GRACE_PERIOD_MS = 60_000L
  }

  private var startedActivities = 0
  private var backgroundSince = 0L
  private var unlocked = false
  private var prompting = false

  fun register(application: Application) {
    application.registerActivityLifecycleCallbacks(this)
  }

  private val authenticators: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

  /** True when the phone has a fingerprint or screen lock we can ask for. */
  fun isAvailable(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

  /** Hides [content] and asks to unlock if needed; closes the screen if the user cancels. */
  fun guard(activity: FragmentActivity, content: View) {
    if (!settings.appLockEnabled || unlocked) {
      content.visibility = View.VISIBLE
      return
    }
    content.visibility = View.INVISIBLE
    if (prompting) return
    prompting = true

    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        prompting = false
        unlocked = true
        content.visibility = View.VISIBLE
      }

      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        prompting = false
        activity.finish()
      }
    })
    prompt.authenticate(promptInfo(activity, R.string.app_lock_prompt_subtitle))
  }

  /** Asks to unlock from a screen that is already open (to turn the lock on or off). */
  fun confirm(fragment: Fragment, onSuccess: () -> Unit) {
    val context = fragment.requireContext()
    val prompt = BiometricPrompt(fragment, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        unlocked = true
        onSuccess()
      }
    })
    prompt.authenticate(promptInfo(context, R.string.app_lock_confirm_subtitle))
  }

  private fun promptInfo(context: Context, subtitle: Int): BiometricPrompt.PromptInfo {
    val builder = BiometricPrompt.PromptInfo.Builder()
      .setTitle(context.getString(R.string.app_name))
      .setSubtitle(context.getString(subtitle))
      .setAllowedAuthenticators(authenticators)
    if (authenticators and DEVICE_CREDENTIAL == 0) {
      builder.setNegativeButtonText(context.getString(android.R.string.cancel))
    }
    return builder.build()
  }

  override fun onActivityStarted(activity: Activity) {
    if (startedActivities == 0 && backgroundSince > 0 &&
      System.currentTimeMillis() - backgroundSince > GRACE_PERIOD_MS
    ) {
      unlocked = false
    }
    startedActivities++
  }

  override fun onActivityStopped(activity: Activity) {
    startedActivities = (startedActivities - 1).coerceAtLeast(0)
    if (startedActivities == 0 && !activity.isChangingConfigurations) {
      backgroundSince = System.currentTimeMillis()
    }
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
  override fun onActivityResumed(activity: Activity) = Unit
  override fun onActivityPaused(activity: Activity) = Unit
  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
  override fun onActivityDestroyed(activity: Activity) = Unit
}
