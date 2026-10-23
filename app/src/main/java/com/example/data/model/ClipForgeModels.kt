package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class VideoProject(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val sourceFileName: String = "",
    val sourceUri: String = "",
    val durationSec: Long = 60L,
    val width: Long = 1920L,
    val height: Long = 1080L,
    val transcriptText: String = "",
    val campaignId: String = "",
    val status: String = "READY",
    val clipCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class WhopCampaign(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val brandName: String = "",
    val payoutPer1kViews: String = "$1.50 / 1K views",
    val targetPlatform: String = "TIKTOK",
    val preferredAspectRatio: String = "9:16",
    val minDurationSec: Long = 15L,
    val maxDurationSec: Long = 60L,
    val logoPlacement: String = "TOP_RIGHT",
    val requiredLogoAsset: String = "brand_watermark.png",
    @get:Exclude val customLogoLocalPath: String = "",
    val safeZoneTopPct: Long = 12L,
    val safeZoneBottomPct: Long = 20L,
    val requiredHashtags: String = "#whop #clips",
    val mandatoryCaptionCta: String = "Check link in bio",
    val seoGuidance: String = "Front-load primary keyword in first 40 chars",
    val minConfidenceScore: Long = 75L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ClipCandidate(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val campaignId: String = "",
    val title: String = "",
    val category: String = "GAMING_HIGHLIGHT",
    val reasonForSelection: String = "",
    val confidenceScore: Long = 85L,
    val startSec: Long = 0L,
    val endSec: Long = 30L,
    val aspectRatio: String = "9:16",
    val reframeFocusX: Long = 50L,
    val subtitlesEnabled: Boolean = true,
    val subtitleText: String = "",
    val captionText: String = "",
    val hashtags: String = "",
    val seoTitle: String = "",
    val seoKeywords: String = "",
    val logoPlacement: String = "TOP_RIGHT",
    val reviewStatus: String = "PROPOSED",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val durationSec: Long
        get() = (endSec - startSec).coerceAtLeast(1L)
}

@IgnoreExtraProperties
data class ApiUsageLog(
    @DocumentId val id: String = "",
    val userId: String = "",
    val providerId: String = "",
    val modelName: String = "",
    val operation: String = "",
    val latencyMs: Long = 0L,
    val estimatedTokens: Long = 0L,
    val retryCount: Long = 0L,
    val success: Boolean = true,
    val errorMessage: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

enum class HighlightCategory(val id: String, val label: String, val badgeHex: Long) {
    FUNNY_MOMENT("FUNNY_MOMENT", "Funny Moment", 0xFFF59E0B),
    REACTION("REACTION", "High Reaction", 0xFFEC4899),
    GAMING_HIGHLIGHT("GAMING_HIGHLIGHT", "Gaming Highlight", 0xFF38BDF8),
    CLUTCH_PLAY("CLUTCH_PLAY", "Clutch Play", 0xFF10B981),
    EXCITING_MOMENT("EXCITING_MOMENT", "Exciting Peak", 0xFFA855F7),
    MEMORABLE_DIALOGUE("MEMORABLE_DIALOGUE", "Memorable Quote", 0xFF6366F1);

    companion object {
        fun fromId(id: String): HighlightCategory =
            entries.firstOrNull { it.id == id } ?: GAMING_HIGHLIGHT
    }
}

data class CampaignValidationCheck(
    val ruleName: String,
    val passed: Boolean,
    val detail: String
)

data class CampaignValidationReport(
    val campaignName: String,
    val checks: List<CampaignValidationCheck>
) {
    val allPassed: Boolean get() = checks.all { it.passed }
    val passedCount: Int get() = checks.count { it.passed }
}

enum class ProviderVerificationState(val label: String) {
    VERIFIED_WORKING("VERIFIED WORKING (LIVE TEST PASSED)"),
    UNTESTED("UNTESTED — Run Live Test Before Relying on Backup"),
    FAILED("TEST FAILED — Check Endpoint or Quota")
}

data class AiProviderConfig(
    val id: String,
    val name: String,
    val modelOrEndpoint: String,
    val roleLabel: String,
    val costNotes: String,
    val isBuiltInFirebase: Boolean,
    val verificationState: ProviderVerificationState = ProviderVerificationState.UNTESTED,
    val lastTestedLatencyMs: Long? = null,
    val lastTestMessage: String = "Not tested in this session yet."
)

/**
 * Reusable Campaign Preset combining Whop campaign validation rules, hashtags,
 * aspect ratio preferences, viral video caption style, captions ON/OFF state,
 * and default clip count (1–10) for 1-tap application to new projects.
 */
data class CampaignPreset(
    val id: String = "",
    val userId: String = "",
    val presetName: String = "",
    val campaignName: String = "",
    val brandName: String = "",
    val payoutPer1kViews: String = "$2.00 / 1K views",
    val targetPlatform: String = "TIKTOK",
    val preferredAspectRatio: String = "9:16",
    val minDurationSec: Long = 15L,
    val maxDurationSec: Long = 55L,
    val logoPlacement: String = "TOP_RIGHT",
    val requiredLogoAsset: String = "whop_partner_watermark.png",
    val customLogoLocalPath: String = "",
    val safeZoneTopPct: Long = 12L,
    val safeZoneBottomPct: Long = 20L,
    val requiredHashtags: String = "#whop #clipping #viral",
    val mandatoryCaptionCta: String = "Check link in bio",
    val seoGuidance: String = "Front-load primary keyword in first 40 chars",
    val minConfidenceScore: Long = 78L,
    val viralCaptionStyle: String = "HORMOZI_BOLD",
    val includeVideoCaptions: Boolean = true,
    val defaultClipCount: Int = 3
) {
    fun toWhopCampaign(existingCampaignId: String = ""): WhopCampaign = WhopCampaign(
        id = existingCampaignId,
        userId = userId,
        name = campaignName.ifBlank { presetName },
        brandName = brandName.ifBlank { "Creator Brand" },
        payoutPer1kViews = payoutPer1kViews,
        targetPlatform = targetPlatform,
        preferredAspectRatio = preferredAspectRatio.ifBlank { "9:16" },
        minDurationSec = minDurationSec,
        maxDurationSec = maxDurationSec,
        logoPlacement = logoPlacement,
        requiredLogoAsset = requiredLogoAsset,
        customLogoLocalPath = customLogoLocalPath,
        safeZoneTopPct = safeZoneTopPct,
        safeZoneBottomPct = safeZoneBottomPct,
        requiredHashtags = requiredHashtags,
        mandatoryCaptionCta = mandatoryCaptionCta,
        seoGuidance = seoGuidance,
        minConfidenceScore = minConfidenceScore
    )
}

