package dev.sasikanth.pinnit.data.preferences

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification history settings. Plain SharedPreferences (not the proto DataStore) because the
 * listener reads them on every notification and they are tiny.
 */
@Singleton
class HistorySettings @Inject constructor(
  @ApplicationContext context: Context
) {

  companion object {
    private const val PREFS_NAME = "history_settings"
    private const val KEY_IGNORED_PACKAGES = "ignored_packages"
    private const val KEY_RETENTION_DAYS = "history_retention_days"

    const val RETENTION_FOREVER = 0
    const val DEFAULT_RETENTION_DAYS = 30
    val RETENTION_CHOICES = listOf(7, 30, 90, 365, RETENTION_FOREVER)
  }

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** Apps whose notifications are not saved in the history. */
  var ignoredPackages: Set<String>
    get() = prefs.getStringSet(KEY_IGNORED_PACKAGES, emptySet()).orEmpty().toSet()
    set(value) = prefs.edit { putStringSet(KEY_IGNORED_PACKAGES, value.toSet()) }

  fun isIgnored(sourcePackage: String) = sourcePackage in ignoredPackages

  fun ignore(sourcePackage: String) {
    ignoredPackages = ignoredPackages + sourcePackage
  }

  /** Days unpinned history is kept; [RETENTION_FOREVER] keeps it forever. */
  var historyRetentionDays: Int
    get() = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
    set(value) = prefs.edit { putInt(KEY_RETENTION_DAYS, value) }
}
