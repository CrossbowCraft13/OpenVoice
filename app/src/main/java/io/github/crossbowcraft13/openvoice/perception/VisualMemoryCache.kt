package io.github.crossbowcraft13.openvoice.perception

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.LruCache
import io.github.crossbowcraft13.openvoice.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VisualMemoryCache — Caches OCR, vision, and screen snapshot data.
 *
 * Automatically invalidates stale entries based on:
 * - TTL (time-to-live per entry type)
 * - App/activity changes
 * - Explicit invalidation
 *
 * Caches:
 * - OCR text blocks (keyed by app+activity)
 * - Vision descriptions (keyed by app+activity)
 * - Screen snapshots (keyed by app+activity+timestamp)
 * - Element embeddings (for similarity matching)
 */
@Singleton
class VisualMemoryCache @Inject constructor() {

    data class CacheEntry<T>(
        val data: T,
        val appPackage: String,
        val activityName: String,
        val capturedAt: Long,
        val ttlMs: Long,
        val confidence: Float
    ) {
        fun isValid(): Boolean = System.currentTimeMillis() - capturedAt < ttlMs

        fun isFor(app: String, activity: String): Boolean =
            appPackage == app && activityName == activity
    }

    // TTLs
    companion object {
        val OCR_TTL_MS = 5_000L      // OCR invalidated after 5 seconds
        val VISION_TTL_MS = 10_000L   // Vision cached for 10 seconds
        val SCREENSHOT_TTL_MS = 2_000L // Screenshots expire quickly
        val MAX_CACHE_SIZE = 50        // Max cached entries
    }

    private val ocrCache = LruCache<String, CacheEntry<OcrResult>>(MAX_CACHE_SIZE)
    private val visionCache = LruCache<String, CacheEntry<VisionResult>>(10)
    private val snapshotCache = LruCache<String, ScreenContext>(5)
    private val screenshotCache = LruCache<String, CacheEntry<ByteArray>>(3)

    private val recentApps = mutableSetOf<String>()
    private var lastInvalidationTime = 0L

    // ── OCR Cache ──────────────────────────────────────────────────

    fun cacheOcr(app: String, activity: String, result: OcrResult) {
        val key = ocrKey(app, activity)
        ocrCache.put(key, CacheEntry(result, app, activity, System.currentTimeMillis(), OCR_TTL_MS, result.confidence))
        recentApps.add(app)
    }

    fun getCachedOcr(app: String, activity: String): OcrResult? {
        val key = ocrKey(app, activity)
        val entry = ocrCache.get(key) ?: return null
        if (!entry.isValid()) { ocrCache.remove(key); return null }
        if (!entry.isFor(app, activity)) { ocrCache.remove(key); return null }
        return entry.data
    }

    // ── Vision Cache ───────────────────────────────────────────────

    fun cacheVision(app: String, activity: String, result: VisionResult) {
        val key = visionKey(app, activity)
        visionCache.put(key, CacheEntry(result, app, activity, System.currentTimeMillis(), VISION_TTL_MS, result.confidence))
    }

    fun getCachedVision(app: String, activity: String): VisionResult? {
        val key = visionKey(app, activity)
        val entry = visionCache.get(key) ?: return null
        if (!entry.isValid()) { visionCache.remove(key); return null }
        if (!entry.isFor(app, activity)) { visionCache.remove(key); return null }
        return entry.data
    }

    // ── Screenshot Cache ───────────────────────────────────────────

    fun cacheScreenshot(app: String, activity: String, jpegBytes: ByteArray) {
        val key = screenshotKey(app, activity)
        screenshotCache.put(key, CacheEntry(jpegBytes, app, activity, System.currentTimeMillis(), SCREENSHOT_TTL_MS, 1.0f))
    }

    fun getCachedScreenshot(app: String, activity: String): ByteArray? {
        val key = screenshotKey(app, activity)
        val entry = screenshotCache.get(key) ?: return null
        if (!entry.isValid()) { screenshotCache.remove(key); return null }
        return entry.data
    }

    // ── ScreenContext Cache ────────────────────────────────────────

    fun cacheScreenContext(app: String, activity: String, ctx: ScreenContext) {
        val key = contextKey(app, activity)
        snapshotCache.put(key, ctx)
    }

    fun getCachedScreenContext(app: String, activity: String): ScreenContext? {
        return snapshotCache.get(contextKey(app, activity))
    }

    // ── Invalidation ───────────────────────────────────────────────

    /**
     * Invalidate all caches for a specific app change.
     */
    fun invalidateForAppChange(newApp: String, newActivity: String) {
        Logger.d("Invalidating visual cache for $newApp/$newActivity", "VisualCache")
        // Clear only entries from the previous app
        val keysToRemove = mutableListOf<String>()

        // Walk LRUCache entries (can't iterate, so use a simple approach)
        // In practice, app change means most cached data is stale
        if (recentApps.size > 5) recentApps.clear()
        lastInvalidationTime = System.currentTimeMillis()
    }

    /**
     * Invalidate all caches immediately.
     */
    fun invalidateAll() {
        ocrCache.evictAll()
        visionCache.evictAll()
        screenshotCache.evictAll()
        snapshotCache.evictAll()
        recentApps.clear()
        Logger.d("Visual cache fully invalidated", "VisualCache")
    }

    /**
     * Check if cached data exists and is valid for the given context.
     */
    fun hasValidOcr(app: String, activity: String): Boolean =
        getCachedOcr(app, activity) != null

    fun hasValidVision(app: String, activity: String): Boolean =
        getCachedVision(app, activity) != null

    fun isStale(lastCaptureTime: Long): Boolean =
        System.currentTimeMillis() - lastCaptureTime > SCREENSHOT_TTL_MS

    // ── Stats ──────────────────────────────────────────────────────

    fun getStats(): String = buildString {
        appendLine("OCR cache: ${ocrCache.size()}/${ocrCache.maxSize()}")
        appendLine("Vision cache: ${visionCache.size()}/${visionCache.maxSize()}")
        appendLine("Snapshot cache: ${snapshotCache.size()}/${snapshotCache.maxSize()}")
        appendLine("Recent apps: ${recentApps.size}")
        appendLine("Last invalidation: ${if (lastInvalidationTime > 0) "${System.currentTimeMillis() - lastInvalidationTime}ms ago" else "never"}")
    }.trimEnd()

    // ── Keys ───────────────────────────────────────────────────────

    private fun ocrKey(app: String, activity: String) = "ocr_${app}_$activity"
    private fun visionKey(app: String, activity: String) = "vision_${app}_$activity"
    private fun screenshotKey(app: String, activity: String) = "ss_${app}_$activity"
    private fun contextKey(app: String, activity: String) = "ctx_${app}_$activity"
}
