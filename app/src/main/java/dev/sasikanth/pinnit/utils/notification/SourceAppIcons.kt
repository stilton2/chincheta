package dev.sasikanth.pinnit.utils.notification

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Icons of the apps whose notifications end up in the history, so a pinned copy can
 * look like the original: the original status bar (small) icon and the app icon.
 *
 * The small icon only exists while the original notification is alive, so it is saved
 * to disk (one file per app) when the listener sees a notification from that app.
 */
@Singleton
class SourceAppIcons @Inject constructor(
  @ApplicationContext private val context: Context
) {

  companion object {
    private const val TAG = "SourceAppIcons"
    private const val SMALL_ICON_SIZE_PX = 96
    private const val LARGE_ICON_SIZE_PX = 192
    private val SMALL_ICON_MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)
  }

  private val smallIconsDir: File
    get() = File(context.filesDir, "source_small_icons").apply { mkdirs() }

  private fun smallIconFile(sourcePackage: String) = File(smallIconsDir, "$sourcePackage.png")

  /** Saves the original notification's small icon, refreshing it once a week. */
  fun saveSmallIcon(sourcePackage: String, icon: Icon?) {
    if (icon == null) return
    val file = smallIconFile(sourcePackage)
    if (file.exists() && System.currentTimeMillis() - file.lastModified() < SMALL_ICON_MAX_AGE_MS) return

    try {
      val drawable = icon.loadDrawable(context) ?: return
      val bitmap = drawable.toBitmap(SMALL_ICON_SIZE_PX, SMALL_ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } catch (e: Exception) {
      Log.d(TAG, "Couldn't save small icon of $sourcePackage", e)
    }
  }

  fun smallIcon(sourcePackage: String): Bitmap? {
    val file = smallIconFile(sourcePackage)
    if (!file.exists()) return null
    return BitmapFactory.decodeFile(file.absolutePath)
  }

  fun appIcon(sourcePackage: String): Bitmap? {
    return try {
      context.packageManager.getApplicationIcon(sourcePackage)
        .toBitmap(LARGE_ICON_SIZE_PX, LARGE_ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
    } catch (e: PackageManager.NameNotFoundException) {
      null
    }
  }
}
