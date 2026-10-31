package com.example.ui.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.di.AppContainer
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AppLanguage
import com.example.domain.model.AssistantOrbState
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.ConfirmationRequest
import com.example.domain.model.LatencyMetrics
import com.example.domain.model.MemoryFact
import com.example.domain.model.OemBatteryGuide
import com.example.domain.model.PermissionItemStatus
import com.example.domain.model.RoutineItem
import com.example.domain.model.RoutineTriggerType
import com.example.domain.model.UserSettings
import com.example.domain.model.VoiceDiagnosticsSnapshot
import com.example.domain.model.VoiceProfile
import com.example.domain.model.VoiceSessionState
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.safety.KillSwitch
import com.example.domain.safety.SafetyGate
import com.example.services.JarvisAccessibilityService
import com.example.services.JarvisNotificationListener
import com.example.services.OverlayService
import com.example.services.RoutineTriggerReceiver
import com.example.services.VoiceForegroundService
import com.example.services.voice.SpeechRecognizerWrapper
import com.example.services.voice.TtsEngineWrapper
import com.example.services.voice.VoiceSessionManager
import com.example.services.voice.WakeWordEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val agentOrchestrator: AgentOrchestrator,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val safetyGate: SafetyGate,
    private val killSwitch: KillSwitch,
    private val voiceSessionManager: VoiceSessionManager,
    private val speechRecognizer: SpeechRecognizerWrapper,
    private val ttsEngine: TtsEngineWrapper,
    private val wakeWordEngine: WakeWordEngine
) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = chatRepository.messagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<UserSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val sessionState: StateFlow<VoiceSessionState> = voiceSessionManager.state

    val orbState: StateFlow<AssistantOrbState> = combine(
        voiceSessionManager.state,
        agentOrchestrator.orbState,
        killSwitch.isKilled
    ) { vState, agentState, isKilled ->
        when {
            isKilled -> AssistantOrbState.PAUSED_KILLSWITCH
            vState == VoiceSessionState.LISTENING -> AssistantOrbState.LISTENING
            vState == VoiceSessionState.SPEAKING -> AssistantOrbState.SPEAKING
            vState == VoiceSessionState.THINKING || agentState == AssistantOrbState.THINKING ->
                AssistantOrbState.THINKING
            else -> AssistantOrbState.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssistantOrbState.IDLE)

    val lastResponseText: StateFlow<String> = agentOrchestrator.lastResponseText
    val pendingConfirmation: StateFlow<ConfirmationRequest?> = safetyGate.pendingConfirmation
    val isKilled: StateFlow<Boolean> = killSwitch.isKilled

    /**
     * Driven directly by SpeechRecognizer.onRmsChanged(rmsdB) instead of a second AudioRecord client.
     */
    val rmsEnergyLevel: StateFlow<Float> = speechRecognizer.rmsDbLevel
    val partialTranscript: StateFlow<String> = speechRecognizer.partialTranscript
    val isWakeWordMonitoring: StateFlow<Boolean> = wakeWordEngine.isMonitoring
    val isWakeWordPaused: StateFlow<Boolean> = wakeWordEngine.isPaused
    val isOfflineVoiceActive: StateFlow<Boolean> = ttsEngine.isUsingOfflineFallback

    private val _inputDraft = MutableStateFlow("")
    val inputDraft: StateFlow<String> = _inputDraft.asStateFlow()

    private val _sttStatusBanner = MutableStateFlow<String?>(null)
    val sttStatusBanner: StateFlow<String?> = _sttStatusBanner.asStateFlow()

    val latencyMetrics: StateFlow<LatencyMetrics> = combine(
        speechRecognizer.lastSttLatencyMs,
        agentOrchestrator.lastAgentStepLatencyMs,
        ttsEngine.firstAudioLatencyMs,
        ttsEngine.isUsingOfflineFallback
    ) { stt, agent, tts, isOffline ->
        LatencyMetrics(
            sttLatencyMs = stt,
            agentStepLatencyMs = agent,
            ttsFirstAudioLatencyMs = tts,
            endToFirstAudioMs = stt + agent + tts,
            usingStreamingBackend = !isOffline && BuildConfig.AI_VOICE
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LatencyMetrics())

    fun updateDraft(text: String) {
        _inputDraft.value = text
    }

    fun dismissSttBanner() {
        _sttStatusBanner.value = null
    }

    fun sendTextCommand(textOverride: String? = null) {
        val text = (textOverride ?: _inputDraft.value).trim()
        if (text.isEmpty()) return
        if (textOverride == null) {
            _inputDraft.value = ""
        }
        _sttStatusBanner.value = null

        viewModelScope.launch {
            voiceSessionManager.enterThinking()
            val reply = agentOrchestrator.handleUserCommand(text)
            val currentSettings = settings.value
            if (!killSwitch.isKilled.value) {
                val wantsFollowUp = reply.trimEnd().endsWith("?")
                voiceSessionManager.speakReply(
                    rawText = reply,
                    language = currentSettings.language,
                    voiceProfile = currentSettings.voiceProfile,
                    speed = currentSettings.speechSpeed,
                    followUpListening = wantsFollowUp,
                    onFollowUpReady = {
                        if (!killSwitch.isKilled.value) {
                            startPushToTalk()
                        }
                    }
                )
            } else {
                voiceSessionManager.transitionToIdle()
            }
        }
    }

    fun togglePushToTalk() {
        when (voiceSessionManager.state.value) {
            VoiceSessionState.LISTENING -> {
                voiceSessionManager.transitionToIdle()
            }
            VoiceSessionState.SPEAKING -> {
                // Barge-in: tapping orb while SPEAKING stops TTS immediately and opens mic
                _sttStatusBanner.value = null
                val lang = settings.value.language
                voiceSessionManager.bargeInAndListen(
                    language = lang,
                    onFinalTranscript = { transcript ->
                        if (transcript.isNotBlank()) {
                            sendTextCommand(transcript)
                        }
                    },
                    onError = { friendlyError ->
                        // Never speak error text via TTS; display inline message only
                        _sttStatusBanner.value = friendlyError
                    }
                )
            }
            VoiceSessionState.THINKING -> {
                cancelCurrentTurn()
            }
            VoiceSessionState.IDLE -> {
                startPushToTalk()
            }
        }
    }

    fun startPushToTalk() {
        if (killSwitch.isKilled.value) return
        _sttStatusBanner.value = null
        val lang = settings.value.language

        // Single mic owner: VoiceSessionManager starts ONLY SpeechRecognizer (no PcmAudioRecorder).
        voiceSessionManager.startListening(
            language = lang,
            onFinalTranscript = { transcript ->
                if (transcript.isNotBlank()) {
                    sendTextCommand(transcript)
                }
            },
            onError = { friendlyError ->
                // Never speak error text aloud; show inline status message only
                _sttStatusBanner.value = friendlyError
            }
        )
    }

    fun cancelCurrentTurn() {
        voiceSessionManager.stopAll()
        agentOrchestrator.cancelActiveTurn()
    }

    fun resolveConfirmation(approved: Boolean, selectedPhoneOverride: String? = null) {
        safetyGate.resolveUserApproval(approved, selectedPhoneOverride)
    }

    fun triggerKillSwitch() {
        killSwitch.trigger("User pressed emergency KillSwitch")
        voiceSessionManager.stopAll()
        agentOrchestrator.cancelActiveTurn()
        viewModelScope.launch {
            settingsRepository.setKillSwitchActive(true)
        }
    }

    fun resetKillSwitch() {
        killSwitch.reset()
        agentOrchestrator.setOrbState(AssistantOrbState.IDLE)
        voiceSessionManager.transitionToIdle()
        viewModelScope.launch {
            settingsRepository.setKillSwitchActive(false)
        }
    }

    override fun onCleared() {
        voiceSessionManager.stopAll()
        super.onCleared()
    }
}

class ActivityLogViewModel(
    private val activityLogRepository: ActivityLogRepository
) : ViewModel() {

    val logs: StateFlow<List<ActivityLogEntry>> = activityLogRepository.logsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearLogs() {
        viewModelScope.launch {
            activityLogRepository.clearAllLogs()
        }
    }
}

class MemoryViewModel(
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val memories: StateFlow<List<MemoryFact>> = _searchQuery
        .flatMapLatest { q ->
            if (q.isBlank()) memoryRepository.memoriesFlow
            else memoryRepository.searchMemories(q.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun saveMemory(category: String, key: String, value: String) {
        if (key.isBlank() || value.isBlank()) return
        viewModelScope.launch {
            memoryRepository.upsertMemory(category, key, value)
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id)
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            memoryRepository.clearAllMemories()
        }
    }
}

class RoutinesViewModel(
    private val appContext: Context,
    private val routineRepository: RoutineRepository,
    private val agentOrchestrator: AgentOrchestrator
) : ViewModel() {

    val routines: StateFlow<List<RoutineItem>> = routineRepository.routinesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val oemGuides: List<OemBatteryGuide> = listOf(
        OemBatteryGuide(
            oemName = "Samsung (One UI)",
            stepsEn = "Settings -> Apps -> JARVIS -> Battery -> Select 'Unrestricted'. Disable 'Put unused apps to sleep'."
        ),
        OemBatteryGuide(
            oemName = "Xiaomi / Redmi / POCO (MIUI & HyperOS)",
            stepsEn = "Settings -> Apps -> Manage Apps -> JARVIS -> Enable 'Autostart' and set Battery Saver to 'No restrictions'."
        ),
        OemBatteryGuide(
            oemName = "OnePlus / Oppo (OxygenOS & ColorOS)",
            stepsEn = "Settings -> Battery -> More Settings -> Optimize battery use -> JARVIS -> Select 'Don't optimize' and enable 'Allow background activity'."
        ),
        OemBatteryGuide(
            oemName = "Realme / Vivo",
            stepsEn = "Settings -> App Management -> App List -> JARVIS -> Battery Usage -> Enable 'Allow background activity' and 'Allow auto-launch'."
        )
    )

    fun saveRoutine(
        title: String,
        triggerType: RoutineTriggerType,
        triggerValue: String,
        actionsCommaSeparated: String
    ) {
        if (title.isBlank() || triggerValue.isBlank() || actionsCommaSeparated.isBlank()) return
        val actions = actionsCommaSeparated.split(",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (actions.isEmpty()) return

        viewModelScope.launch {
            val savedId = routineRepository.upsertRoutine(
                RoutineItem(
                    title = title.trim(),
                    triggerType = triggerType,
                    triggerValue = triggerValue.trim(),
                    actionsJson = actions,
                    enabled = true
                )
            )
            if (triggerType == RoutineTriggerType.TIME_DAILY && savedId > 0L) {
                RoutineTriggerReceiver.scheduleDailyRoutineAlarm(
                    context = appContext,
                    routineId = savedId,
                    timeHhMm = triggerValue.trim()
                )
            }
        }
    }

    fun toggleRoutine(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            routineRepository.toggleRoutine(id, enabled)
        }
    }

    fun deleteRoutine(id: Long) {
        viewModelScope.launch {
            routineRepository.deleteRoutine(id)
        }
    }

    fun executeRoutineNow(routine: RoutineItem) {
        viewModelScope.launch {
            routineRepository.markRoutineRun(routine.id, System.currentTimeMillis())
            for (cmd in routine.actionsJson) {
                agentOrchestrator.handleUserCommand(cmd)
            }
        }
    }
}

class PermissionsViewModel(
    private val appContext: Context
) : ViewModel() {

    private val _permissionsList = MutableStateFlow<List<PermissionItemStatus>>(emptyList())
    val permissionsList: StateFlow<List<PermissionItemStatus>> = _permissionsList.asStateFlow()

    val oemGuides: List<OemBatteryGuide> = listOf(
        OemBatteryGuide(
            oemName = "Samsung (One UI)",
            stepsEn = "Settings -> Apps -> JARVIS -> Battery -> Select 'Unrestricted' so background wake-word detection is not killed."
        ),
        OemBatteryGuide(
            oemName = "Xiaomi / Redmi / POCO (HyperOS / MIUI)",
            stepsEn = "Settings -> Apps -> Manage Apps -> JARVIS -> Enable 'Autostart' and set Battery Saver to 'No restrictions'."
        ),
        OemBatteryGuide(
            oemName = "OnePlus / Oppo / Realme (OxygenOS / ColorOS)",
            stepsEn = "Settings -> Battery -> App Battery Management -> JARVIS -> Enable 'Allow foreground & background activity'."
        ),
        OemBatteryGuide(
            oemName = "Vivo (FuntouchOS)",
            stepsEn = "Settings -> Battery -> Background Power Consumption Management -> Set JARVIS to 'Don't restrict background power usage'."
        )
    )

    init {
        refreshStatuses()
    }

    fun refreshStatuses() {
        val hasMic = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasContacts = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val hasCallPhone = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val hasPostNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val hasOverlay = Settings.canDrawOverlays(appContext)
        val hasAccessibility = JarvisAccessibilityService.isConnected.value
        val hasNotifListener = JarvisNotificationListener.isListenerConnected()

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBatteryOpt = pm?.isIgnoringBatteryOptimizations(appContext.packageName) == true

        _permissionsList.value = listOf(
            PermissionItemStatus(
                id = "RECORD_AUDIO",
                titleEn = "Microphone (Voice Input)",
                rationaleEn = "Required to hear your spoken commands and 'Hey Jarvis' wake word. Raw audio is never stored on disk.",
                isGranted = hasMic
            ),
            PermissionItemStatus(
                id = "POST_NOTIFICATIONS",
                titleEn = "Foreground Service Notification",
                rationaleEn = "Shows a persistent microphone status indicator and 1-tap STOP kill-switch while voice service is active.",
                isGranted = hasPostNotif
            ),
            PermissionItemStatus(
                id = "READ_CONTACTS",
                titleEn = "Contacts Lookup (Safe Calling & Messaging)",
                rationaleEn = "Resolves spoken contact names to real phone numbers so JARVIS never guesses or dials wrong numbers.",
                isGranted = hasContacts
            ),
            PermissionItemStatus(
                id = "SYSTEM_ALERT_WINDOW",
                titleEn = "Draw Over Other Apps (Floating Orb)",
                rationaleEn = "Displays the draggable JARVIS orb and emergency STOP button over other apps.",
                isGranted = hasOverlay
            ),
            PermissionItemStatus(
                id = "ACCESSIBILITY_SERVICE",
                titleEn = "Accessibility Service (Full Control Mode)",
                rationaleEn = "Reads screen layout and executes user-approved taps and scrolls. Automatically blocked on password fields and banking/UPI apps.",
                isGranted = hasAccessibility,
                isOptionalInStoreSafeMode = true
            ),
            PermissionItemStatus(
                id = "NOTIFICATION_LISTENER",
                titleEn = "Notification Listener Access",
                rationaleEn = "Summarizes incoming notifications and sends confirmed inline replies (4-8 digit OTP codes are automatically redacted).",
                isGranted = hasNotifListener
            ),
            PermissionItemStatus(
                id = "BATTERY_OPTIMIZATION",
                titleEn = "Unrestricted Battery Optimization",
                rationaleEn = "Prevents OEM battery savers from stopping the 'Hey Jarvis' background wake-word service.",
                isGranted = isIgnoringBatteryOpt
            ),
            PermissionItemStatus(
                id = "CALL_PHONE",
                titleEn = "Direct Phone Call Permission",
                rationaleEn = "Places confirmed phone calls after explicit user approval in the SafetyGate dialog.",
                isGranted = hasCallPhone
            )
        )
    }
}

class SettingsViewModel(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val wakeWordEngine: WakeWordEngine,
    private val voiceSessionManager: VoiceSessionManager,
    private val speechRecognizer: SpeechRecognizerWrapper,
    private val ttsEngine: TtsEngineWrapper,
    private val agentOrchestrator: AgentOrchestrator,
    private val killSwitch: KillSwitch
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val isWakeWordConfigured: Boolean
        get() = wakeWordEngine.isWakeWordConfigured

    val isOfflineVoiceActive: StateFlow<Boolean> = ttsEngine.isUsingOfflineFallback

    val voiceDiagnostics: StateFlow<VoiceDiagnosticsSnapshot> = combine(
        combine(
            voiceSessionManager.state,
            speechRecognizer.lastErrorCode,
            speechRecognizer.lastErrorMessage
        ) { state, errCode, errMsg -> Triple(state, errCode, errMsg) },
        combine(
            ttsEngine.isUsingOfflineFallback,
            settings,
            speechRecognizer.lastSttLatencyMs,
            agentOrchestrator.lastAgentStepLatencyMs,
            ttsEngine.firstAudioLatencyMs
        ) { isOffline, userSettings, sttLat, agentLat, ttsLat ->
            VoiceDiagnosticsSnapshot(
                isOfflineTtsActive = isOffline,
                activeVoiceId = userSettings.voiceProfile.id,
                sttLatencyMs = sttLat,
                agentLatencyMs = agentLat,
                ttsLatencyMs = ttsLat
            )
        }
    ) { (state, errCode, errMsg), partial ->
        partial.copy(
            sessionState = state,
            lastSttErrorCode = errCode,
            lastSttErrorMessage = errMsg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VoiceDiagnosticsSnapshot())

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.setLanguage(language)
        }
    }

    fun setVoiceProfile(profile: VoiceProfile) {
        viewModelScope.launch {
            settingsRepository.setVoiceProfile(profile)
        }
    }

    fun setSpeechSpeed(speed: Float) {
        viewModelScope.launch {
            settingsRepository.setSpeechSpeed(speed.coerceIn(0.8f, 1.2f))
        }
    }

    fun previewVoice(samplePhrase: String) {
        val current = settings.value
        voiceSessionManager.speakReply(
            rawText = samplePhrase,
            language = current.language,
            voiceProfile = current.voiceProfile,
            speed = current.speechSpeed,
            followUpListening = false
        )
    }

    fun setWakeWordSensitivity(value: Float) {
        wakeWordEngine.setSensitivity(value)
        viewModelScope.launch {
            settingsRepository.setWakeWordSensitivity(value)
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        if (enabled && !wakeWordEngine.isWakeWordConfigured) {
            return
        }
        viewModelScope.launch {
            settingsRepository.setWakeWordEnabled(enabled)
            if (enabled) {
                VoiceForegroundService.startService(appContext)
            } else {
                VoiceForegroundService.stopService(appContext)
            }
        }
    }

    fun setWakeWordPaused(paused: Boolean) {
        wakeWordEngine.setPaused(paused)
        viewModelScope.launch {
            settingsRepository.setWakeWordPaused(paused)
        }
    }

    fun setBootAutoStartEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBootAutoStartEnabled(enabled)
        }
    }

    fun setFloatingOrbEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setFloatingOrbEnabled(enabled)
            if (enabled) {
                OverlayService.startIfPermitted(appContext)
            } else {
                OverlayService.stop(appContext)
            }
        }
    }

    fun setConfirmationLevel(level: ConfirmationLevel) {
        viewModelScope.launch {
            settingsRepository.setConfirmationLevel(level)
        }
    }

    fun setKillSwitch(active: Boolean) {
        if (active) {
            killSwitch.trigger("Toggled in Settings")
            voiceSessionManager.stopAll()
            VoiceForegroundService.stopService(appContext)
            OverlayService.stop(appContext)
        } else {
            killSwitch.reset()
        }
        viewModelScope.launch {
            settingsRepository.setKillSwitchActive(active)
        }
    }
}

class JarvisViewModelFactory(
    private val appContext: Context,
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(
                agentOrchestrator = container.agentOrchestrator,
                chatRepository = container.chatRepository,
                settingsRepository = container.settingsRepository,
                safetyGate = container.safetyGate,
                killSwitch = container.killSwitch,
                voiceSessionManager = container.voiceSessionManager,
                speechRecognizer = container.speechRecognizerWrapper,
                ttsEngine = container.ttsEngineWrapper,
                wakeWordEngine = container.wakeWordEngine
            ) as T

            modelClass.isAssignableFrom(ActivityLogViewModel::class.java) -> ActivityLogViewModel(
                activityLogRepository = container.activityLogRepository
            ) as T

            modelClass.isAssignableFrom(MemoryViewModel::class.java) -> MemoryViewModel(
                memoryRepository = container.memoryRepository
            ) as T

            modelClass.isAssignableFrom(RoutinesViewModel::class.java) -> RoutinesViewModel(
                appContext = appContext,
                routineRepository = container.routineRepository,
                agentOrchestrator = container.agentOrchestrator
            ) as T

            modelClass.isAssignableFrom(PermissionsViewModel::class.java) -> PermissionsViewModel(
                appContext = appContext
            ) as T

            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
                appContext = appContext,
                settingsRepository = container.settingsRepository,
                wakeWordEngine = container.wakeWordEngine,
                voiceSessionManager = container.voiceSessionManager,
                speechRecognizer = container.speechRecognizerWrapper,
                ttsEngine = container.ttsEngineWrapper,
                agentOrchestrator = container.agentOrchestrator,
                killSwitch = container.killSwitch
            ) as T

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
