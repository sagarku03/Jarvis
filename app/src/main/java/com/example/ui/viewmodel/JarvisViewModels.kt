package com.example.ui.viewmodel

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.example.di.HiltViewModel
import com.example.di.Inject
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.model.AgentMessage
import com.example.domain.model.AppLanguage
import com.example.domain.model.OemVendorGuide
import com.example.domain.model.OrbState
import com.example.domain.model.PendingMemoryCandidate
import com.example.domain.model.PermissionItemStatus
import com.example.domain.model.PermissionKey
import com.example.domain.model.RoutineItem
import com.example.domain.model.RoutineTriggerType
import com.example.domain.model.SafetyConfirmationRequest
import com.example.domain.model.SafetyPolicyLevel
import com.example.domain.model.UserSettings
import com.example.domain.model.VoiceLatencyMetrics
import com.example.domain.model.VoiceProfile
import com.example.domain.repository.ActivityLogRecord
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRecord
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.safety.KillSwitch
import com.example.domain.safety.SafetyGate
import com.example.domain.tool.ToolDescriptor
import com.example.domain.tool.ToolRegistry
import com.example.domain.voice.AudioRecorder
import com.example.domain.voice.SpeechRecognizerWrapper
import com.example.domain.voice.TtsEngineWrapper
import com.example.domain.voice.WakeWordDetector
import com.example.services.JarvisAccessibilityService
import com.example.services.JarvisNotificationListener
import com.example.services.OverlayService
import com.example.services.RoutineTriggerReceiver
import com.example.services.VoiceForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val orchestrator: AgentOrchestrator,
    private val audioRecorder: AudioRecorder,
    private val speechRecognizer: SpeechRecognizerWrapper,
    private val ttsEngine: TtsEngineWrapper,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val settingsRepository: SettingsRepository,
    private val safetyGate: SafetyGate,
    private val killSwitch: KillSwitch,
    private val toolRegistry: ToolRegistry
) : ViewModel() {

    val orbState: StateFlow<OrbState> = orchestrator.orbState
    val lastResponseText: StateFlow<String> = orchestrator.lastResponseText
    val currentStepInfo: StateFlow<String?> = orchestrator.currentStepInfo
    val rmsDbLevel: StateFlow<Float> = audioRecorder.rmsDbLevel
    val noiseFloorDb: StateFlow<Float> = audioRecorder.noiseFloorDb
    val partialTranscript: StateFlow<String> = speechRecognizer.partialTranscript
    val speechError: StateFlow<String?> = speechRecognizer.errorState
    val latencyMetrics: StateFlow<VoiceLatencyMetrics> = ttsEngine.latencyMetrics
    val activeConfirmation: StateFlow<SafetyConfirmationRequest?> = safetyGate.activeConfirmationRequest
    val isKillSwitchHalted: StateFlow<Boolean> = killSwitch.isHalted
    val isOverlayShowing: StateFlow<Boolean> = OverlayService.isOverlayShowing
    val isMicActiveIndicator: StateFlow<Boolean> = combine(
        audioRecorder.isRecording,
        speechRecognizer.isListening,
        VoiceForegroundService.isMicActiveIndicator
    ) { rec, stt, fgs -> rec || stt || fgs }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val pendingMemoryCandidates: StateFlow<List<PendingMemoryCandidate>> =
        memoryRepository.pendingCandidates

    val userSettings: StateFlow<UserSettings> = settingsRepository.userSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettings()
    )

    val chatHistory: StateFlow<List<AgentMessage>> = chatRepository.observeMessages().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val availableTools: StateFlow<List<ToolDescriptor>> = userSettings.combine(killSwitch.isHalted) { settings, _ ->
        toolRegistry.getToolDescriptors(settings.fullControlModeEnabled)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = toolRegistry.getToolDescriptors()
    )

    private val _liveInputTranscript = MutableStateFlow("")
    val liveInputTranscript: StateFlow<String> = _liveInputTranscript.asStateFlow()

    fun updateDraftTranscript(text: String) {
        _liveInputTranscript.value = text
    }

    fun startPushToTalk() {
        val settings = userSettings.value
        killSwitch.resetAfterKill()
        orchestrator.setListeningState(true)

        audioRecorder.startRecording(
            enableSilenceEndpointing = true,
            silenceDurationMs = 1100L,
            onSilenceEndpoint = {
                stopPushToTalk()
            },
            onPcmFrame = { _ -> }
        )

        speechRecognizer.startListening(
            language = settings.language,
            onFinalResult = { recognizedText ->
                audioRecorder.stopRecording()
                _liveInputTranscript.value = recognizedText
                orchestrator.processUserUtterance(recognizedText)
            },
            onError = { friendlyError ->
                audioRecorder.stopRecording()
                orchestrator.setListeningState(false)
                ttsEngine.speak(
                    text = friendlyError,
                    language = settings.language,
                    speechRate = settings.voiceSpeed,
                    pitch = settings.voicePitch,
                    voiceProfile = settings.voiceProfile
                )
            }
        )
    }

    fun stopPushToTalk() {
        audioRecorder.stopRecording()
        speechRecognizer.stopListening()
        orchestrator.setListeningState(false)
        val pending = speechRecognizer.partialTranscript.value.trim()
            .ifEmpty { _liveInputTranscript.value.trim() }
        if (pending.isNotEmpty()) {
            orchestrator.processUserUtterance(pending)
        }
    }

    fun submitCommand(commandText: String) {
        val trimmed = commandText.trim()
        if (trimmed.isEmpty()) return
        _liveInputTranscript.value = trimmed
        speechRecognizer.clearTranscript()
        audioRecorder.stopRecording()
        orchestrator.processUserUtterance(trimmed)
    }

    fun bargeIn() {
        orchestrator.bargeIn()
    }

    fun triggerEmergencyKillSwitch() {
        killSwitch.triggerKillSwitch("Emergency KillSwitch pressed on Home HUD")
    }

    fun resolveSafetyGate(requestId: String, approved: Boolean) {
        safetyGate.resolveConfirmation(requestId, approved)
    }

    fun approveMemoryCandidate(candidateId: String) {
        viewModelScope.launch {
            memoryRepository.approveMemoryCandidate(candidateId)
        }
    }

    fun dismissMemoryCandidate(candidateId: String) {
        memoryRepository.dismissMemoryCandidate(candidateId)
    }
}

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _permissionsList = MutableStateFlow<List<PermissionItemStatus>>(emptyList())
    val permissionsList: StateFlow<List<PermissionItemStatus>> = _permissionsList.asStateFlow()

    val userSettings: StateFlow<UserSettings> = settingsRepository.userSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettings()
    )

    val oemVendorGuides: List<OemVendorGuide> = listOf(
        OemVendorGuide(
            vendorName = "Samsung",
            osName = "One UI 6 / 7",
            stepsEn = "Settings → Apps → JARVIS → Battery → Select 'Unrestricted'. Also remove JARVIS from 'Deep sleeping apps'.",
            stepsHi = "Settings → Apps → JARVIS → Battery में जाकर 'Unrestricted' चुनें और 'Deep sleeping apps' से हटाएं।"
        ),
        OemVendorGuide(
            vendorName = "Xiaomi / Redmi / POCO",
            osName = "HyperOS & MIUI 14",
            stepsEn = "Settings → Apps → Manage Apps → JARVIS → Turn ON 'Autostart' and set Battery Saver to 'No restrictions'.",
            stepsHi = "Settings → Manage Apps → JARVIS में 'Autostart' चालू करें और Battery Saver को 'No restrictions' पर सेट करें।"
        ),
        OemVendorGuide(
            vendorName = "Oppo / OnePlus",
            osName = "ColorOS / OxygenOS 14+",
            stepsEn = "Settings → Battery → More settings → Optimize battery use → JARVIS → 'Don't optimize' & enable 'Allow background activity'.",
            stepsHi = "Settings → Battery → Optimize battery use → JARVIS को 'Don't optimize' करें और 'Allow background activity' चालू करें।"
        ),
        OemVendorGuide(
            vendorName = "Realme",
            osName = "Realme UI 5.0",
            stepsEn = "Settings → App Management → App List → JARVIS → Battery Usage → Enable 'Allow background activity' & 'Allow auto-launch'.",
            stepsHi = "Settings → App List → JARVIS → Battery Usage में 'Allow background activity' और 'Allow auto-launch' चालू करें।"
        )
    )

    init {
        refreshPermissionsStatus()
    }

    fun refreshPermissionsStatus() {
        val hasAudio = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val hasOverlay = Settings.canDrawOverlays(appContext)

        val enabledAccessibility = runCatching {
            Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        }.getOrDefault("")
        val hasAccessibility = JarvisAccessibilityService.isConnected.value ||
            enabledAccessibility.contains(appContext.packageName, ignoreCase = true)

        val enabledNotificationListeners = runCatching {
            Settings.Secure.getString(
                appContext.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
        }.getOrDefault("")
        val hasNotifListener = JarvisNotificationListener.isListenerConnected.value ||
            enabledNotificationListeners.contains(appContext.packageName, ignoreCase = true)

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val hasBatteryExemption = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false

        val hasCallPhone = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val hasReadContacts = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        _permissionsList.value = listOf(
            PermissionItemStatus(
                key = PermissionKey.RECORD_AUDIO,
                titleEn = "Microphone (16kHz Voice Input)",
                titleHi = "माइक्रोफ़ोन (16kHz वॉइस इनपुट)",
                rationaleEn = "Required so JARVIS can hear your Hindi/Hinglish/English voice commands. Raw audio is never stored or logged.",
                rationaleHi = "आपकी हिन्दी/हिंग्लिश/अंग्रेज़ी आवाज़ सुनने के लिए आवश्यक। आपकी कच्ची ऑडियो कभी भी सेव या लॉग नहीं की जाती।",
                isGranted = hasAudio
            ),
            PermissionItemStatus(
                key = PermissionKey.POST_NOTIFICATIONS,
                titleEn = "Foreground Service Notifications",
                titleHi = "फ़ोरग्राउंड सर्विस नोटिफिकेशन",
                rationaleEn = "Displays persistent status when Voice Core is active and provides a 1-tap KillSwitch in the notification shade.",
                rationaleHi = "वॉइस सर्विस चालू होने पर स्थिति दिखाने और नोटिफिकेशन से तुरंत किल-स्विच दबाने के लिए।",
                isGranted = hasNotifications
            ),
            PermissionItemStatus(
                key = PermissionKey.READ_CONTACTS,
                titleEn = "Contacts Lookup (Verified Call & Message)",
                titleHi = "कॉन्टैक्ट्स पहचान (सुरक्षित कॉल और संदेश)",
                rationaleEn = "Resolves spoken contact names to real phone numbers so JARVIS never dials or texts unverified numbers.",
                rationaleHi = "बोले गए नामों से सही फ़ोन नंबर खोजने के लिए ताकि JARVIS कभी गलत नंबर पर कॉल या मैसेज न करे।",
                isGranted = hasReadContacts
            ),
            PermissionItemStatus(
                key = PermissionKey.SYSTEM_ALERT_WINDOW,
                titleEn = "Floating Orb Overlay (Display Over Apps)",
                titleHi = "फ्लोटिंग ऑर्ब ओवरले (अन्य ऐप्स के ऊपर)",
                rationaleEn = "Shows the floating JARVIS status orb with an emergency red STOP (KillSwitch) button while controlling other apps.",
                rationaleHi = "अन्य ऐप्स चलाते समय स्क्रीन पर फ्लोटिंग ऑर्ब और आपातकालीन STOP (KillSwitch) बटन दिखाने के लिए।",
                isGranted = hasOverlay
            ),
            PermissionItemStatus(
                key = PermissionKey.ACCESSIBILITY_SERVICE,
                titleEn = "Accessibility Control (FULL_CONTROL_MODE)",
                titleHi = "एक्सेसिबिलिटी नियंत्रण (पूर्ण नियंत्रण मोड)",
                rationaleEn = "Allows ScreenReader & ActionExecutor in Phase A builds. Skips password fields, blocks UPI/banking apps, and supports Volume-Down double-press KillSwitch.",
                rationaleHi = "स्क्रीन पढ़ने और आपकी अनुमति से टैप/स्क्रॉल करने के लिए। पासवर्ड फ़ील्ड और बैंकिंग ऐप्स पर स्वतः ब्लॉक रहता है।",
                isGranted = hasAccessibility,
                isGatedByFullControl = !BuildConfig.FULL_CONTROL_MODE || BuildConfig.STORE_SAFE_MODE
            ),
            PermissionItemStatus(
                key = PermissionKey.NOTIFICATION_LISTENER,
                titleEn = "Notification Listener Access",
                titleHi = "नोटिफिकेशन रीडर अनुमति",
                rationaleEn = "Enables JARVIS to summarize incoming messages and send confirmed quick-replies (OTPs are never read).",
                rationaleHi = "आने वाले संदेशों का सारांश पढ़ने और आपकी पुष्टि से जवाब भेजने के लिए (OTP कभी नहीं पढ़े जाते)।",
                isGranted = hasNotifListener
            ),
            PermissionItemStatus(
                key = PermissionKey.BATTERY_OPTIMIZATION,
                titleEn = "Unrestricted Battery (Wake-Word Reliability)",
                titleHi = "बैटरी ऑप्टिमाइज़ेशन छूट",
                rationaleEn = "Prevents Samsung/Xiaomi/Oppo/Realme battery killers from stopping the 'Hey Jarvis' wake-word service.",
                rationaleHi = "बैकग्राउंड में 'Hey Jarvis' वॉइस सर्विस को बंद होने से बचाने के लिए।",
                isGranted = hasBatteryExemption
            ),
            PermissionItemStatus(
                key = PermissionKey.CALL_PHONE,
                titleEn = "Direct Phone Call Permission",
                titleHi = "सीधे फ़ोन कॉल की अनुमति",
                rationaleEn = "Allows the call_contact tool to place calls after you approve the HIGH-risk SafetyGate dialog.",
                rationaleHi = "SafetyGate में आपकी स्पष्ट अनुमति के बाद सीधे कॉल लगाने के लिए।",
                isGranted = hasCallPhone
            )
        )
    }

    fun markOnboardingComplete() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
        }
    }
}

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _exportedJson = MutableStateFlow<String?>(null)
    val exportedJson: StateFlow<String?> = _exportedJson.asStateFlow()

    val pendingCandidates: StateFlow<List<PendingMemoryCandidate>> =
        memoryRepository.pendingCandidates

    val memories: StateFlow<List<MemoryRecord>> = combine(
        memoryRepository.observeMemories(),
        _searchQuery
    ) { all, query ->
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            all
        } else {
            all.filter {
                it.category.lowercase().contains(q) ||
                    it.keyLabel.lowercase().contains(q) ||
                    it.contentValue.lowercase().contains(q)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun saveMemory(id: Long = 0L, category: String, keyLabel: String, contentValue: String) {
        if (keyLabel.isBlank() || contentValue.isBlank()) return
        viewModelScope.launch {
            memoryRepository.upsertMemory(
                id = id,
                category = category,
                keyLabel = keyLabel,
                contentValue = contentValue
            )
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id)
        }
    }

    fun approveCandidate(candidateId: String) {
        viewModelScope.launch {
            memoryRepository.approveMemoryCandidate(candidateId)
        }
    }

    fun dismissCandidate(candidateId: String) {
        memoryRepository.dismissMemoryCandidate(candidateId)
    }

    fun exportVaultToJson() {
        viewModelScope.launch {
            _exportedJson.value = memoryRepository.exportMemoriesAsJson()
        }
    }

    fun clearExportedJsonPreview() {
        _exportedJson.value = null
    }
}

@HiltViewModel
class RoutinesViewModel @Inject constructor(
    private val appContext: Context,
    private val routineRepository: RoutineRepository,
    private val settingsRepository: SettingsRepository,
    private val orchestrator: AgentOrchestrator
) : ViewModel() {

    val routines: StateFlow<List<RoutineItem>> = routineRepository.observeRoutines().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val settings: StateFlow<UserSettings> = settingsRepository.userSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettings()
    )

    fun saveRoutine(
        id: Long = 0L,
        title: String,
        triggerType: RoutineTriggerType,
        triggerValue: String,
        actionsPrompt: String
    ) {
        if (title.isBlank() || actionsPrompt.isBlank() || !triggerType.isSupported) return
        viewModelScope.launch {
            routineRepository.upsertRoutine(
                id = id,
                title = title,
                triggerType = triggerType,
                triggerValue = triggerValue,
                actionsPrompt = actionsPrompt,
                enabled = true
            )
            if (triggerType == RoutineTriggerType.TIME_DAILY) {
                RoutineTriggerReceiver.scheduleDailyRoutineAlarm(appContext, id, triggerValue)
            }
        }
    }

    fun toggleRoutineEnabled(routine: RoutineItem, enabled: Boolean) {
        viewModelScope.launch {
            routineRepository.setRoutineEnabled(routine.id, enabled)
            if (enabled && routine.triggerType == RoutineTriggerType.TIME_DAILY) {
                RoutineTriggerReceiver.scheduleDailyRoutineAlarm(appContext, routine.id, routine.triggerValue)
            }
        }
    }

    fun runRoutineNow(routine: RoutineItem) {
        viewModelScope.launch {
            routineRepository.markRoutineExecuted(routine.id)
            orchestrator.processUserUtterance(routine.actionsPrompt)
        }
    }

    fun deleteRoutine(id: Long) {
        viewModelScope.launch {
            routineRepository.deleteRoutine(id)
        }
    }

    fun setProactiveSuggestionsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setProactiveSuggestionsEnabled(enabled)
        }
    }

    fun setQuietHours(start: String, end: String) {
        viewModelScope.launch {
            settingsRepository.setQuietHours(start, end)
        }
    }
}

@HiltViewModel
class ActivityLogViewModel @Inject constructor(
    private val activityLogRepository: ActivityLogRepository
) : ViewModel() {

    val logs: StateFlow<List<ActivityLogRecord>> = activityLogRepository.observeActivityLogs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun clearLogs() {
        viewModelScope.launch {
            activityLogRepository.clearAllLogs()
        }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val routineRepository: RoutineRepository,
    private val activityLogRepository: ActivityLogRepository,
    private val wakeWordDetector: WakeWordDetector,
    private val killSwitch: KillSwitch
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.userSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettings()
    )

    val isVoiceServiceRunning: StateFlow<Boolean> = VoiceForegroundService.isRunning

    val isWakeWordConfigured: Boolean
        get() = wakeWordDetector.isWakeWordConfigured

    val wakeWordStatusMessage: String
        get() = wakeWordDetector.statusMessage

    private val _exportedDataJson = MutableStateFlow<String?>(null)
    val exportedDataJson: StateFlow<String?> = _exportedDataJson.asStateFlow()

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

    fun setVoiceSpeed(speed: Float) {
        viewModelScope.launch {
            settingsRepository.setVoiceSpeed(speed)
        }
    }

    fun setVoicePitch(pitch: Float) {
        viewModelScope.launch {
            settingsRepository.setVoicePitch(pitch)
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWakeWordEnabled(enabled && wakeWordDetector.isWakeWordConfigured)
        }
    }

    fun setWakeWordSensitivity(sensitivity: Float) {
        viewModelScope.launch {
            settingsRepository.setWakeWordSensitivity(sensitivity)
            wakeWordDetector.setSensitivity(sensitivity)
        }
    }

    fun setWakeWordPaused(paused: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWakeWordPaused(paused)
            if (paused) {
                wakeWordDetector.pauseMonitoring()
            } else {
                wakeWordDetector.resumeMonitoring {}
            }
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoStartOnBoot(enabled)
        }
    }

    fun setSafetyPolicyLevel(level: SafetyPolicyLevel) {
        viewModelScope.launch {
            settingsRepository.setSafetyPolicyLevel(level)
        }
    }

    fun setFullControlMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setFullControlModeEnabled(enabled)
        }
    }

    fun setSimulateSecureScreen(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSimulateSecureScreen(enabled)
        }
    }

    fun setAnalyticsOptIn(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAnalyticsOptIn(enabled)
        }
    }

    fun setShowLatencyDebugPanel(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowLatencyDebugPanel(show)
        }
    }

    fun exportAllUserData() {
        viewModelScope.launch {
            val memoriesJson = memoryRepository.exportMemoriesAsJson()
            val routines = routineRepository.getAllRoutinesSnapshot()
            val routinesJson = routines.joinToString(",") { r ->
                """{"id":${r.id},"title":"${r.title.replace("\"", "\\\"")}","trigger":"${r.triggerType.id}","value":"${r.triggerValue}"}"""
            }
            _exportedDataJson.value = """{"memories":$memoriesJson,"routines":[$routinesJson]}"""
        }
    }

    fun clearExportedDataPreview() {
        _exportedDataJson.value = null
    }

    fun wipeAllUserData(onComplete: () -> Unit) {
        viewModelScope.launch {
            killSwitch.triggerKillSwitch("User wiped all local data")
            chatRepository.clearAllMessages()
            memoryRepository.clearAllMemories()
            routineRepository.clearAllRoutines()
            activityLogRepository.clearAllLogs()
            settingsRepository.resetAllSettings()
            authRepository?.signOut()
            onComplete()
        }
    }

    var authRepository: com.example.domain.repository.AuthRepository? = null

    fun signInWithEmail(email: String, password: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val repo = authRepository
            if (repo == null) {
                onResult(false, "Firebase Auth repository is not configured.")
                return@launch
            }
            when (val res = repo.signInWithEmail(email, password)) {
                is com.example.domain.model.AppResult.Success -> onResult(true, "Signed in as ${res.data.email ?: res.data.uid}")
                is com.example.domain.model.AppResult.Error -> onResult(false, res.messageEn)
                else -> Unit
            }
        }
    }

    fun signUpWithEmail(email: String, password: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val repo = authRepository
            if (repo == null) {
                onResult(false, "Firebase Auth repository is not configured.")
                return@launch
            }
            when (val res = repo.signUpWithEmail(email, password)) {
                is com.example.domain.model.AppResult.Success -> onResult(true, "Account created for ${res.data.email ?: res.data.uid}")
                is com.example.domain.model.AppResult.Error -> onResult(false, res.messageEn)
                else -> Unit
            }
        }
    }

    fun sendPasswordReset(email: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val repo = authRepository
            if (repo == null) {
                onResult(false, "Firebase Auth repository is not configured.")
                return@launch
            }
            when (val res = repo.sendPasswordReset(email)) {
                is com.example.domain.model.AppResult.Success -> onResult(true, "Password reset link sent to $email")
                is com.example.domain.model.AppResult.Error -> onResult(false, res.messageEn)
                else -> Unit
            }
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
                orchestrator = container.agentOrchestrator,
                audioRecorder = container.audioRecorder,
                speechRecognizer = container.speechRecognizer,
                ttsEngine = container.ttsEngine,
                chatRepository = container.chatRepository,
                memoryRepository = container.memoryRepository,
                settingsRepository = container.settingsRepository,
                safetyGate = container.safetyGate,
                killSwitch = container.killSwitch,
                toolRegistry = container.toolRegistry
            ) as T

            modelClass.isAssignableFrom(PermissionsViewModel::class.java) -> PermissionsViewModel(
                appContext = appContext,
                settingsRepository = container.settingsRepository
            ) as T

            modelClass.isAssignableFrom(MemoryViewModel::class.java) -> MemoryViewModel(
                memoryRepository = container.memoryRepository
            ) as T

            modelClass.isAssignableFrom(RoutinesViewModel::class.java) -> RoutinesViewModel(
                appContext = appContext,
                routineRepository = container.routineRepository,
                settingsRepository = container.settingsRepository,
                orchestrator = container.agentOrchestrator
            ) as T

            modelClass.isAssignableFrom(ActivityLogViewModel::class.java) -> ActivityLogViewModel(
                activityLogRepository = container.activityLogRepository
            ) as T

            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
                settingsRepository = container.settingsRepository,
                chatRepository = container.chatRepository,
                memoryRepository = container.memoryRepository,
                routineRepository = container.routineRepository,
                activityLogRepository = container.activityLogRepository,
                wakeWordDetector = container.wakeWordDetector,
                killSwitch = container.killSwitch
            ).apply {
                authRepository = container.authRepository
            } as T

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
