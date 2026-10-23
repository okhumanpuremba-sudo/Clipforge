package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.HighlightDetectionEngine
import com.example.data.local.LocalWhopCampaignStore
import com.example.data.model.AiProviderConfig
import com.example.data.model.ApiUsageLog
import com.example.data.model.CampaignPreset
import com.example.data.model.ClipCandidate
import com.example.data.model.ProviderVerificationState
import com.example.data.model.VideoProject
import com.example.data.model.WhopCampaign
import com.example.data.repository.ClipForgeRepository
import com.example.domain.ClipExportResult
import com.example.domain.UrlVideoIngestEngine
import com.example.domain.VideoProcessingPipeline
import com.example.domain.WhopRuleValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "ClipForgeVM"
private const val STOP_TIMEOUT_MS = 5000L

sealed interface DataStreamState<out T> {
    data object Loading : DataStreamState<Nothing>
    data class Success<T>(val data: T) : DataStreamState<T>
    data class Error(val message: String) : DataStreamState<Nothing>
}

data class StudioUiState(
    val selectedProjectId: String? = null,
    val selectedCampaignId: String? = null,
    val selectedClipId: String? = null,
    val desiredClipCount: Int = 3,
    val includeVideoCaptions: Boolean = true,
    val viralCaptionStyle: String = "HORMOZI_BOLD",
    val localCampaigns: List<WhopCampaign> = emptyList(),
    val campaignPresets: List<CampaignPreset> = emptyList(),
    val activePresetId: String? = null,
    val isIngestingUrl: Boolean = false,
    val isAnalyzingHighlights: Boolean = false,
    val pipelineProgress: Float = 0f,
    val pipelineStageLabel: String = "",
    val analysisStatusBanner: String? = null,
    val errorBanner: String? = null,
    val lastExportSummary: String? = null,
    val lastExportFfmpegCommand: String? = null,
    val lastExportSrtPreview: String? = null,
    val uploadedPreviewFrames: Map<String, List<Bitmap>> = emptyMap(),
    val singleClipPreviewFrame: Bitmap? = null,
    val dailyTokenSoftLimit: Long = 25_000L,
    val dailyRequestWarnThreshold: Int = 15,
    val maxRetryCount: Int = 2,
    val customWebhookUrl: String = "",
    val testingProviderId: String? = null,
    val providers: List<AiProviderConfig> = listOf(
        AiProviderConfig(
            id = "firebase_ai_primary",
            name = "Firebase AI Logic (Primary)",
            modelOrEndpoint = "gemini-3.8-flash",
            roleLabel = "PRIMARY SERVER-ATTESTED",
            costNotes = "Free Spark tier included; server-attested via App Check (zero client keys).",
            isBuiltInFirebase = true,
            verificationState = ProviderVerificationState.UNTESTED,
            lastTestMessage = "Tap 'Run Live Test' to verify App Check + Gemini 3.8 Flash."
        ),
        AiProviderConfig(
            id = "firebase_ai_backup_lite",
            name = "Firebase AI Logic Lite (Backup #1)",
            modelOrEndpoint = "gemini-3.5-flash-lite",
            roleLabel = "FAILOVER STANDBY",
            costNotes = "Free Spark tier; lower latency & higher quota headroom fallback.",
            isBuiltInFirebase = true,
            verificationState = ProviderVerificationState.UNTESTED,
            lastTestMessage = "UNTESTED — Never assumed working until live test succeeds."
        ),
        AiProviderConfig(
            id = "custom_remote_worker",
            name = "Custom Remote Video/LLM Worker (Backup #2)",
            modelOrEndpoint = "https://your-worker.example.com/health",
            roleLabel = "PLUGGABLE BACKEND",
            costNotes = "Self-hosted FFmpeg + Whisper/LLM worker; cost depends on your cloud host.",
            isBuiltInFirebase = false,
            verificationState = ProviderVerificationState.UNTESTED,
            lastTestMessage = "UNTESTED — Configure HTTPS URL and run live test before use."
        )
    )
)

class ClipForgeViewModel(
    private val repository: ClipForgeRepository,
    private val currentUserId: String,
    private val appContext: Context? = null
) : ViewModel() {

    private val aiEngine = HighlightDetectionEngine()

    private val _studioState = MutableStateFlow(
        StudioUiState(
            localCampaigns = appContext?.let {
                LocalWhopCampaignStore.loadLocalCampaigns(it, currentUserId)
            }.orEmpty(),
            campaignPresets = appContext?.let {
                LocalWhopCampaignStore.loadCampaignPresets(it, currentUserId)
            }.orEmpty()
        )
    )
    val studioState: StateFlow<StudioUiState> = _studioState.asStateFlow()

    val projectsState: StateFlow<DataStreamState<List<VideoProject>>> =
        repository.observeProjects(currentUserId)
            .map<List<VideoProject>, DataStreamState<List<VideoProject>>> { list ->
                if (_studioState.value.selectedProjectId == null && list.isNotEmpty()) {
                    _studioState.update { it.copy(selectedProjectId = list.first().id) }
                }
                DataStreamState.Success(list)
            }
            .catch { error ->
                Log.w(TAG, "Error observing projects", error)
                emit(DataStreamState.Error(error.message ?: "Failed to load projects"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = DataStreamState.Loading
            )

    val campaignsState: StateFlow<DataStreamState<List<WhopCampaign>>> =
        combine(
            repository.observeCampaigns(currentUserId).catch { emit(emptyList()) },
            _studioState.map { it.localCampaigns }
        ) { remoteList, _ ->
            val merged = if (appContext != null) {
                LocalWhopCampaignStore.mergeWithRemoteCampaigns(appContext, currentUserId, remoteList)
            } else {
                remoteList
            }
            if (_studioState.value.selectedCampaignId == null && merged.isNotEmpty()) {
                _studioState.update { it.copy(selectedCampaignId = merged.first().id) }
            }
            DataStreamState.Success(merged) as DataStreamState<List<WhopCampaign>>
        }
            .catch { error ->
                Log.w(TAG, "Error observing campaigns", error)
                emit(DataStreamState.Error(error.message ?: "Failed to load Whop campaigns"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = DataStreamState.Loading
            )

    val clipsState: StateFlow<DataStreamState<List<ClipCandidate>>> =
        repository.observeClips(currentUserId)
            .map<List<ClipCandidate>, DataStreamState<List<ClipCandidate>>> { list ->
                if (_studioState.value.selectedClipId == null && list.isNotEmpty()) {
                    _studioState.update { it.copy(selectedClipId = list.first().id) }
                }
                DataStreamState.Success(list)
            }
            .catch { error ->
                Log.w(TAG, "Error observing clips", error)
                emit(DataStreamState.Error(error.message ?: "Failed to load clips"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = DataStreamState.Loading
            )

    val apiLogsState: StateFlow<DataStreamState<List<ApiUsageLog>>> =
        repository.observeApiLogs(currentUserId)
            .map<List<ApiUsageLog>, DataStreamState<List<ApiUsageLog>>> { DataStreamState.Success(it) }
            .catch { error ->
                Log.w(TAG, "Error observing API logs", error)
                emit(DataStreamState.Error(error.message ?: "Failed to load API logs"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = DataStreamState.Loading
            )

    fun selectProject(projectId: String) {
        _studioState.update { it.copy(selectedProjectId = projectId, errorBanner = null) }
    }

    fun selectCampaign(campaignId: String) {
        _studioState.update { it.copy(selectedCampaignId = campaignId, errorBanner = null) }
    }

    fun setDesiredClipCount(count: Int) {
        _studioState.update { it.copy(desiredClipCount = count.coerceIn(1, 10)) }
    }

    fun setIncludeVideoCaptions(enabled: Boolean) {
        _studioState.update { it.copy(includeVideoCaptions = enabled) }
    }

    fun setViralCaptionStyle(styleId: String) {
        _studioState.update { it.copy(viralCaptionStyle = styleId) }
    }

    fun selectClip(clipId: String, context: Context? = null, sourceUri: String = "", startSec: Long = 0L) {
        _studioState.update {
            it.copy(
                selectedClipId = clipId,
                errorBanner = null,
                lastExportSummary = null
            )
        }
        if (context != null && sourceUri.startsWith("content://")) {
            viewModelScope.launch {
                val frame = VideoProcessingPipeline.extractSinglePreviewFrame(context, sourceUri, startSec)
                _studioState.update { it.copy(singleClipPreviewFrame = frame) }
            }
        }
    }

    fun ingestYouTubeOrTwitchUrl(
        rawUrl: String,
        userFocusNotes: String,
        campaign: WhopCampaign?,
        autoFindViralClips: Boolean,
        onNavigateToReview: () -> Unit = {}
    ) {
        if (_studioState.value.isIngestingUrl || _studioState.value.isAnalyzingHighlights) return
        viewModelScope.launch {
            _studioState.update {
                it.copy(
                    isIngestingUrl = true,
                    pipelineProgress = 0.14f,
                    pipelineStageLabel = "Step 1/4: Fetching stream metadata, duration & chapters...",
                    analysisStatusBanner = "Fetching live metadata, chapters & captions from link...",
                    errorBanner = null
                )
            }

            val ingestResult = UrlVideoIngestEngine.ingestStreamUrl(rawUrl, userFocusNotes)
            ingestResult.onSuccess { ingested ->
                _studioState.update {
                    it.copy(
                        pipelineProgress = 0.34f,
                        pipelineStageLabel = "Step 2/4: Extracted ${ingested.platform.label} captions & thumbnail frame..."
                    )
                }
                val newProject = VideoProject(
                    title = ingested.title.take(120),
                    sourceFileName = "${ingested.platform.label} • ${ingested.authorOrChannel} (${ingested.externalId})".take(200),
                    sourceUri = ingested.normalizedUrl.take(500),
                    durationSec = ingested.durationSec.coerceIn(15L, 36000L),
                    width = ingested.width,
                    height = ingested.height,
                    transcriptText = ingested.extractedTranscriptOrContext.take(15000),
                    campaignId = campaign?.id.orEmpty(),
                    status = "READY",
                    clipCount = 0L
                )

                val saveRes = repository.createProject(newProject)
                saveRes.onSuccess { newProjectId ->
                    val savedProject = newProject.copy(id = newProjectId)
                    _studioState.update { st ->
                        val updatedFrames = if (ingested.thumbnailBitmap != null) {
                            st.uploadedPreviewFrames + (newProjectId to listOf(ingested.thumbnailBitmap))
                        } else {
                            st.uploadedPreviewFrames
                        }
                        st.copy(
                            isIngestingUrl = false,
                            pipelineProgress = if (autoFindViralClips) 0.42f else 1f,
                            pipelineStageLabel = if (autoFindViralClips) {
                                "Step 3/4: Launching AI viral highlight detector..."
                            } else {
                                "Completed link import (100%)"
                            },
                            selectedProjectId = newProjectId,
                            uploadedPreviewFrames = updatedFrames,
                            singleClipPreviewFrame = ingested.thumbnailBitmap ?: st.singleClipPreviewFrame,
                            analysisStatusBanner = "Ingested ${ingested.platform.label} link '${ingested.title}' (${ingested.extractionSourceLabel})."
                        )
                    }

                    if (autoFindViralClips) {
                        runAiHighlightPipeline(
                            project = savedProject,
                            campaign = campaign,
                            onCompleteNavigateToReview = onNavigateToReview
                        )
                    }
                }.onFailure { err ->
                    _studioState.update {
                        it.copy(
                            isIngestingUrl = false,
                            pipelineProgress = 0f,
                            pipelineStageLabel = "",
                            analysisStatusBanner = null,
                            errorBanner = err.localizedMessage ?: "Failed to save link project."
                        )
                    }
                }
            }.onFailure { err ->
                _studioState.update {
                    it.copy(
                        isIngestingUrl = false,
                        pipelineProgress = 0f,
                        pipelineStageLabel = "",
                        analysisStatusBanner = null,
                        errorBanner = err.localizedMessage ?: "Could not ingest YouTube/Twitch URL."
                    )
                }
            }
        }
    }

    fun dismissBanners() {
        _studioState.update {
            it.copy(
                errorBanner = null,
                analysisStatusBanner = null,
                lastExportSummary = null
            )
        }
    }

    fun updateQuotaSettings(softTokenLimit: Long, requestWarnCount: Int, maxRetries: Int, customUrl: String) {
        _studioState.update { state ->
            val updatedProviders = state.providers.map { prov ->
                if (prov.id == "custom_remote_worker") {
                    prov.copy(
                        modelOrEndpoint = customUrl.ifBlank { "https://your-worker.example.com/health" },
                        verificationState = ProviderVerificationState.UNTESTED,
                        lastTestMessage = "Endpoint updated — run live test to verify."
                    )
                } else prov
            }
            state.copy(
                dailyTokenSoftLimit = softTokenLimit.coerceIn(1000L, 1_000_000L),
                dailyRequestWarnThreshold = requestWarnCount.coerceIn(1, 500),
                maxRetryCount = maxRetries.coerceIn(0, 5),
                customWebhookUrl = customUrl.trim(),
                providers = updatedProviders
            )
        }
    }

    fun uploadVideoFromUri(
        context: Context,
        uri: Uri,
        customTitle: String,
        transcriptNotes: String,
        campaignId: String
    ) {
        viewModelScope.launch {
            _studioState.update { it.copy(analysisStatusBanner = "Extracting real video metadata & keyframes on-device...", errorBanner = null) }
            try {
                val meta = VideoProcessingPipeline.inspectLocalVideo(context, uri)
                val projectTitle = customTitle.ifBlank {
                    meta.fileName.substringBeforeLast(".").replace("_", " ").replaceFirstChar { it.uppercase() }
                }
                val defaultTranscript = transcriptNotes.ifBlank {
                    buildDefaultTranscriptForDuration(projectTitle, meta.durationSec)
                }

                val result = repository.createProject(
                    VideoProject(
                        title = projectTitle,
                        sourceFileName = meta.fileName,
                        sourceUri = uri.toString(),
                        durationSec = meta.durationSec,
                        width = meta.width,
                        height = meta.height,
                        transcriptText = defaultTranscript,
                        campaignId = campaignId,
                        status = "READY",
                        clipCount = 0L
                    )
                )
                result.onSuccess { newId ->
                    _studioState.update { state ->
                        val updatedFrames = if (meta.sampleFrames.isNotEmpty()) {
                            state.uploadedPreviewFrames + (newId to meta.sampleFrames)
                        } else state.uploadedPreviewFrames
                        state.copy(
                            selectedProjectId = newId,
                            uploadedPreviewFrames = updatedFrames,
                            analysisStatusBanner = "Imported '${meta.fileName}' (${meta.width}x${meta.height}, ${meta.durationSec}s, ${meta.sampleFrames.size} keyframes extracted)."
                        )
                    }
                }.onFailure { err ->
                    _studioState.update { it.copy(errorBanner = err.localizedMessage ?: "Failed to save project", analysisStatusBanner = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _studioState.update { it.copy(errorBanner = e.localizedMessage ?: "Failed to inspect video", analysisStatusBanner = null) }
            }
        }
    }

    fun createSampleProjectWithTranscript(selectedCampaignId: String) {
        viewModelScope.launch {
            val sampleTranscript = """
                [00:05] Host: Welcome back to the Championship Finals! Look at that crazy rush on A-site!
                [00:18] Player 1 (Funny Moment): Bro I just threw my smoke grenade at a pigeon instead of heaven, I'm actually cooked!
                [00:44] Caster (Clutch Play): It's a 1v4 in overtime! He hits the flick on the first, dodges the flash, double headshot through the smoke—UNBELIEVABLE 1v4 CLUTCH!
                [01:22] Streamer (High Reaction): WAIT WHAT?! NO WAY WE JUST PULLED A 0.001% MYTHIC DROP LIVE ON STREAM! CHAT CLIP THAT RIGHT NOW!
                [02:05] Guest (Memorable Dialogue): If you want to win creator bounties consistently, stop editing for 16:9 monitors. 94% of viewers watch on a vertical phone screen with the first 2 seconds deciding everything.
                [02:48] Caster (Gaming Highlight): Pixel-perfect movement across the gap, zero health remaining, and locks in the world record pace!
            """.trimIndent()

            val result = repository.createProject(
                VideoProject(
                    title = "Pro Finals & Streamer Breakdown VOD",
                    sourceFileName = "finals_championship_1080p60.mp4",
                    sourceUri = "",
                    durationSec = 210L,
                    width = 1920L,
                    height = 1080L,
                    transcriptText = sampleTranscript,
                    campaignId = selectedCampaignId,
                    status = "READY",
                    clipCount = 0L
                )
            )
            result.onSuccess { id ->
                _studioState.update {
                    it.copy(
                        selectedProjectId = id,
                        analysisStatusBanner = "Created sample VOD project with timecoded transcript. Ready for AI Highlight Detection."
                    )
                }
            }.onFailure { err ->
                _studioState.update { it.copy(errorBanner = err.localizedMessage ?: "Failed to create sample project") }
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            if (_studioState.value.selectedProjectId == projectId) {
                _studioState.update { it.copy(selectedProjectId = null) }
            }
        }
    }

    fun saveWhopCampaign(campaign: WhopCampaign) {
        viewModelScope.launch {
            // 1. Always save locally on-device first so campaign rules & custom logo path are persisted immediately for validation
            val locallySaved = if (appContext != null) {
                LocalWhopCampaignStore.saveCampaignLocally(appContext, currentUserId, campaign)
            } else {
                campaign
            }

            // 2. Sync to Firestore and update local ID mapping if cloud succeeds
            val res = repository.createCampaign(locallySaved)
            res.onSuccess { cloudId ->
                val syncedCampaign = locallySaved.copy(id = cloudId, userId = currentUserId)
                val updatedLocalList = if (appContext != null) {
                    LocalWhopCampaignStore.deleteCampaignLocally(appContext, locallySaved.id)
                    LocalWhopCampaignStore.saveCampaignLocally(appContext, currentUserId, syncedCampaign)
                    LocalWhopCampaignStore.loadLocalCampaigns(appContext, currentUserId)
                } else {
                    listOf(syncedCampaign)
                }
                _studioState.update {
                    it.copy(
                        selectedCampaignId = cloudId,
                        localCampaigns = updatedLocalList,
                        analysisStatusBanner = "Saved Whop Campaign '${campaign.name}' locally (${campaign.preferredAspectRatio}) & synced to cloud."
                    )
                }
            }.onFailure { err ->
                val updatedLocalList = if (appContext != null) {
                    LocalWhopCampaignStore.loadLocalCampaigns(appContext, currentUserId)
                } else {
                    listOf(locallySaved)
                }
                _studioState.update {
                    it.copy(
                        selectedCampaignId = locallySaved.id,
                        localCampaigns = updatedLocalList,
                        analysisStatusBanner = "Saved Whop Campaign '${campaign.name}' locally on-device for validation (cloud sync pending: ${err.localizedMessage ?: "offline"})."
                    )
                }
            }
        }
    }

    fun createStarterWhopCampaign() {
        saveWhopCampaign(
            WhopCampaign(
                name = "Whop Viral Gaming & Creator Bounty",
                brandName = "Whop Creators Pro",
                payoutPer1kViews = "$2.00 / 1K views",
                targetPlatform = "TIKTOK",
                preferredAspectRatio = "9:16",
                minDurationSec = 15L,
                maxDurationSec = 55L,
                logoPlacement = "TOP_RIGHT",
                requiredLogoAsset = "whop_partner_badge.png",
                safeZoneTopPct = 12L,
                safeZoneBottomPct = 22L,
                requiredHashtags = "#whop #clipping #gaming",
                mandatoryCaptionCta = "Join the bounty via link in bio",
                seoGuidance = "Front-load game or reaction keyword in the first 40 characters",
                minConfidenceScore = 78L
            )
        )
    }

    fun deleteCampaign(campaignId: String) {
        viewModelScope.launch {
            if (appContext != null) {
                LocalWhopCampaignStore.deleteCampaignLocally(appContext, campaignId)
            }
            repository.deleteCampaign(campaignId)
            val refreshedLocal = appContext?.let {
                LocalWhopCampaignStore.loadLocalCampaigns(it, currentUserId)
            }.orEmpty()
            _studioState.update {
                it.copy(
                    selectedCampaignId = if (it.selectedCampaignId == campaignId) null else it.selectedCampaignId,
                    localCampaigns = refreshedLocal
                )
            }
        }
    }

    /**
     * Saves a reusable Campaign Preset (rules, hashtags, aspect ratio, caption style,
     * captions ON/OFF, and default clip count) locally and activates it immediately.
     */
    fun saveCampaignPreset(preset: CampaignPreset) {
        viewModelScope.launch {
            val updatedPresets = if (appContext != null) {
                LocalWhopCampaignStore.saveCampaignPreset(appContext, currentUserId, preset)
            } else {
                listOf(preset) + _studioState.value.campaignPresets.filterNot { it.id == preset.id }
            }
            val savedPreset = updatedPresets.firstOrNull {
                it.presetName.equals(preset.presetName, ignoreCase = true) || it.id == preset.id
            } ?: preset

            _studioState.update {
                it.copy(
                    campaignPresets = updatedPresets,
                    activePresetId = savedPreset.id,
                    viralCaptionStyle = savedPreset.viralCaptionStyle,
                    includeVideoCaptions = savedPreset.includeVideoCaptions,
                    desiredClipCount = savedPreset.defaultClipCount.coerceIn(1, 10),
                    analysisStatusBanner = "Saved Campaign Preset '${savedPreset.presetName}' (${savedPreset.preferredAspectRatio}, ${savedPreset.viralCaptionStyle.replace("_", " ")}, ${savedPreset.defaultClipCount} clips)."
                )
            }
            // Also persist the preset's campaign rules so new projects immediately validate against them
            saveWhopCampaign(savedPreset.toWhopCampaign())
        }
    }

    /**
     * Loads and applies a Campaign Preset in 1 tap: sets recurring Whop rules, hashtags,
     * aspect ratio, viral caption style, video captions ON/OFF, and clip count without manual entry.
     */
    fun applyCampaignPreset(preset: CampaignPreset) {
        viewModelScope.launch {
            _studioState.update {
                it.copy(
                    activePresetId = preset.id,
                    viralCaptionStyle = preset.viralCaptionStyle,
                    includeVideoCaptions = preset.includeVideoCaptions,
                    desiredClipCount = preset.defaultClipCount.coerceIn(1, 10),
                    analysisStatusBanner = "Loaded Preset '${preset.presetName}' — applied ${preset.preferredAspectRatio} rules, ${preset.viralCaptionStyle.replace("_", " ")} captions (${if (preset.includeVideoCaptions) "ON" else "OFF"}), ${preset.defaultClipCount} clips & hashtags (${preset.requiredHashtags})."
                )
            }
            saveWhopCampaign(preset.toWhopCampaign())
        }
    }

    fun deleteCampaignPreset(presetId: String) {
        viewModelScope.launch {
            val updated = if (appContext != null) {
                LocalWhopCampaignStore.deleteCampaignPreset(appContext, currentUserId, presetId)
            } else {
                _studioState.value.campaignPresets.filterNot { it.id == presetId }
            }
            _studioState.update {
                it.copy(
                    campaignPresets = updated,
                    activePresetId = if (it.activePresetId == presetId) null else it.activePresetId
                )
            }
        }
    }

    fun runAiHighlightPipeline(
        project: VideoProject,
        campaign: WhopCampaign?,
        onCompleteNavigateToReview: () -> Unit = {}
    ) {
        if (_studioState.value.isAnalyzingHighlights) return
        viewModelScope.launch {
            val stateSnap = _studioState.value
            val frames = stateSnap.uploadedPreviewFrames[project.id].orEmpty()
            val desiredCount = stateSnap.desiredClipCount.coerceIn(1, 10)
            val currentLogs = (apiLogsState.value as? DataStreamState.Success)?.data.orEmpty()
            val totalTokensUsed = currentLogs.sumOf { it.estimatedTokens }
            val isOverSoftLimit = totalTokensUsed >= stateSnap.dailyTokenSoftLimit

            _studioState.update {
                it.copy(
                    isAnalyzingHighlights = true,
                    pipelineProgress = 0.45f,
                    pipelineStageLabel = "Step 3/4: Analyzing transcript & keyframes for $desiredCount viral clip(s)...",
                    analysisStatusBanner = if (isOverSoftLimit) {
                        "Token limit reached ($totalTokensUsed/${stateSnap.dailyTokenSoftLimit}) — auto-routing $desiredCount clip(s) to Gemini 3.5 Flash-Lite Economy Mode..."
                    } else {
                        "Running Firebase AI Logic streaming pipeline for $desiredCount clip(s) (${stateSnap.viralCaptionStyle} captions: ${if (stateSnap.includeVideoCaptions) "ON" else "OFF"})..."
                    },
                    errorBanner = null
                )
            }
            repository.updateProjectStatus(project.id, "ANALYZING", project.clipCount, campaign?.id)

            try {
                val outcome = aiEngine.detectHighlights(
                    project = project,
                    campaign = campaign,
                    sampleFrames = frames,
                    desiredClipCount = desiredCount,
                    includeVideoCaptions = stateSnap.includeVideoCaptions,
                    viralCaptionStyle = stateSnap.viralCaptionStyle,
                    maxRetries = stateSnap.maxRetryCount.coerceAtLeast(1),
                    allowBackupModelFailover = true,
                    economyTokenMode = isOverSoftLimit,
                    onProgressUpdate = { progressText, progressFraction ->
                        _studioState.update { st ->
                            st.copy(
                                pipelineProgress = progressFraction.coerceIn(0.45f, 0.93f),
                                pipelineStageLabel = progressText,
                                analysisStatusBanner = progressText
                            )
                        }
                    }
                )

                _studioState.update {
                    it.copy(
                        pipelineProgress = 0.95f,
                        pipelineStageLabel = "Step 4/4: Saving ${outcome.clips.size} viral clips & SEO metadata..."
                    )
                }

                var firstClipId: String? = null
                outcome.clips.forEach { candidate ->
                    val saved = repository.createClip(candidate)
                    if (firstClipId == null && saved.isSuccess) {
                        firstClipId = saved.getOrNull()
                    }
                }

                repository.updateProjectStatus(
                    projectId = project.id,
                    status = "CLIPS_GENERATED",
                    clipCount = outcome.clips.size.toLong(),
                    campaignId = campaign?.id
                )

                repository.recordApiUsage(
                    ApiUsageLog(
                        providerId = outcome.providerId,
                        modelName = outcome.modelUsed,
                        operation = "detect_highlights (${outcome.clips.size} clips)",
                        latencyMs = outcome.latencyMs,
                        estimatedTokens = outcome.estimatedTokens,
                        retryCount = outcome.retryCount.toLong(),
                        success = true,
                        errorMessage = ""
                    )
                )

                _studioState.update { st ->
                    val updatedProviders = st.providers.map { prov ->
                        if (prov.id == outcome.providerId) {
                            prov.copy(
                                verificationState = ProviderVerificationState.VERIFIED_WORKING,
                                lastTestedLatencyMs = outcome.latencyMs,
                                lastTestMessage = "Active pipeline succeeded in ${outcome.latencyMs}ms."
                            )
                        } else prov
                    }
                    st.copy(
                        isAnalyzingHighlights = false,
                        pipelineProgress = 1.0f,
                        pipelineStageLabel = "Complete (100%) — ${outcome.clips.size} viral clip(s) ready!",
                        selectedClipId = firstClipId ?: st.selectedClipId,
                        providers = updatedProviders,
                        analysisStatusBanner = "Generated ${outcome.clips.size} timestamped clip(s) via ${outcome.modelUsed} (${outcome.latencyMs}ms, ~${outcome.estimatedTokens} tokens)."
                    )
                }
                onCompleteNavigateToReview()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cloud AI pipeline unavailable (${e.localizedMessage}); auto-failing over to On-Device Viral Clipper", e)
                repository.recordApiUsage(
                    ApiUsageLog(
                        providerId = "firebase_ai_primary",
                        modelName = "gemini-3.8-flash",
                        operation = "detect_highlights_auto_failover ($desiredCount clips)",
                        latencyMs = 0L,
                        estimatedTokens = 0L,
                        retryCount = stateSnap.maxRetryCount.toLong(),
                        success = false,
                        errorMessage = e.localizedMessage ?: "Switched to On-Device Viral Clipper"
                    )
                )

                _studioState.update {
                    it.copy(
                        pipelineProgress = 0.78f,
                        pipelineStageLabel = "Step 4/4: Extracting $desiredCount viral clip(s) via On-Device Rule Engine...",
                        analysisStatusBanner = "Auto-switched to On-Device Viral Clipper — extracting $desiredCount clip(s) with ${stateSnap.viralCaptionStyle.replace("_", " ")} captions...",
                        errorBanner = null
                    )
                }

                val fallbackClips = WhopRuleValidator.extractClipsWithoutAiTokens(
                    project = project,
                    campaign = campaign,
                    desiredClipCount = desiredCount,
                    includeVideoCaptions = stateSnap.includeVideoCaptions,
                    viralCaptionStyle = stateSnap.viralCaptionStyle
                )

                var firstFallbackClipId: String? = null
                fallbackClips.forEach { candidate ->
                    val saved = repository.createClip(candidate)
                    if (firstFallbackClipId == null && saved.isSuccess) {
                        firstFallbackClipId = saved.getOrNull()
                    }
                }

                repository.updateProjectStatus(
                    projectId = project.id,
                    status = "CLIPS_GENERATED",
                    clipCount = fallbackClips.size.toLong(),
                    campaignId = campaign?.id
                )

                _studioState.update { st ->
                    st.copy(
                        isAnalyzingHighlights = false,
                        pipelineProgress = 1.0f,
                        pipelineStageLabel = "Complete (100%) — ${fallbackClips.size} viral clip(s) ready!",
                        selectedClipId = firstFallbackClipId ?: st.selectedClipId,
                        errorBanner = null,
                        analysisStatusBanner = "Generated ${fallbackClips.size} viral clip(s) with ${stateSnap.viralCaptionStyle.replace("_", " ")} captions (${if (stateSnap.includeVideoCaptions) "ON" else "OFF"})."
                    )
                }
                onCompleteNavigateToReview()
            }
        }
    }

    /**
     * Deterministic 0-Token Local Clipper for when the user reaches their daily token limit
     * or wants instant local rule-based clipping without consuming any API quota.
     */
    fun runZeroTokenLocalClipper(
        project: VideoProject,
        campaign: WhopCampaign?,
        onCompleteNavigateToReview: () -> Unit = {}
    ) {
        if (_studioState.value.isAnalyzingHighlights) return
        viewModelScope.launch {
            val stateSnap = _studioState.value
            val desiredCount = stateSnap.desiredClipCount.coerceIn(1, 10)
            _studioState.update {
                it.copy(
                    isAnalyzingHighlights = true,
                    pipelineProgress = 0.35f,
                    pipelineStageLabel = "Scanning transcript timestamps ($desiredCount clips, 0 API tokens)...",
                    analysisStatusBanner = "Running 0-Token Local Rule Clipper ($desiredCount clips, 0 API tokens)...",
                    errorBanner = null
                )
            }
            delay(180L)
            _studioState.update {
                it.copy(
                    pipelineProgress = 0.72f,
                    pipelineStageLabel = "Applying ${stateSnap.viralCaptionStyle.replace("_", " ")} captions & Whop rules..."
                )
            }
            val generated = WhopRuleValidator.extractClipsWithoutAiTokens(
                project = project,
                campaign = campaign,
                desiredClipCount = desiredCount,
                includeVideoCaptions = stateSnap.includeVideoCaptions,
                viralCaptionStyle = stateSnap.viralCaptionStyle
            )
            var firstClipId: String? = null
            generated.forEach { candidate ->
                val saved = repository.createClip(candidate)
                if (firstClipId == null && saved.isSuccess) {
                    firstClipId = saved.getOrNull()
                }
            }
            repository.updateProjectStatus(
                projectId = project.id,
                status = "CLIPS_GENERATED",
                clipCount = generated.size.toLong(),
                campaignId = campaign?.id
            )
            _studioState.update { st ->
                st.copy(
                    isAnalyzingHighlights = false,
                    pipelineProgress = 1.0f,
                    pipelineStageLabel = "Complete (100%) — ${generated.size} local clip(s) ready!",
                    selectedClipId = firstClipId ?: st.selectedClipId,
                    analysisStatusBanner = "Generated ${generated.size} timestamped clip(s) via 0-Token Local Rule Engine (0 API tokens used)."
                )
            }
            onCompleteNavigateToReview()
        }
    }

    fun updateClipCandidate(updatedClip: ClipCandidate) {
        viewModelScope.launch {
            val res = repository.updateClip(updatedClip)
            res.onFailure { err ->
                _studioState.update { it.copy(errorBanner = err.localizedMessage ?: "Failed to update clip") }
            }
        }
    }

    fun autoFixClipForCampaign(clip: ClipCandidate, campaign: WhopCampaign) {
        val fixed = WhopRuleValidator.autoFixClipForCampaign(clip, campaign)
        updateClipCandidate(fixed)
        _studioState.update {
            it.copy(analysisStatusBanner = "Applied Whop Campaign compliance fixes to '${clip.title}'.")
        }
    }

    fun exportApprovedClip(
        context: Context,
        clip: ClipCandidate,
        campaign: WhopCampaign?,
        project: VideoProject?
    ) {
        viewModelScope.launch {
            val exportedClip = clip.copy(reviewStatus = "EXPORTED")
            repository.updateClip(exportedClip)
            if (project != null) {
                repository.updateProjectStatus(project.id, "EXPORTED", project.clipCount, campaign?.id)
            }

            val result = VideoProcessingPipeline.exportClipPackage(
                context = context,
                clip = exportedClip,
                campaign = campaign,
                sourceUriString = project?.sourceUri.orEmpty(),
                sourceFileName = project?.sourceFileName ?: "source_video.mp4"
            )

            when (result) {
                is ClipExportResult.NativeMp4AndManifestSuccess -> {
                    _studioState.update {
                        it.copy(
                            lastExportSummary = "EXPORTED WORKING MP4 CLIP (${result.mp4File.name}, ${result.mp4File.length() / 1024} KB) + SRT Subtitles (${result.srtFile.name}) + JSON Render Spec.",
                            lastExportFfmpegCommand = result.ffmpegCommand,
                            lastExportSrtPreview = result.srtFile.readText().take(500)
                        )
                    }
                }

                is ClipExportResult.ManifestOnlySuccess -> {
                    _studioState.update {
                        it.copy(
                            lastExportSummary = "EXPORTED SRT SUBTITLES (${result.srtFile.name}) + JSON RENDER SPEC (${result.manifestFile.name}). Note: ${result.reasonForNoMp4}",
                            lastExportFfmpegCommand = result.ffmpegCommand,
                            lastExportSrtPreview = result.srtFile.readText().take(500)
                        )
                    }
                }

                is ClipExportResult.Error -> {
                    _studioState.update { it.copy(errorBanner = "Export error: ${result.message}") }
                }
            }
        }
    }

    fun testProviderLive(providerId: String) {
        if (_studioState.value.testingProviderId != null) return
        viewModelScope.launch {
            val customUrl = _studioState.value.customWebhookUrl
            _studioState.update { it.copy(testingProviderId = providerId, errorBanner = null) }

            val outcome = aiEngine.testProviderConnectivity(providerId, customUrl)
            val modelLabel = _studioState.value.providers.firstOrNull { it.id == providerId }?.modelOrEndpoint ?: providerId

            repository.recordApiUsage(
                ApiUsageLog(
                    providerId = providerId,
                    modelName = modelLabel.take(80),
                    operation = "live_connectivity_test",
                    latencyMs = outcome.latencyMs,
                    estimatedTokens = if (outcome.success) 24L else 0L,
                    retryCount = 0L,
                    success = outcome.success,
                    errorMessage = if (outcome.success) "" else outcome.message
                )
            )

            _studioState.update { state ->
                val updated = state.providers.map { prov ->
                    if (prov.id == providerId) {
                        prov.copy(
                            verificationState = if (outcome.success) {
                                ProviderVerificationState.VERIFIED_WORKING
                            } else {
                                ProviderVerificationState.FAILED
                            },
                            lastTestedLatencyMs = outcome.latencyMs,
                            lastTestMessage = outcome.message
                        )
                    } else prov
                }
                state.copy(
                    testingProviderId = null,
                    providers = updated,
                    analysisStatusBanner = if (outcome.success) {
                        "Provider '$providerId' verified LIVE (${outcome.latencyMs}ms)."
                    } else null,
                    errorBanner = if (!outcome.success) {
                        "Provider '$providerId' test failed: ${outcome.message}"
                    } else null
                )
            }
        }
    }

    private fun buildDefaultTranscriptForDuration(title: String, durationSec: Long): String {
        val t1 = (durationSec * 0.12).toLong().coerceAtLeast(2L)
        val t2 = (durationSec * 0.35).toLong().coerceAtLeast(10L)
        val t3 = (durationSec * 0.62).toLong().coerceAtLeast(20L)
        val t4 = (durationSec * 0.82).toLong().coerceAtLeast(30L)
        return """
            [${t1}s] Opening reaction and high-energy intro hook for $title.
            [${t2}s] Unexpected funny moment and rapid squad banter during intense play.
            [${t3}s] High-pressure clutch sequence and crowd/chat reaction spike.
            [${t4}s] Memorable closing takeaway and call to action.
        """.trimIndent()
    }
}
