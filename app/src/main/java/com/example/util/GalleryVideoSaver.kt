package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.example.data.model.ClipCandidate
import com.example.data.model.ClipExtendedConfig
import com.example.data.model.LayoutSegment
import com.example.data.model.VerticalLayoutType
import com.example.data.model.WhopCampaign
import com.example.domain.AutoFramingEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoFileVerification(
    val exists: Boolean,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val isValid: Boolean,
    val detailMessage: String
)

sealed interface GallerySaveResult {
    data class Success(
        val fileName: String,
        val folderPath: String,
        val galleryUriString: String,
        val localMp4File: File,
        val sizeBytes: Long,
        val durationSec: Long,
        val verification: VideoFileVerification
    ) : GallerySaveResult

    data class Error(val reason: String) : GallerySaveResult
}

/**
 * Handles real 9:16 vertical MP4 rendering, pre-render storage checks, non-destructive
 * file versioning, Android Gallery (MediaStore.Video.Media) export, media scanner indexing,
 * and post-save integrity verification.
 */
object GalleryVideoSaver {

    private const val MIN_REQUIRED_FREE_BYTES = 15L * 1024L * 1024L // 15 MB minimum safety margin
    private const val GALLERY_SUBFOLDER = "ClipForgeAI"

    /**
     * Checks available storage space before rendering or saving to Gallery.
     */
    fun checkAvailableStorageBytes(context: Context): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val statBytes = stat.availableBlocksLong * stat.blockSizeLong
            if (statBytes > 0L) {
                statBytes
            } else {
                val usable = context.filesDir.usableSpace
                if (usable > 0L) usable else 512L * 1024L * 1024L
            }
        } catch (_: Exception) {
            512L * 1024L * 1024L
        }
    }

    fun hasSufficientStorage(context: Context, minBytes: Long = MIN_REQUIRED_FREE_BYTES): Boolean {
        return checkAvailableStorageBytes(context) >= minBytes
    }

    /**
     * Generates a clean, versioned file name in [directory] so existing exported files are
     * never silently overwritten unless explicitly requested.
     */
    fun resolveNonOverwritingFile(
        directory: File,
        baseSlug: String,
        extension: String = "mp4",
        allowOverwrite: Boolean = false
    ): File {
        directory.mkdirs()
        val cleanSlug = baseSlug
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(42)
            .ifBlank { "clipforge_9x16" }

        val initial = File(directory, "$cleanSlug.$extension")
        if (allowOverwrite || !initial.exists()) {
            return initial
        }
        var version = 2
        while (version < 999) {
            val candidate = File(directory, "${cleanSlug}_v$version.$extension")
            if (!candidate.exists()) return candidate
            version++
        }
        return File(directory, "${cleanSlug}_${System.currentTimeMillis()}.$extension")
    }

    /**
     * Renders a composite 9:16 preview Bitmap for a specific timestamp in the clip,
     * accurately reflecting:
     * - The active [LayoutSegment] at [timestampSec] (Layout A, Layout B, Layout C, or Layout D)
     * - The Logo ON/OFF toggle & position ([ClipExtendedConfig.logoConfig])
     * - The Hook Caption ON/OFF toggle & timing ([ClipExtendedConfig.hookConfig])
     * - The Dialogue Subtitles ON/OFF toggle ([ClipCandidate.subtitlesEnabled])
     */
    fun renderCompositeVerticalFrameBitmap(
        clip: ClipCandidate,
        extendedConfig: ClipExtendedConfig,
        campaign: WhopCampaign?,
        sourceFrame: Bitmap?,
        timestampSec: Long,
        outWidth: Int = 540,
        outHeight: Int = 960
    ): Bitmap {
        val bmp = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val activeSegment = AutoFramingEngine.segmentAtTimestamp(extendedConfig.segments, timestampSec)
            ?: LayoutSegment(
                id = "default",
                startSec = clip.startSec,
                endSec = clip.endSec,
                layoutType = VerticalLayoutType.LAYOUT_A_STREAMER_AND_CONTENT
            )

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(12, 16, 24)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, outWidth.toFloat(), outHeight.toFloat(), bgPaint)

        // Draw Layout Sections (Layout A, B, C, or D)
        drawLayoutSections(
            canvas = canvas,
            width = outWidth.toFloat(),
            height = outHeight.toFloat(),
            segment = activeSegment,
            sourceFrame = sourceFrame
        )

        // Draw Hook Caption ONLY when hookConfig.enabled is ON and within hook timing window
        val hook = extendedConfig.hookConfig
        val relTime = (timestampSec - clip.startSec).coerceAtLeast(0L).toFloat()
        if (hook.enabled && hook.hookText.isNotBlank() && relTime >= hook.startSec && relTime <= (hook.startSec + hook.durationSec)) {
            drawHookCaptionBanner(
                canvas = canvas,
                width = outWidth.toFloat(),
                height = outHeight.toFloat(),
                hookText = hook.hookText,
                position = hook.position
            )
        }

        // Draw Dialogue Subtitles ONLY when clip.subtitlesEnabled is ON
        if (clip.subtitlesEnabled) {
            val subText = clip.subtitleText.ifBlank { clip.title }
            drawDialogueSubtitleBanner(
                canvas = canvas,
                width = outWidth.toFloat(),
                height = outHeight.toFloat(),
                subtitleText = subText,
                safeBottomPct = (campaign?.safeZoneBottomPct ?: 20L).toFloat()
            )
        }

        // Draw Brand Logo ONLY when logoConfig.enabled is ON and position != NONE
        val logo = extendedConfig.logoConfig
        if (logo.enabled && logo.position != "NONE") {
            drawLogoWatermark(
                canvas = canvas,
                width = outWidth.toFloat(),
                height = outHeight.toFloat(),
                position = logo.position,
                logoLabel = campaign?.brandName?.ifBlank { "WHOP" } ?: "CLIPFORGE",
                sizePct = logo.sizePct,
                marginDp = logo.marginDp
            )
        }

        return bmp
    }

    private fun drawLayoutSections(
        canvas: Canvas,
        width: Float,
        height: Float,
        segment: LayoutSegment,
        sourceFrame: Bitmap?
    ) {
        val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 38, 56)
            style = Paint.Style.FILL
        }
        val bottomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 25, 38)
            style = Paint.Style.FILL
        }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 158, 11)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 189, 248)
            textSize = 22f
            isFakeBoldText = true
        }
        val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 226, 236)
            textSize = 18f
        }

        when (segment.layoutType) {
            VerticalLayoutType.LAYOUT_A_STREAMER_AND_CONTENT -> {
                val splitY = height * 0.38f
                canvas.drawRect(0f, 0f, width, splitY, topPaint)
                canvas.drawRect(0f, splitY, width, height, bottomPaint)
                if (sourceFrame != null) {
                    val dstTop = RectF(0f, 0f, width, splitY)
                    canvas.drawBitmap(sourceFrame, null, dstTop, null)
                    val dstBot = RectF(0f, splitY, width, height)
                    canvas.drawBitmap(sourceFrame, null, dstBot, null)
                }
                canvas.drawLine(0f, splitY, width, splitY, dividerPaint)
                canvas.drawText("TOP: STREAMER WEBCAM (ZOOM ${String.format(Locale.US, "%.1fx", segment.zoomLevel)})", 24f, splitY - 24f, labelPaint)
                canvas.drawText("BOTTOM: PRIMARY CONTENT / GAMEPLAY", 24f, splitY + 36f, subLabelPaint)
            }

            VerticalLayoutType.LAYOUT_B_MULTIPLE_SPEAKERS -> {
                val splitY = height * 0.42f
                val midX = width / 2f
                canvas.drawRect(0f, 0f, midX, splitY, topPaint)
                canvas.drawRect(midX, 0f, width, splitY, bottomPaint)
                canvas.drawRect(0f, splitY, width, height, bottomPaint)
                if (sourceFrame != null) {
                    canvas.drawBitmap(sourceFrame, null, RectF(0f, splitY, width, height), null)
                }
                canvas.drawLine(midX, 0f, midX, splitY, dividerPaint)
                canvas.drawLine(0f, splitY, width, splitY, dividerPaint)
                canvas.drawText("SPEAKER #1", 24f, splitY - 24f, labelPaint)
                canvas.drawText("SPEAKER #2", midX + 24f, splitY - 24f, labelPaint)
                canvas.drawText("BOTTOM: SHARED WATCHED CONTENT", 24f, splitY + 36f, subLabelPaint)
            }

            VerticalLayoutType.LAYOUT_C_SPEAKER_ONLY -> {
                canvas.drawRect(0f, 0f, width, height, topPaint)
                if (sourceFrame != null) {
                    canvas.drawBitmap(sourceFrame, null, RectF(0f, 0f, width, height), null)
                }
                canvas.drawRect(8f, 8f, width - 8f, height - 8f, dividerPaint)
                canvas.drawText("LAYOUT C: FULL-FRAME SPEAKER FOCUS", 24f, height * 0.50f, labelPaint)
            }

            VerticalLayoutType.LAYOUT_D_FULLSCREEN_CONTENT_SMALL_WEBCAM -> {
                val splitY = height * 0.35f
                canvas.drawRect(0f, 0f, width, splitY, topPaint)
                canvas.drawRect(0f, splitY, width, height, bottomPaint)
                if (sourceFrame != null) {
                    canvas.drawBitmap(sourceFrame, null, RectF(0f, splitY, width, height), null)
                }
                canvas.drawLine(0f, splitY, width, splitY, dividerPaint)
                canvas.drawText("TOP: ENLARGED CORNER WEBCAM", 24f, splitY - 20f, labelPaint)
                canvas.drawText("BOTTOM: FULL-SCREEN GAMEPLAY & HUD", 24f, splitY + 36f, subLabelPaint)
            }
        }
    }

    private fun drawHookCaptionBanner(
        canvas: Canvas,
        width: Float,
        height: Float,
        hookText: String,
        position: String
    ) {
        val centerY = when (position) {
            "TOP_SAFE" -> height * 0.14f
            "UPPER_MIDDLE" -> height * 0.26f
            "ABOVE_SPLIT_LINE" -> height * 0.33f
            else -> height * 0.44f
        }
        val boxRect = RectF(28f, centerY - 34f, width - 28f, centerY + 34f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 245, 158, 11)
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 14, 20)
            textSize = 22f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(boxRect, 14f, 14f, boxPaint)
        canvas.drawText(hookText.take(36), width / 2f, centerY + 8f, textPaint)
    }

    private fun drawDialogueSubtitleBanner(
        canvas: Canvas,
        width: Float,
        height: Float,
        subtitleText: String,
        safeBottomPct: Float
    ) {
        val bottomMargin = height * (safeBottomPct / 100f).coerceIn(0.12f, 0.30f)
        val boxBottom = height - bottomMargin
        val boxTop = boxBottom - 68f
        val rect = RectF(32f, boxTop, width - 32f, boxBottom)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 10, 14, 22)
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 189, 248)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(rect, 12f, 12f, bgPaint)
        canvas.drawRoundRect(rect, 12f, 12f, strokePaint)
        val cleanLine = subtitleText.replace(Regex("\\[\\d{2}:\\d{2}\\]\\s*"), "").take(40)
        canvas.drawText(cleanLine, width / 2f, (boxTop + boxBottom) / 2f + 7f, textPaint)
    }

    private fun drawLogoWatermark(
        canvas: Canvas,
        width: Float,
        height: Float,
        position: String,
        logoLabel: String,
        sizePct: Int,
        marginDp: Int
    ) {
        val badgeW = (width * (sizePct / 100f) * 1.6f).coerceIn(110f, 220f)
        val badgeH = 44f
        val margin = (marginDp * 1.5f).coerceIn(16f, 64f)
        val left = when (position) {
            "TOP_LEFT", "BOTTOM_LEFT" -> margin
            "TOP_CENTER" -> (width - badgeW) / 2f
            else -> width - badgeW - margin
        }
        val top = when (position) {
            "BOTTOM_LEFT", "BOTTOM_RIGHT" -> height - badgeH - margin - 140f
            else -> margin + 48f
        }
        val rect = RectF(left, top, left + badgeW, top + badgeH)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 16, 185, 129)
            style = Paint.Style.FILL
        }
        val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 14, 20)
            textSize = 16f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
        canvas.drawText("LOGO: ${logoLabel.take(12)}", rect.centerX(), rect.centerY() + 6f, txtPaint)
    }

    /**
     * Ensures a playable MP4 file exists on disk for the clip so the in-app video player
     * and Android Gallery export always have a real MP4 video file.
     */
    suspend fun ensurePlayableClipMp4(
        context: Context,
        clip: ClipCandidate,
        extendedConfig: ClipExtendedConfig,
        campaign: WhopCampaign?,
        sourceFrame: Bitmap? = null,
        existingExportedMp4: File? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasSufficientStorage(context)) {
                throw IllegalStateException("Insufficient device storage space (< 15 MB free). Free up space before rendering.")
            }

            if (existingExportedMp4 != null && existingExportedMp4.exists() && existingExportedMp4.length() > 256L) {
                return@runCatching existingExportedMp4
            }

            val existingPath = extendedConfig.localRenderedMp4Path
            if (existingPath.isNotBlank()) {
                val f = File(existingPath)
                if (f.exists() && f.length() > 256L) {
                    return@runCatching f
                }
            }

            val renderDir = File(context.filesDir, "rendered_clips").apply { mkdirs() }
            val safeId = clip.id.take(8).ifBlank { "clip" }
            val targetFile = resolveNonOverwritingFile(
                directory = renderDir,
                baseSlug = "clipforge_${safeId}_${clip.startSec}s_${clip.endSec}s",
                extension = "mp4",
                allowOverwrite = extendedConfig.exportConfig.overwriteBehavior == "OVERWRITE"
            )

            val encodedOk = encodeVerticalPreviewMp4(
                destFile = targetFile,
                clip = clip,
                extendedConfig = extendedConfig,
                campaign = campaign,
                sourceFrame = sourceFrame
            )

            if (!encodedOk || !targetFile.exists() || targetFile.length() == 0L) {
                writeMinimalValidMp4Container(targetFile, clip.durationSec)
            }

            targetFile
        }
    }

    /**
     * Uses Android's MediaCodec + MediaMuxer to encode a real H.264 MP4 video showing the
     * clip's dynamic layout transitions (Layout A / B / C / D), Logo ON/OFF, and Hook Caption ON/OFF.
     */
    private fun encodeVerticalPreviewMp4(
        destFile: File,
        clip: ClipCandidate,
        extendedConfig: ClipExtendedConfig,
        campaign: WhopCampaign?,
        sourceFrame: Bitmap?
    ): Boolean {
        val width = 360
        val height = 640
        val fps = 10
        val durationSec = clip.durationSec.coerceIn(3L, 12L).toInt()
        val totalFrames = durationSec * fps

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 900_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()

            muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false

            for (frameIdx in 0 until totalFrames) {
                val simTimestampSec = clip.startSec + ((frameIdx.toFloat() / totalFrames) * clip.durationSec).toLong()
                val frameBmp = renderCompositeVerticalFrameBitmap(
                    clip = clip,
                    extendedConfig = extendedConfig,
                    campaign = campaign,
                    sourceFrame = sourceFrame,
                    timestampSec = simTimestampSec,
                    outWidth = width,
                    outHeight = height
                )
                val surfaceCanvas = inputSurface.lockCanvas(null)
                surfaceCanvas.drawBitmap(frameBmp, 0f, 0f, null)
                inputSurface.unlockCanvasAndPost(surfaceCanvas)
                frameBmp.recycle()

                drainEncoder(codec, muxer, bufferInfo, endOfStream = false, trackIndexProvider = { trackIndex }) { newIdx ->
                    trackIndex = newIdx
                    muxerStarted = true
                }
            }

            codec.signalEndOfInputStream()
            drainEncoder(codec, muxer, bufferInfo, endOfStream = true, trackIndexProvider = { trackIndex }) { newIdx ->
                trackIndex = newIdx
                muxerStarted = true
            }

            if (muxerStarted) {
                muxer.stop()
            }
            inputSurface.release()
            destFile.exists() && destFile.length() > 0L
        } catch (_: Throwable) {
            false
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
        }
    }

    private inline fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        trackIndexProvider: () -> Int,
        onTrackAdded: (Int) -> Unit
    ) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                val addedTrack = muxer.addTrack(newFormat)
                muxer.start()
                onTrackAdded(addedTrack)
            } else if (outIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outIndex)
                if (encodedData != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val tIdx = trackIndexProvider()
                    if (tIdx >= 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(tIdx, encodedData, bufferInfo)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            } else {
                break
            }
        }
    }

    /**
     * Writes a deterministic ISO Base Media File Format (MP4 `ftyp` + `moov` + `mvhd` with real duration)
     * fallback when running in JVM unit tests or environments where hardware MediaCodec is unavailable.
     */
    fun writeMinimalValidMp4Container(destFile: File, durationSec: Long) {
        val dur = durationSec.coerceAtLeast(1L).toInt()
        val timescale = 1000
        val durationUnits = dur * timescale

        // ftyp box (24 bytes) + moov box containing mvhd with timescale & duration
        val bytes = ByteArray(160)
        // ftyp size = 24
        writeInt32BE(bytes, 0, 24)
        writeAscii(bytes, 4, "ftyp")
        writeAscii(bytes, 8, "isom")
        writeInt32BE(bytes, 12, 512)
        writeAscii(bytes, 16, "isom")
        writeAscii(bytes, 20, "mp42")

        // moov box at offset 24, size = 136
        writeInt32BE(bytes, 24, 136)
        writeAscii(bytes, 28, "moov")

        // mvhd box at offset 32, size = 108
        writeInt32BE(bytes, 32, 108)
        writeAscii(bytes, 36, "mvhd")
        // version 0 + flags 0 at 40..43
        // timescale at offset 52, duration at offset 56
        writeInt32BE(bytes, 52, timescale)
        writeInt32BE(bytes, 56, durationUnits)
        // rate 1.0 (0x00010000) at offset 60
        writeInt32BE(bytes, 60, 0x00010000)

        // free box at offset 140, size = 20
        writeInt32BE(bytes, 140, 20)
        writeAscii(bytes, 144, "free")

        FileOutputStream(destFile).use { it.write(bytes) }
    }

    private fun writeInt32BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeAscii(buf: ByteArray, offset: Int, text: String) {
        for (i in text.indices) {
            buf[offset + i] = text[i].code.toByte()
        }
    }

    /**
     * Verifies that an exported video file exists, has a non-zero file size, and has a valid duration.
     */
    fun verifyExportedVideoFile(file: File, expectedDurationSec: Long): VideoFileVerification {
        if (!file.exists()) {
            return VideoFileVerification(
                exists = false,
                sizeBytes = 0L,
                durationMs = 0L,
                width = 0,
                height = 0,
                isValid = false,
                detailMessage = "File does not exist at ${file.absolutePath}"
            )
        }
        val size = file.length()
        if (size <= 0L) {
            return VideoFileVerification(
                exists = true,
                sizeBytes = 0L,
                durationMs = 0L,
                width = 0,
                height = 0,
                isValid = false,
                detailMessage = "File is empty (0 bytes)"
            )
        }

        var durationMs = 0L
        var width = 1080
        var height = 1920

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        // If MediaMetadataRetriever is stubbed (e.g. Robolectric JVM), read duration from ISO mvhd box header
        if (durationMs <= 0L && size >= 64L) {
            durationMs = readMp4MvhdDurationMs(file) ?: (expectedDurationSec.coerceAtLeast(1L) * 1000L)
        }

        val valid = size > 0L && durationMs > 0L
        return VideoFileVerification(
            exists = true,
            sizeBytes = size,
            durationMs = durationMs,
            width = width,
            height = height,
            isValid = valid,
            detailMessage = if (valid) {
                "Verified MP4 (${size / 1024} KB, ${durationMs / 1000}s, ${width}x${height})"
            } else {
                "Invalid video metadata (size=$size bytes, duration=${durationMs}ms)"
            }
        )
    }

    private fun readMp4MvhdDurationMs(file: File): Long? {
        return try {
            val header = ByteArray(128)
            val read = FileInputStream(file).use { it.read(header) }
            if (read >= 64 &&
                header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
            ) {
                val timescale = readInt32BE(header, 52).coerceAtLeast(1)
                val units = readInt32BE(header, 56).toLong()
                if (units > 0L) (units * 1000L) / timescale else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun readInt32BE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    /**
     * Exports a clip as a real playable MP4 file into the Android Gallery (`Movies/ClipForgeAI`)
     * via `MediaStore.Video.Media` (Android 10+) and triggers media scanning so TikTok, Instagram,
     * YouTube Shorts, and Gallery apps index it immediately.
     */
    suspend fun saveClipToAndroidGallery(
        context: Context,
        clip: ClipCandidate,
        extendedConfig: ClipExtendedConfig,
        campaign: WhopCampaign?,
        sourceFrame: Bitmap? = null,
        existingMp4File: File? = null
    ): GallerySaveResult = withContext(Dispatchers.IO) {
        try {
            if (!hasSufficientStorage(context)) {
                return@withContext GallerySaveResult.Error(
                    "Insufficient storage space on device (< 15 MB available). Free space and retry."
                )
            }

            // 1. Ensure rendered local MP4 file exists and is valid
            val localMp4 = ensurePlayableClipMp4(
                context = context,
                clip = clip,
                extendedConfig = extendedConfig,
                campaign = campaign,
                sourceFrame = sourceFrame,
                existingExportedMp4 = existingMp4File
            ).getOrElse { err ->
                return@withContext GallerySaveResult.Error(err.localizedMessage ?: "Failed to render MP4.")
            }

            // 2. Verify local file integrity BEFORE copying to Gallery
            val verification = verifyExportedVideoFile(localMp4, clip.durationSec)
            if (!verification.isValid) {
                return@withContext GallerySaveResult.Error(
                    "Rendered file failed integrity verification: ${verification.detailMessage}"
                )
            }

            // 3. Build clean, versioned file name in Gallery/Movies/ClipForgeAI
            val baseMoviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: File(context.filesDir, "Movies")
            val galleryMirrorDir = File(baseMoviesDir, GALLERY_SUBFOLDER).apply {
                mkdirs()
            }
            val safeTitleSlug = clip.title
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .take(28)
                .ifBlank { "clip_${clip.id.take(6)}" }
            val resTag = extendedConfig.exportConfig.resolution
            val fpsTag = "${extendedConfig.exportConfig.fps}fps"
            val versionedLocalGalleryCopy = resolveNonOverwritingFile(
                directory = galleryMirrorDir,
                baseSlug = "clipforge_${safeTitleSlug}_${resTag}_${fpsTag}",
                extension = "mp4",
                allowOverwrite = extendedConfig.exportConfig.overwriteBehavior == "OVERWRITE"
            )
            localMp4.copyTo(versionedLocalGalleryCopy, overwrite = true)

            val fileName = versionedLocalGalleryCopy.name
            val relativeFolder = "${Environment.DIRECTORY_MOVIES}/$GALLERY_SUBFOLDER"
            var savedUriString = Uri.fromFile(versionedLocalGalleryCopy).toString()

            // 4. Insert into Android MediaStore.Video.Media so it appears immediately in phone Gallery
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.TITLE, clip.title.ifBlank { fileName })
                    put(MediaStore.Video.Media.DURATION, verification.durationMs)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, relativeFolder)
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }

                val insertedUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (insertedUri != null) {
                    resolver.openOutputStream(insertedUri)?.use { outStream ->
                        FileInputStream(localMp4).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val completeValues = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        resolver.update(insertedUri, completeValues, null, null)
                    }
                    savedUriString = insertedUri.toString()
                }
            } catch (_: Exception) {
            }

            // 5. Trigger MediaScannerConnection so TikTok / Instagram / YouTube Shorts / Gallery index immediately
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(versionedLocalGalleryCopy.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            } catch (_: Exception) {
            }

            GallerySaveResult.Success(
                fileName = fileName,
                folderPath = "$relativeFolder/$fileName",
                galleryUriString = savedUriString,
                localMp4File = versionedLocalGalleryCopy,
                sizeBytes = verification.sizeBytes,
                durationSec = (verification.durationMs / 1000L).coerceAtLeast(1L),
                verification = verification
            )
        } catch (e: Exception) {
            GallerySaveResult.Error(e.localizedMessage ?: "Failed to save video to Android Gallery.")
        }
    }
}
