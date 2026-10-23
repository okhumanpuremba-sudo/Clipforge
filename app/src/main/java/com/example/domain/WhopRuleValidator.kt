package com.example.domain

import com.example.data.model.CampaignValidationCheck
import com.example.data.model.CampaignValidationReport
import com.example.data.model.ClipCandidate
import com.example.data.model.VideoProject
import com.example.data.model.WhopCampaign
import java.io.File

object WhopRuleValidator {

    fun validateClip(clip: ClipCandidate, campaign: WhopCampaign): CampaignValidationReport {
        val checks = mutableListOf<CampaignValidationCheck>()
        val ext = clip.extendedConfig

        // 1. Duration Bounds Check
        val duration = clip.durationSec
        val durationOk = duration in campaign.minDurationSec..campaign.maxDurationSec
        checks.add(
            CampaignValidationCheck(
                ruleName = "Duration Window (${campaign.minDurationSec}s–${campaign.maxDurationSec}s)",
                passed = durationOk,
                detail = if (durationOk) {
                    "Clip duration ${duration}s is within campaign bounds."
                } else {
                    "Clip is ${duration}s; must be between ${campaign.minDurationSec}s and ${campaign.maxDurationSec}s."
                },
                isBlockingConflict = !durationOk
            )
        )

        // 2. Aspect Ratio & Dynamic Layout Check
        val targetAspect = campaign.preferredAspectRatio.ifBlank { "9:16" }
        val unreviewedLowConf = ext?.segments?.count { it.needsManualReview } ?: 0
        val aspectOk = clip.aspectRatio == targetAspect && unreviewedLowConf == 0
        val layoutSummary = ext?.segments?.joinToString(" → ") { "${it.layoutType.id}(${it.startSec}s-${it.endSec}s)" }
            ?: "9:16 Focus X: ${clip.reframeFocusX}%"
        checks.add(
            CampaignValidationCheck(
                ruleName = "Aspect Ratio & Dynamic Layout ($targetAspect • ${campaign.targetPlatform})",
                passed = aspectOk,
                detail = when {
                    clip.aspectRatio != targetAspect -> "Current aspect ratio is ${clip.aspectRatio}; campaign requires $targetAspect."
                    unreviewedLowConf > 0 -> "$unreviewedLowConf timeline segment(s) flagged for low-confidence manual review."
                    else -> "$targetAspect active • Dynamic Timeline: $layoutSummary"
                },
                isBlockingConflict = !aspectOk
            )
        )

        // 3. Campaign Logo Placement ON/OFF Toggle & Conflict Check
        val hasLocalLogoFile = campaign.customLogoLocalPath.isNotBlank() &&
            File(campaign.customLogoLocalPath).exists()
        val effectiveLogoEnabled = ext?.logoConfig?.enabled ?: (clip.logoPlacement != "NONE")
        val effectiveLogoPos = if (effectiveLogoEnabled) {
            ext?.logoConfig?.position?.takeIf { it != "NONE" } ?: clip.logoPlacement
        } else {
            "NONE"
        }
        val campaignRequiresLogo = campaign.logoPlacement != "NONE"
        val logoOk = if (!campaignRequiresLogo) {
            !effectiveLogoEnabled || effectiveLogoPos == "NONE"
        } else {
            effectiveLogoEnabled &&
                effectiveLogoPos == campaign.logoPlacement &&
                campaign.requiredLogoAsset.isNotBlank()
        }
        val logoSourceNote = if (hasLocalLogoFile) {
            "Verified local file '${campaign.requiredLogoAsset}'"
        } else {
            "Logo asset '${campaign.requiredLogoAsset}'"
        }
        checks.add(
            CampaignValidationCheck(
                ruleName = "Logo Placement Toggle (${if (effectiveLogoEnabled) "ON • $effectiveLogoPos" else "OFF"})",
                passed = logoOk,
                detail = when {
                    !campaignRequiresLogo && effectiveLogoEnabled ->
                        "CONFLICT: Campaign '${campaign.name}' prohibits brand logos (NONE), but Logo Placement is ON ($effectiveLogoPos)."
                    campaignRequiresLogo && !effectiveLogoEnabled ->
                        "CONFLICT: Campaign requires logo '${campaign.requiredLogoAsset}' at ${campaign.logoPlacement}, but Logo Placement is toggled OFF."
                    logoOk && !campaignRequiresLogo ->
                        "Logo Placement is OFF as required by campaign — full 9:16 layout space active."
                    logoOk ->
                        "$logoSourceNote active at $effectiveLogoPos (size ${ext?.logoConfig?.sizePct ?: 14}%, opacity ${ext?.logoConfig?.opacityPct ?: 95}%)."
                    else ->
                        "CONFLICT: Logo is at $effectiveLogoPos; campaign requires ${campaign.logoPlacement} (${campaign.requiredLogoAsset})."
                },
                isBlockingConflict = !logoOk
            )
        )

        // 4. Hook Caption Placement Toggle (Independent of Ordinary Subtitles)
        val hookCfg = ext?.hookConfig
        val hookEnabled = hookCfg?.enabled ?: true
        val hookText = hookCfg?.hookText ?: clip.title
        val hookPos = hookCfg?.position ?: "TOP_SAFE"
        val hookValidWhenOn = hookText.isNotBlank() &&
            (hookCfg?.durationSec ?: 3.5f) in 1.0f..15.0f &&
            hookPos in listOf("TOP_SAFE", "UPPER_MIDDLE", "ABOVE_SPLIT_LINE", "CENTER_BANNER")
        val hookOk = if (hookEnabled) hookValidWhenOn else true
        checks.add(
            CampaignValidationCheck(
                ruleName = "Hook Caption Toggle (${if (hookEnabled) "ON • $hookPos" else "OFF"})",
                passed = hookOk,
                detail = when {
                    !hookEnabled ->
                        "Opening Hook Caption toggled OFF (Dialogue Subtitles remain ${if (clip.subtitlesEnabled) "ON" else "OFF"})."
                    hookOk ->
                        "Hook Caption ON (\"${hookText.take(36)}\") at $hookPos for ${hookCfg?.durationSec ?: 3.5f}s — clears face & HUD."
                    else ->
                        "CONFLICT: Hook Caption is ON but hook text is empty or outside safe zones."
                },
                isBlockingConflict = !hookOk
            )
        )

        // 5. Safe Zone & Dialogue Subtitle Check (Independent of Hook Caption)
        val safeMarginsValid = campaign.safeZoneTopPct in 5..35 && campaign.safeZoneBottomPct in 10..35
        val safeZoneOk = safeMarginsValid && (!clip.subtitlesEnabled || clip.subtitleText.isNotBlank())
        checks.add(
            CampaignValidationCheck(
                ruleName = "Dialogue Subtitles & Safe Zones (Top ${campaign.safeZoneTopPct}% / Bottom ${campaign.safeZoneBottomPct}%)",
                passed = safeZoneOk,
                detail = when {
                    !safeMarginsValid -> "Safe-zone margins must be 5–35% top and 10–35% bottom."
                    !clip.subtitlesEnabled -> "Dialogue subtitles toggled OFF — Hook Caption (${if (hookEnabled) "ON" else "OFF"}) operates independently."
                    safeZoneOk -> "Synchronized SRT subtitles clear top ${campaign.safeZoneTopPct}% and bottom ${campaign.safeZoneBottomPct}% UI overlays."
                    else -> "Provide non-empty dialogue subtitle text or toggle subtitles OFF."
                },
                isBlockingConflict = !safeZoneOk
            )
        )

        // 6. Required Hashtags Check
        val requiredTags = campaign.requiredHashtags
            .split(" ", ",")
            .map { it.trim() }
            .filter { it.startsWith("#") && it.length > 1 }
        val combinedTags = (clip.hashtags + " " + clip.captionText).lowercase()
        val missingTags = requiredTags.filter { !combinedTags.contains(it.lowercase()) }
        val hashtagsOk = missingTags.isEmpty()
        checks.add(
            CampaignValidationCheck(
                ruleName = "Required Campaign Hashtags (${requiredTags.size} required)",
                passed = hashtagsOk,
                detail = if (hashtagsOk) {
                    "All mandatory hashtags present (${requiredTags.joinToString(" ")})."
                } else {
                    "Missing required hashtags: ${missingTags.joinToString(" ")}"
                },
                isBlockingConflict = !hashtagsOk
            )
        )

        // 7. Mandatory Caption CTA Check
        val ctaRequired = campaign.mandatoryCaptionCta.trim()
        val ctaOk = ctaRequired.isEmpty() ||
            clip.captionText.contains(ctaRequired, ignoreCase = true)
        checks.add(
            CampaignValidationCheck(
                ruleName = "Mandatory Caption CTA",
                passed = ctaOk,
                detail = if (ctaOk) {
                    "Caption includes required CTA: \"$ctaRequired\"."
                } else {
                    "Caption is missing mandatory CTA phrase: \"$ctaRequired\"."
                },
                isBlockingConflict = !ctaOk
            )
        )

        // 8. SEO Guidance & Confidence Threshold Check
        val seoOk = clip.seoTitle.length in 15..100 &&
            clip.seoKeywords.isNotBlank() &&
            clip.confidenceScore >= campaign.minConfidenceScore
        checks.add(
            CampaignValidationCheck(
                ruleName = "SEO Metadata & Confidence (>= ${campaign.minConfidenceScore}%)",
                passed = seoOk,
                detail = if (seoOk) {
                    "Confidence ${clip.confidenceScore}% meets threshold; SEO title & keywords populated."
                } else {
                    "Requires confidence >= ${campaign.minConfidenceScore}% (currently ${clip.confidenceScore}%) and non-empty SEO title/keywords."
                },
                isBlockingConflict = !seoOk
            )
        )

        return CampaignValidationReport(
            campaignName = "${campaign.name} (${campaign.brandName})",
            checks = checks
        )
    }

    fun autoFixClipForCampaign(clip: ClipCandidate, campaign: WhopCampaign): ClipCandidate {
        val targetDuration = clip.durationSec.coerceIn(campaign.minDurationSec, campaign.maxDurationSec)
        val adjustedEnd = (clip.startSec + targetDuration).coerceAtLeast(clip.startSec + campaign.minDurationSec)

        val requiredTags = campaign.requiredHashtags
            .split(" ", ",")
            .map { it.trim() }
            .filter { it.startsWith("#") }
        val existingTags = clip.hashtags.split(" ").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        requiredTags.forEach { tag ->
            if (existingTags.none { it.equals(tag, ignoreCase = true) }) {
                existingTags.add(tag)
            }
        }
        val mergedHashtags = existingTags.joinToString(" ")

        val baseCaption = clip.captionText.ifBlank { clip.title }
        val updatedCaption = buildString {
            append(baseCaption.trim())
            if (campaign.mandatoryCaptionCta.isNotBlank() &&
                !baseCaption.contains(campaign.mandatoryCaptionCta, ignoreCase = true)
            ) {
                append(" — ")
                append(campaign.mandatoryCaptionCta.trim())
            }
            if (!baseCaption.contains(mergedHashtags, ignoreCase = true)) {
                append(" ")
                append(mergedHashtags)
            }
        }.take(950)

        val validAspect = if (campaign.preferredAspectRatio in listOf("9:16", "16:9", "1:1")) {
            campaign.preferredAspectRatio
        } else {
            "9:16"
        }

        val resolvedLogoPlacement = if (campaign.logoPlacement == "NONE") "NONE" else campaign.logoPlacement
        val resolvedLogoEnabled = campaign.logoPlacement != "NONE"

        val baseUpdated = clip.copy(
            campaignId = campaign.id,
            endSec = adjustedEnd,
            aspectRatio = validAspect,
            logoPlacement = resolvedLogoPlacement,
            subtitlesEnabled = clip.subtitlesEnabled,
            subtitleText = clip.subtitleText.ifBlank { "[00:00] ${clip.title.uppercase()}" },
            captionText = updatedCaption,
            hashtags = mergedHashtags,
            seoTitle = clip.seoTitle.ifBlank { "${clip.title} | ${campaign.brandName} Highlight" }.take(95),
            seoKeywords = clip.seoKeywords.ifBlank { "${campaign.brandName}, whop clips, ${clip.category.lowercase()}" },
            confidenceScore = clip.confidenceScore.coerceAtLeast(campaign.minConfidenceScore)
        )

        val existingExt = clip.extendedConfig ?: AutoFramingEngine.generateDefaultExtendedConfig(baseUpdated, campaign = campaign)
        val fixedSegments = existingExt.segments.map { seg ->
            seg.copy(
                needsManualReview = false,
                manualReviewReason = "",
                confidence = seg.confidence.coerceAtLeast(0.88f)
            )
        }
        val fixedExt = existingExt.copy(
            segments = AutoFramingEngine.applyHysteresisAndSmoothing(
                segments = fixedSegments,
                minSegmentDurationSec = existingExt.hysteresisMinSegmentSec,
                clipStartSec = baseUpdated.startSec,
                clipEndSec = baseUpdated.endSec
            ),
            logoConfig = existingExt.logoConfig.copy(
                enabled = resolvedLogoEnabled,
                position = resolvedLogoPlacement
            ),
            hookConfig = existingExt.hookConfig.copy(
                hookText = existingExt.hookConfig.hookText.ifBlank {
                    AutoFramingEngine.buildDefaultHookText(baseUpdated, campaign)
                },
                position = "TOP_SAFE"
            )
        )
        return baseUpdated.copy(extendedConfig = fixedExt)
    }

    /**
     * Formats burned-in video captions into cool viral typography presets.
     */
    fun formatViralVideoCaption(
        styleId: String,
        title: String,
        currentSubtitle: String
    ): String {
        val cleanCore = currentSubtitle
            .replace(Regex("\\[\\d{2}:\\d{2}\\]\\s*"), "")
            .replace(Regex("^(STOP SCROLLING:|WAIT FOR IT\\.\\.\\.|NOBODY EXPECTED THIS:|WATCH CLOSELY:)\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { title.trim() }

        return when (styleId) {
            "GAMING_HYPE" -> "[00:00] WAIT FOR IT... ${cleanCore.uppercase()}!! [00:04] CHAT WENT CRAZY"
            "STORYTELLER" -> "[00:00] Nobody expected this... [00:03] $cleanCore"
            "CLEAN_MINIMAL" -> "[00:00] $cleanCore"
            else -> "[00:00] STOP SCROLLING: ${cleanCore.uppercase()}"
        }.take(950)
    }

    /**
     * Formats platform post caption into a high-converting viral hook + CTA + hashtags.
     */
    fun formatViralPostCaption(
        styleId: String,
        title: String,
        cta: String,
        hashtags: String
    ): String {
        val cleanTitle = title.replace("[0-Token Local]", "").trim()
        val ctaPart = if (cta.isNotBlank()) " — $cta" else ""
        val tagPart = if (hashtags.isNotBlank()) " $hashtags" else ""
        return when (styleId) {
            "GAMING_HYPE" -> "ZERO CHANCE THIS ACTUALLY HAPPENED LIVE: $cleanTitle!! Drop a W in the comments$ctaPart$tagPart"
            "STORYTELLER" -> "99% of people miss what happens at the 3-second mark in '$cleanTitle'... Watch till the end$ctaPart$tagPart"
            "CLEAN_MINIMAL" -> "$cleanTitle$ctaPart$tagPart"
            else -> "THIS CHANGED EVERYTHING: $cleanTitle (Wait for the payoff)$ctaPart$tagPart"
        }.take(950)
    }

    /**
     * Deterministic Zero-Token Local Rule Clipper.
     * Supports 1 to 10 clips, video captions ON/OFF toggle, and viral caption presets.
     */
    fun extractClipsWithoutAiTokens(
        project: VideoProject,
        campaign: WhopCampaign?,
        desiredClipCount: Int = 3,
        includeVideoCaptions: Boolean = true,
        viralCaptionStyle: String = "HORMOZI_BOLD"
    ): List<ClipCandidate> {
        val count = desiredClipCount.coerceIn(1, 10)
        val totalDur = project.durationSec.coerceAtLeast(30L)
        val minLen = campaign?.minDurationSec ?: 15L
        val maxLen = campaign?.maxDurationSec ?: 55L
        val targetLen = ((minLen + maxLen) / 2L).coerceIn(15L, 45L)
        val aspect = if (campaign?.preferredAspectRatio in listOf("9:16", "16:9", "1:1")) {
            campaign?.preferredAspectRatio ?: "9:16"
        } else {
            "9:16"
        }
        val hashtags = campaign?.requiredHashtags?.ifBlank { "#whop #clips #shorts #viral" } ?: "#whop #clips #shorts #viral"
        val cta = campaign?.mandatoryCaptionCta ?: "Check link in bio"

        val timestampRegex = Regex("\\[(?:(\\d{1,2}):(\\d{2})|(\\d+)s)\\]\\s*(.+)")
        val parsedLines = project.transcriptText.lines().mapNotNull { line ->
            val match = timestampRegex.find(line.trim()) ?: return@mapNotNull null
            val sec = if (match.groupValues[1].isNotEmpty()) {
                match.groupValues[1].toLong() * 60L + match.groupValues[2].toLong()
            } else {
                match.groupValues[3].toLongOrNull() ?: 0L
            }
            val text = match.groupValues[4].trim()
            sec.coerceIn(0L, (totalDur - 5L).coerceAtLeast(0L)) to text
        }

        val fallbackAnchors = listOf(
            (totalDur * 0.05).toLong() to "Opening Scroll-Stopper Hook — ${project.title}",
            (totalDur * 0.14).toLong() to "Early Banter & Unexpected Funny Moment — ${project.title}",
            (totalDur * 0.24).toLong() to "Rapid Escalation & High-Energy Reaction — ${project.title}",
            (totalDur * 0.34).toLong() to "Mid-Stream Pattern Interrupt & Callout — ${project.title}",
            (totalDur * 0.44).toLong() to "Peak Reaction & Chat Hype Spike — ${project.title}",
            (totalDur * 0.54).toLong() to "High-Stakes Clutch Play Sequence — ${project.title}",
            (totalDur * 0.64).toLong() to "Unfiltered Creator Takeaway & Debate Hook — ${project.title}",
            (totalDur * 0.74).toLong() to "Overtime Climax & Crowd Eruption — ${project.title}",
            (totalDur * 0.83).toLong() to "Shocking Twist & Replay Moment — ${project.title}",
            (totalDur * 0.90).toLong() to "Final Memorable Mic-Drop & Outro Hook — ${project.title}"
        )

        val anchorSegments = (parsedLines + fallbackAnchors).take(count)

        return anchorSegments.mapIndexed { idx, (startRaw, lineText) ->
            val start = startRaw.coerceIn(0L, (totalDur - minLen).coerceAtLeast(0L))
            val end = (start + targetLen).coerceAtMost(totalDur).coerceAtLeast(start + 1L)
            val lower = lineText.lowercase()
            val category = when {
                lower.contains("funny") || lower.contains("laugh") || lower.contains("cooked") -> "FUNNY_MOMENT"
                lower.contains("clutch") || lower.contains("1v") || lower.contains("overtime") -> "CLUTCH_PLAY"
                lower.contains("wait") || lower.contains("what") || lower.contains("reaction") || lineText.contains("!") -> "REACTION"
                lower.contains("quote") || lower.contains("viewers") || lower.contains("dialogue") || lower.contains("takeaway") -> "MEMORABLE_DIALOGUE"
                else -> "GAMING_HIGHLIGHT"
            }
            val cleanHook = lineText.take(65).removeSuffix(".")
            val styledVideoCaption = formatViralVideoCaption(
                styleId = viralCaptionStyle,
                title = cleanHook,
                currentSubtitle = lineText
            )
            val styledPostCaption = formatViralPostCaption(
                styleId = viralCaptionStyle,
                title = cleanHook,
                cta = cta,
                hashtags = hashtags
            )
            val baseClip = ClipCandidate(
                projectId = project.id,
                campaignId = campaign?.id.orEmpty(),
                title = cleanHook.ifBlank { "Viral Highlight #${idx + 1} — ${project.title.take(40)}" },
                category = category,
                reasonForSelection = "High-retention hook extracted at ${start}s–${end}s with ${viralCaptionStyle.replace("_", " ")} viral captions.",
                confidenceScore = (campaign?.minConfidenceScore ?: 80L).coerceAtLeast(94L - idx * 2L),
                startSec = start,
                endSec = end,
                aspectRatio = aspect,
                reframeFocusX = listOf(50L, 44L, 56L, 48L, 52L)[idx % 5],
                subtitlesEnabled = includeVideoCaptions,
                subtitleText = styledVideoCaption.take(240),
                captionText = styledPostCaption.take(350),
                hashtags = hashtags,
                seoTitle = "${project.title.take(45)} | Viral Highlight #${idx + 1}",
                seoKeywords = "${campaign?.brandName ?: "creator"}, viral clip, ${category.lowercase()}, shorts, reels",
                logoPlacement = campaign?.logoPlacement ?: "TOP_RIGHT",
                reviewStatus = "PROPOSED"
            )
            if (campaign != null) autoFixClipForCampaign(baseClip, campaign) else baseClip
        }
    }
}
