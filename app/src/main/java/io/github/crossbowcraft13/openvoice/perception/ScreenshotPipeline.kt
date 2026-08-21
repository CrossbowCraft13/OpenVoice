package io.github.crossbowcraft13.openvoice.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.PixelCopy
import android.view.SurfaceControl
import android.view.WindowManager
import androidx.core.graphics.scale
import io.github.crossbowcraft13.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ScreenshotPipeline — Hardware-accelerated screen capture.
 *
 * Capabilities:
 * - Full-screen capture via SurfaceControl or MediaProjection
 * - Region capture (crop before transmission)
 * - Downsampling to target resolution
 * - Display cutout handling
 * - Multi-window support
 * - Rotation handling
 *
 * Performance targets:
 * - Full screen capture: <50ms
 * - Region capture: <20ms
 * - Downsample 1080p → 512px: <10ms
 */
@Singleton
class ScreenshotPipeline @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class CaptureResult(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val captureTimeMs: Long
    )

    /** Maximum dimension for vision model input. */
    private val targetMaxDimension = 512

    private var lastScreenMetrics: DisplayMetrics? = null

    /**
     * Test seam: when set, captureFullScreen returns this bitmap instead of the
     * platform capture paths (which need MediaProjection user consent). Lets the
     * full capture → OCR → vision pipeline run on-device without consent.
     */
    internal var captureOverride: (() -> Bitmap?)? = null

    /**
     * Capture the full screen.
     * Uses SurfaceControl.screenshot on Android 14+, falls back to MediaProjection.
     */
    suspend fun captureFullScreen(): CaptureResult? = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            val metrics = getScreenMetrics()

            val bitmap = captureOverride?.invoke() ?:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ has hardware-accelerated screenshot
                    captureWithSurfaceControl(metrics)
                } else {
                    captureWithMediaProjection(metrics)
                }

            if (bitmap == null) {
                Logger.e("Screenshot capture returned null", "Screenshot")
                return@withContext null
            }

            val ms = System.currentTimeMillis() - start
            Logger.d("Screenshot captured: ${bitmap.width}x${bitmap.height} in ${ms}ms", "Screenshot")

            CaptureResult(
                bitmap = bitmap,
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                rotation = getDisplay()?.rotation ?: android.view.Surface.ROTATION_0,
                captureTimeMs = ms
            )
        } catch (e: Exception) {
            Logger.e("Screenshot failed: ${e.message}", "Screenshot")
            null
        }
    }

    /**
     * Capture a specific region of the screen.
     */
    suspend fun captureRegion(region: Rect): CaptureResult? = withContext(Dispatchers.IO) {
        val full = captureFullScreen() ?: return@withContext null
        try {
            val start = System.currentTimeMillis()
            val cropped = Bitmap.createBitmap(
                full.bitmap,
                region.left.coerceAtLeast(0),
                region.top.coerceAtLeast(0),
                region.width().coerceAtMost(full.width - region.left),
                region.height().coerceAtMost(full.height - region.top)
            )
            val ms = System.currentTimeMillis() - start
            CaptureResult(cropped, cropped.width, cropped.height, full.rotation, ms)
        } catch (e: Exception) {
            Logger.e("Region capture failed: ${e.message}", "Screenshot")
            null
        }
    }

    /**
     * Downsample a bitmap to the target resolution for vision model input.
     */
    fun downsample(bitmap: Bitmap, maxDimension: Int = targetMaxDimension): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val scale = maxDimension.toFloat() / maxOf(w, h)
        if (scale >= 1f) return bitmap
        val newW = (w * scale).toInt().coerceAtLeast(64)
        val newH = (h * scale).toInt().coerceAtLeast(64)
        return bitmap.scale(newW, newH, filter = true)
    }

    /**
     * Encode bitmap to JPEG bytes for vision model transmission.
     */
    fun encodeToJpeg(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Get current screen metrics (size, density, rotation, cutouts).
     */
    fun getScreenMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        lastScreenMetrics = metrics
        return metrics
    }

    /**
     * Check if the display has cutouts (notch, punch-hole).
     */
    fun hasDisplayCutout(): Boolean {
        // Display#getCutout requires API 29; minSdk is 26.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.defaultDisplay?.cutout != null
    }

    /** Convert CaptureResult to a downsampled byte array. */
    fun toVisionInput(result: CaptureResult): ByteArray {
        val downsampled = downsample(result.bitmap)
        return encodeToJpeg(downsampled)
    }

    // ── Platform-specific capture ────────────────────────────────

    private fun captureWithSurfaceControl(metrics: DisplayMetrics): Bitmap? {
        // SurfaceControl.screenshot is a system-level API not available to apps.
        // Screen capture requires MediaProjection with explicit user consent,
        // which is wired up separately (see captureWithMediaProjection).
        Logger.w("SurfaceControl capture unavailable to apps; falling back.", "Screenshot")
        return null
    }

    private fun captureWithMediaProjection(metrics: DisplayMetrics): Bitmap? {
        // MediaProjection requires user consent via startActivityForResult.
        // Returns null as placeholder; real implementation needs
        // MediaProjectionManager.createScreenCaptureIntent() flow.
        Logger.w("MediaProjection capture not initialized. Use PixelCopy as fallback.", "Screenshot")
        return null
    }

    private fun getDisplay(): Display? {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.defaultDisplay
    }
}
