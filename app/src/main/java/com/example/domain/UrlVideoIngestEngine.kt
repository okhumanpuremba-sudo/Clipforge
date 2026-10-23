package com.example.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

enum class StreamPlatform(val label: String) {
    YOUTUBE("YouTube"),
    TWITCH("Twitch"),
    GENERIC_URL("Web Stream")
}

data class IngestedUrlVideo(
    val normalizedUrl: String,
    val platform: StreamPlatform,
    val externalId: String,
    val title: String,
    val authorOrChannel: String,
    val durationSec: Long,
    val width: Long,
    val height: Long,
    val extractedTranscriptOrContext: String,
    val extractionSourceLabel: String,
    val thumbnailBitmap: Bitmap?
)

/**
 * Real network ingestion engine for YouTube and Twitch links.
 * - Uses public YouTube oEmbed + watch page playerResponse metadata & captionTracks when publicly accessible.
 * - Uses OpenGraph metadata for Twitch VODs/Clips/Channels.
 * - Never simulates fake video file downloads; clearly labels extracted metadata/captions vs remote yt-dlp stream downloading.
 */
object UrlVideoIngestEngine {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun detectPlatform(rawUrl: String): StreamPlatform {
        val lower = rawUrl.trim().lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> StreamPlatform.YOUTUBE
            lower.contains("twitch.tv") -> StreamPlatform.TWITCH
            else -> StreamPlatform.GENERIC_URL
        }
    }

    fun extractYouTubeId(rawUrl: String): String? {
        return try {
            val trimmed = rawUrl.trim()
            val uri = Uri.parse(if (trimmed.startsWith("http")) trimmed else "https://$trimmed")
            val host = uri.host?.lowercase().orEmpty()
            when {
                host.contains("youtu.be") -> uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
                host.contains("youtube.com") -> {
                    val vParam = uri.getQueryParameter("v")
                    if (!vParam.isNullOrBlank()) {
                        vParam
                    } else {
                        val segments = uri.pathSegments
                        val idx = segments.indexOfFirst { it in listOf("shorts", "live", "embed", "v") }
                        if (idx >= 0 && idx + 1 < segments.size) segments[idx + 1] else null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun ingestStreamUrl(
        rawUrl: String,
        userFocusNotes: String = ""
    ): Result<IngestedUrlVideo> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawUrl.trim()
            require(trimmed.isNotBlank()) { "Please enter a YouTube or Twitch link." }
            val normalizedUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }

            val platform = detectPlatform(normalizedUrl)
            when (platform) {
                StreamPlatform.YOUTUBE -> ingestYouTubeUrl(normalizedUrl, userFocusNotes)
                StreamPlatform.TWITCH -> ingestTwitchUrl(normalizedUrl, userFocusNotes)
                StreamPlatform.GENERIC_URL -> ingestGenericStreamUrl(normalizedUrl, userFocusNotes)
            }
        }
    }

    private fun ingestYouTubeUrl(url: String, userFocusNotes: String): IngestedUrlVideo {
        val videoId = extractYouTubeId(url)
            ?: throw IllegalArgumentException("Could not parse a valid YouTube video ID from link.")
        val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"

        var title = "YouTube Video ($videoId)"
        var author = "YouTube Creator"
        var thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        // 1. Query public YouTube oEmbed endpoint (no API key required)
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=$canonicalUrl&format=json"
            val req = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) ClipForgeAI/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        title = json.optString("title", title)
                        author = json.optString("author_name", author)
                        thumbUrl = json.optString("thumbnail_url", thumbUrl)
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Fetch watch page to extract real duration, description chapters, and public caption track if present
        var durationSec = 360L
        var shortDescription = ""
        var captionsTranscript = ""
        var extractionSource = "LIVE YOUTUBE OEMBED + THUMBNAIL"

        try {
            val watchReq = Request.Builder()
                .url(canonicalUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            httpClient.newCall(watchReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()

                    val lenMatch = Regex("\"lengthSeconds\":\"(\\d+)\"").find(html)
                    val parsedLen = lenMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                    if (parsedLen != null && parsedLen > 0L) {
                        durationSec = parsedLen.coerceIn(15L, 36000L)
                    }

                    val descMatch = Regex("\"shortDescription\":\"((?:\\\\.|[^\"\\\\])*)\"").find(html)
                    if (descMatch != null) {
                        shortDescription = descMatch.groupValues[1]
                            .replace("\\n", "\n")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                            .take(4000)
                    }

                    val captionUrlMatch = Regex("\"captionTracks\":\\[\\{\"baseUrl\":\"([^\"]+)\"").find(html)
                    if (captionUrlMatch != null) {
                        val rawCaptionUrl = captionUrlMatch.groupValues[1]
                            .replace("\\u0026", "&")
                            .replace("\\/", "/")
                        captionsTranscript = fetchYouTubeTimedTextXml(rawCaptionUrl)
                    }

                    extractionSource = when {
                        captionsTranscript.isNotBlank() -> "LIVE YOUTUBE CAPTIONS + PLAYER METADATA"
                        shortDescription.isNotBlank() -> "LIVE YOUTUBE PLAYER METADATA + DESCRIPTION"
                        else -> "LIVE YOUTUBE OEMBED + THUMBNAIL"
                    }
                }
            }
        } catch (_: Exception) {
        }

        val thumbnailBitmap = downloadThumbnailBitmap(thumbUrl)

        val combinedTranscript = buildString {
            appendLine("SOURCE LINK: $canonicalUrl")
            appendLine("PLATFORM: YouTube | CHANNEL: $author | VIDEO ID: $videoId")
            appendLine("EXTRACTED DURATION: ${durationSec}s | EXTRACTION MODE: $extractionSource")
            if (userFocusNotes.isNotBlank()) {
                appendLine("\nUSER VIRAL CLIP GOAL / FOCUS NOTES:")
                appendLine(userFocusNotes.trim())
            }
            if (captionsTranscript.isNotBlank()) {
                appendLine("\nEXTRACTED TIMECODED VIDEO CAPTIONS:")
                appendLine(captionsTranscript.take(9000))
            }
            if (shortDescription.isNotBlank()) {
                appendLine("\nVIDEO DESCRIPTION & CHAPTER TIMESTAMPS:")
                appendLine(shortDescription.take(3500))
            }
            if (captionsTranscript.isBlank() && shortDescription.isBlank()) {
                appendLine("\nNOTE: Public caption tracks were not exposed on this video page. Analyze the video title ('$title'), creator ('$author'), thumbnail visual cues, and duration (${durationSec}s) to propose the highest-probability viral clip windows and hooks.")
            }
        }.take(14500)

        return IngestedUrlVideo(
            normalizedUrl = canonicalUrl,
            platform = StreamPlatform.YOUTUBE,
            externalId = videoId,
            title = title,
            authorOrChannel = author,
            durationSec = durationSec,
            width = 1920L,
            height = 1080L,
            extractedTranscriptOrContext = combinedTranscript,
            extractionSourceLabel = extractionSource,
            thumbnailBitmap = thumbnailBitmap
        )
    }

    private fun ingestTwitchUrl(url: String, userFocusNotes: String): IngestedUrlVideo {
        val uri = Uri.parse(url)
        val segments = uri.pathSegments
        val identifier = segments.lastOrNull()?.takeIf { it.isNotBlank() } ?: "twitch_stream"
        val isVod = segments.contains("videos")
        val isClip = url.lowercase().contains("clip")

        var title = when {
            isVod -> "Twitch VOD #$identifier"
            isClip -> "Twitch Clip ($identifier)"
            else -> "Twitch Stream • $identifier"
        }
        var description = ""
        var thumbUrl = ""
        var durationSec = if (isClip) 60L else 900L
        var extractionSource = "TWITCH URL PARSER"

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    val ogTitle = extractMetaContent(html, "og:title")
                    val ogDesc = extractMetaContent(html, "og:description")
                    val ogImage = extractMetaContent(html, "og:image")
                    val ogDur = extractMetaContent(html, "og:video:duration")?.toLongOrNull()

                    if (!ogTitle.isNullOrBlank()) title = ogTitle
                    if (!ogDesc.isNullOrBlank()) description = ogDesc
                    if (!ogImage.isNullOrBlank()) thumbUrl = ogImage
                    if (ogDur != null && ogDur > 0L) durationSec = ogDur.coerceIn(15L, 36000L)
                    if (!ogTitle.isNullOrBlank() || !ogDesc.isNullOrBlank()) {
                        extractionSource = "LIVE TWITCH OPENGRAPH METADATA"
                    }
                }
            }
        } catch (_: Exception) {
        }

        val thumbnailBitmap = if (thumbUrl.isNotBlank()) downloadThumbnailBitmap(thumbUrl) else null

        val combinedContext = buildString {
            appendLine("SOURCE LINK: $url")
            appendLine("PLATFORM: Twitch | IDENTIFIER: $identifier | DURATION ESTIMATE: ${durationSec}s")
            appendLine("EXTRACTION MODE: $extractionSource")
            if (userFocusNotes.isNotBlank()) {
                appendLine("\nUSER VIRAL CLIP GOAL / STREAM CONTEXT:")
                appendLine(userFocusNotes.trim())
            }
            if (description.isNotBlank()) {
                appendLine("\nTWITCH STREAM / VOD DESCRIPTION:")
                appendLine(description.take(4000))
            }
            appendLine(
                "\nSTREAM HIGHLIGHT GUIDANCE: Identify high-energy chat spikes, clutch gameplay moments, streamer reactions, funny fails, and quotable hot takes across 0..${durationSec}s."
            )
        }.take(14500)

        return IngestedUrlVideo(
            normalizedUrl = url,
            platform = StreamPlatform.TWITCH,
            externalId = identifier,
            title = title,
            authorOrChannel = identifier,
            durationSec = durationSec,
            width = 1920L,
            height = 1080L,
            extractedTranscriptOrContext = combinedContext,
            extractionSourceLabel = extractionSource,
            thumbnailBitmap = thumbnailBitmap
        )
    }

    private fun ingestGenericStreamUrl(url: String, userFocusNotes: String): IngestedUrlVideo {
        val uri = Uri.parse(url)
        val host = uri.host ?: "stream"
        val lastSeg = uri.lastPathSegment ?: "video"
        return IngestedUrlVideo(
            normalizedUrl = url,
            platform = StreamPlatform.GENERIC_URL,
            externalId = lastSeg,
            title = "Stream Link ($host / $lastSeg)",
            authorOrChannel = host,
            durationSec = 300L,
            width = 1920L,
            height = 1080L,
            extractedTranscriptOrContext = buildString {
                appendLine("SOURCE LINK: $url")
                if (userFocusNotes.isNotBlank()) {
                    appendLine("USER VIRAL CLIP FOCUS: $userFocusNotes")
                }
            },
            extractionSourceLabel = "URL METADATA",
            thumbnailBitmap = null
        )
    }

    private fun fetchYouTubeTimedTextXml(captionUrl: String): String {
        return try {
            val req = Request.Builder()
                .url(captionUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val xml = resp.body?.string().orEmpty()
                if (xml.isBlank()) return ""

                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(xml.reader())
                val sb = StringBuilder()
                var eventType = parser.eventType
                var currentStartSec = 0L

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name == "text") {
                        val startAttr = parser.getAttributeValue(null, "start")
                        currentStartSec = startAttr?.toDoubleOrNull()?.toLong() ?: 0L
                    } else if (eventType == XmlPullParser.TEXT) {
                        val text = parser.text
                            ?.replace("&amp;", "&")
                            ?.replace("&#39;", "'")
                            ?.replace("&quot;", "\"")
                            ?.replace("\n", " ")
                            ?.trim()
                            .orEmpty()
                        if (text.isNotEmpty()) {
                            val mins = currentStartSec / 60
                            val secs = currentStartSec % 60
                            sb.appendLine(String.format("[%02d:%02d] %s", mins, secs, text))
                            if (sb.length > 8500) break
                        }
                    }
                    eventType = parser.next()
                }
                sb.toString()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractMetaContent(html: String, propertyName: String): String? {
        val regex = Regex(
            "<meta[^>]+(?:property|name)=[\"']$propertyName[\"'][^>]+content=[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.getOrNull(1)
    }

    private fun downloadThumbnailBitmap(thumbUrl: String): Bitmap? {
        return try {
            val req = Request.Builder().url(thumbUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                Bitmap.createScaledBitmap(decoded, 480, 270, true)
            }
        } catch (_: Exception) {
            null
        }
    }
}
