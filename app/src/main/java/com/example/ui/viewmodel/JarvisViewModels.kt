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
import com.example.data.local.ApiKeyStore
import com.example.data.local.DiagnosticsLogger
import com.example.data.remote.GeminiClient
import com.example.data.remote.KeyVerificationResult
import com.example.di.AppContainer
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.model.ActivityLogEntry
import com.example.domain.model.AppLanguage
import com.example.domain.model.AssistantOrbState
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.ConfirmationRequest
import com.example.domain.model.ConversationItem
import com.example.domain.model.DeviceVoiceOption
import com.example.domain.model.LatencyMetrics
import com.example.domain.model.MemoryCategory
import com.example.domain.model.MemoryFact
import com.example.domain.model.MemorySource
import com.example.domain.model.OemBatteryGuide
import com.example.domain.model.PermissionItemStatus
import com.example.domain.model.RoutineItem
import com.example.domain.model.RoutineTriggerType
import com.example.domain.model.UserFacingError
import com.example.domain.model.UserSettings
import com.example.domain.model.VoiceDiagnosticsSnapshot
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
import com.example.tools.MemorySafetyFilter
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactNicknameDraft(
    val nickname: String = "",
    val fullName: String = ""
)

class SetupViewModel(
    private val appContext: Context,
    private val apiKeyStore: ApiKeyStore,
    private val geminiClient: GeminiClient,
    private val settingsRepository: SettingsRepository,
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val _keyPresenceVersion = MutableStateFlow(0)

    val isGateUnlocked: StateFlow<Boolean> = combine(
        settingsRepository.settingsFlow,
        _keyPresenceVersion
    ) { settings, _ ->
        settings.setupComplete && apiKeyStore.exists()
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        apiKeyStore.exists()
    )

    private val _currentStep = MutableStateFlow(1)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _stepError = MutableStateFlow<String?>(null)
    val stepError: StateFlow<String?> = _stepError.asStateFlow()

    private val _apiKeyInput = MutableStateFlow("")
    val apiKeyInput: StateFlow<String> = _apiKeyInput.asStateFlow()

    private val _isKeyVisible = MutableStateFlow(false)
    val isKeyVisible: StateFlow<Boolean> = _isKeyVisible.asStateFlow()

    private val _isVerifying = MutableStateFlow(false)
    val isVerifying: StateFlow<Boolean> = _isVerifying.asStateFlow()

    private val _isKeyVerified = MutableStateFlow(apiKeyStore.exists())
    val isKeyVerified: StateFlow<Boolean> = _isKeyVerified.asStateFlow()

    private val _verificationMessage = MutableStateFlow<String?>(null)
    val verificationMessage: StateFlow<String?> = _verificationMessage.asStateFlow()

    private val _verificationSuccess = MutableStateFlow(apiKeyStore.exists())
    val verificationSuccess: StateFlow<Boolean> = _verificationSuccess.asStateFlow()

    private val _availableModels = MutableStateFlow(
        listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")
    )
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _selectedModelId = MutableStateFlow("gemini-2.5-flash")
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _userNameInput = MutableStateFlow("")
    val userNameInput: StateFlow<String> = _userNameInput.asStateFlow()

    private val _userCityInput = MutableStateFlow("")
    val userCityInput: StateFlow<String> = _userCityInput.asStateFlow()

    private val _contactNicknames = MutableStateFlow(
        listOf(
            ContactNicknameDraft(),
            ContactNicknameDraft(),
            ContactNicknameDraft()
        )
    )
    val contactNicknames: StateFlow<List<ContactNicknameDraft>> = _contactNicknames.asStateFlow()

    private val _isMicGranted = MutableStateFlow(checkMicPermission())
    val isMicGranted: StateFlow<Boolean> = _isMicGranted.asStateFlow()

    private val _privacyAccepted = MutableStateFlow(false)
    val privacyAccepted: StateFlow<Boolean> = _privacyAccepted.asStateFlow()

    val canUnlockJarvis: StateFlow<Boolean> = combine(
        _isKeyVerified,
        _userNameInput,
        _isMicGranted,
        _privacyAccepted
    ) { keyVerified, name, micGranted, privacyChecked ->
        (keyVerified || apiKeyStore.exists()) &&
            name.trim().isNotEmpty() &&
            micGranted &&
            privacyChecked
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun refreshPermissionAndKeyState() {
        _isMicGranted.value = checkMicPermission()
        val hasKey = apiKeyStore.exists()
        _isKeyVerified.value = hasKey
        _keyPresenceVersion.value += 1
    }

    private fun checkMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun updateApiKeyInput(value: String) {
        _apiKeyInput.value = value
        _stepError.value = null
        if (_isKeyVerified.value && value.trim() != apiKeyStore.get()) {
            _isKeyVerified.value = false
            _verificationSuccess.value = false
            _verificationMessage.value = null
        }
    }

    fun onPasteKey(pasted: String) {
        val trimmed = pasted.trim()
        _apiKeyInput.value = trimmed
        _stepError.value = null
        if (trimmed.startsWith("AIza") || trimmed.length >= 30) {
            verifyApiKey()
        }
    }

    fun toggleKeyVisibility() {
        _isKeyVisible.value = !_isKeyVisible.value
    }

    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        viewModelScope.launch {
            settingsRepository.setSelectedModelId(modelId)
        }
    }

    fun updateUserName(name: String) {
        _userNameInput.value = name
        _stepError.value = null
    }

    fun updateUserCity(city: String) {
        _userCityInput.value = city
    }

    fun updateContactNickname(index: Int, nickname: String, fullName: String) {
        if (index !in 0..2) return
        val updated = _contactNicknames.value.toMutableList()
        updated[index] = ContactNicknameDraft(nickname = nickname, fullName = fullName)
        _contactNicknames.value = updated
    }

    fun setPrivacyAccepted(accepted: Boolean) {
        _privacyAccepted.value = accepted
        _stepError.value = null
    }

    fun goToStep(step: Int) {
        if (step in 1..3) {
            _currentStep.value = step
            _stepError.value = null
        }
    }

    fun onNextClicked() {
        when (_currentStep.value) {
            1 -> {
                if (!_isKeyVerified.value && !apiKeyStore.exists()) {
                    _stepError.value = "Verify your key to continue."
                    return
                }
                _stepError.value = null
                _currentStep.value = 2
            }
            2 -> {
                if (_userNameInput.value.trim().isEmpty()) {
                    _stepError.value = "Please enter your name."
                    return
                }
                _stepError.value = null
                _currentStep.value = 3
            }
            3 -> {
                if (!_isMicGranted.value) {
                    _stepError.value = "Microphone permission is required."
                    return
                }
                if (!_privacyAccepted.value) {
                    _stepError.value = "Please agree to the privacy statement."
                    return
                }
                _stepError.value = null
                completeSetup()
            }
        }
    }

    fun onBackClicked() {
        if (_currentStep.value > 1) {
            _currentStep.value -= 1
            _stepError.value = null
        }
    }

    fun verifyApiKey() {
        val candidate = _apiKeyInput.value.trim()
        if (candidate.isEmpty()) {
            _verificationSuccess.value = false
            _verificationMessage.value = "Invalid API key"
            return
        }
        _isVerifying.value = true
        _verificationMessage.value = null
        _stepError.value = null
        viewModelScope.launch {
            when (val result = geminiClient.verifyKeyAndListModels(candidate)) {
                is KeyVerificationResult.Valid -> {
                    apiKeyStore.save(candidate)
                    val models = result.availableModels
                    val defaultModel = result.defaultModelId
                    _availableModels.value = models
                    _selectedModelId.value = defaultModel
                    settingsRepository.setAvailableModels(models)
                    settingsRepository.setSelectedModelId(defaultModel)
                    _isKeyVerified.value = true
                    _verificationSuccess.value = true
                    _verificationMessage.value = "Connected! Available models: ${models.size}"
                    _keyPresenceVersion.value += 1
                }
                is KeyVerificationResult.Invalid -> {
                    _isKeyVerified.value = false
                    _verificationSuccess.value = false
                    _verificationMessage.value = result.message
                }
                is KeyVerificationResult.RateLimited -> {
                    _isKeyVerified.value = false
                    _verificationSuccess.value = false
                    _verificationMessage.value = result.message
                }
                is KeyVerificationResult.NetworkError -> {
                    _isKeyVerified.value = false
                    _verificationSuccess.value = false
                    _verificationMessage.value = result.message
                }
            }
            _isVerifying.value = false
        }
    }

    fun completeSetup() {
        val name = _userNameInput.value.trim()
        if (name.isEmpty() || !apiKeyStore.exists() || !checkMicPermission() || !_privacyAccepted.value) {
            return
        }
        viewModelScope.launch {
            memoryRepository.upsertMemory(
                category = MemoryCategory.PROFILE.name,
                key = "user_name",
                value = name,
                source = MemorySource.SETUP_WIZARD.name,
                confidence = 1.0f,
                pinned = true
            )
            val city = _userCityInput.value.trim()
            if (city.isNotEmpty()) {
                memoryRepository.upsertMemory(
                    category = MemoryCategory.PROFILE.name,
                    key = "home_city",
                    value = city,
                    source = MemorySource.SETUP_WIZARD.name,
                    confidence = 1.0f,
                    pinned = true
                )
            }
            for (draft in _contactNicknames.value) {
                val nick = draft.nickname.trim()
                val full = draft.fullName.trim()
                if (nick.isNotEmpty() && full.isNotEmpty()) {
                    memoryRepository.upsertMemory(
                        category = MemoryCategory.CONTACT.name,
                        key = "contact_${nick.lowercase(Locale.US)}",
                        value = "$nick -> $full",
                        source = MemorySource.SETUP_WIZARD.name,
                        confidence = 1.0f,
                        pinned = true
                    )
                }
            }
            settingsRepository.setSelectedModelId(_selectedModelId.value)
            settingsRepository.setSetupComplete(true)
            _keyPresenceVersion.value += 1
        }
    }
}

class HomeViewModel(
    private val agentOrchestrator: AgentOrchestrator,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val memoryRepository: MemoryRepository,
    private val safetyGate: SafetyGate,
    private val killSwitch: KillSwitch,
    private val voiceSessionManager: VoiceSessionManager,
    private val speechRecognizer: SpeechRecognizerWrapper,
    private val ttsEngine: TtsEngineWrapper
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

    val orbCaption: StateFlow<String?> = agentOrchestrator.orbCaption
    val userFacingError: StateFlow<UserFacingError?> = agentOrchestrator.userFacingError
    val lastResponseText: StateFlow<String> = agentOrchestrator.lastResponseText
    val streamingText: StateFlow<String?> = agentOrchestrator.streamingText
    val isUsingFallbackChip: StateFlow<Boolean> = voiceSessionManager.isUsingFallbackChip
    val pendingConfirmation: StateFlow<ConfirmationRequest?> = safetyGate.pendingConfirmation
    val isKilled: StateFlow<Boolean> = killSwitch.isKilled
    val keyRejectedBanner: StateFlow<Boolean> = agentOrchestrator.keyRejectedBanner

    val rmsEnergyLevel: StateFlow<Float> = speechRecognizer.rmsDbLevel
    val partialTranscript: StateFlow<String> = speechRecognizer.partialTranscript

    private val _inputDraft = MutableStateFlow("")
    val inputDraft: StateFlow<String> = _inputDraft.asStateFlow()

    private val _isInputVisible = MutableStateFlow(false)
    val isInputVisible: StateFlow<Boolean> = _isInputVisible.asStateFlow()

    private val _sttStatusBanner = MutableStateFlow<String?>(null)
    val sttStatusBanner: StateFlow<String?> = _sttStatusBanner.asStateFlow()

    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _showTutorial = MutableStateFlow(false)
    val showTutorial: StateFlow<Boolean> = _showTutorial.asStateFlow()

    init {
        viewModelScope.launch {
            memoryRepository.memoriesFlow.collect { memories ->
                val nameFact = memories.firstOrNull { it.key == "user_name" }
                _userName.value = nameFact?.value.orEmpty()
            }
        }
    }

    fun toggleInputVisibility() {
        _isInputVisible.value = !_isInputVisible.value
    }

    fun dismissTutorial() {
        _showTutorial.value = false
    }

    fun updateDraft(text: String) {
        _inputDraft.value = text
    }

    fun dismissSttBanner() {
        _sttStatusBanner.value = null
    }

    fun clearKeyRejectedBanner() {
        agentOrchestrator.clearKeyRejectedBanner()
    }

    fun clearUserFacingError() {
        agentOrchestrator.clearUserFacingError()
    }

    fun startNewChat() {
        viewModelScope.launch {
            voiceSessionManager.stopAll()
            chatRepository.startNewConversation()
            _sttStatusBanner.value = null
            agentOrchestrator.clearUserFacingError()
        }
    }

    fun sendSampleCommand(command: String) {
        sendTextCommand(command)
    }

    fun sendTextCommand(textOverride: String? = null) {
        val text = (textOverride ?: _inputDraft.value).trim()
        if (text.isEmpty()) return
        if (textOverride == null) {
            _inputDraft.value = ""
        }
        _sttStatusBanner.value = null
        agentOrchestrator.clearUserFacingError()

        viewModelScope.launch {
            voiceSessionManager.enterThinking()
            val reply = agentOrchestrator.handleUserCommand(text)
            val currentSettings = settings.value
            if (!killSwitch.isKilled.value) {
                val wantsFollowUp = reply.trimEnd().endsWith("?")
                voiceSessionManager.speakReply(
                    rawText = reply,
                    language = currentSettings.language,
                    selectedVoiceName = currentSettings.selectedVoiceName,
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
        agentOrchestrator.clearUserFacingError()
        val lang = settings.value.language

        voiceSessionManager.startListening(
            language = lang,
            onFinalTranscript = { transcript ->
                if (transcript.isNotBlank()) {
                    sendTextCommand(transcript)
                }
            },
            onError = { friendlyError ->
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

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModel(
    private val appContext: Context,
    private val memoryRepository: MemoryRepository,
    private val chatRepository: ChatRepository,
    private val activityLogRepository: ActivityLogRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val memories: StateFlow<List<MemoryFact>> = _searchQuery
        .flatMapLatest { q ->
            if (q.isBlank()) memoryRepository.memoriesFlow
            else memoryRepository.searchMemories(q.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ConversationItem>> = chatRepository.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val learnedToday: StateFlow<List<MemoryFact>> = memoryRepository.learnedTodayFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedConversation = MutableStateFlow<ConversationItem?>(null)
    val selectedConversation: StateFlow<ConversationItem?> = _selectedConversation.asStateFlow()

    private val _selectedConversationMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val selectedConversationMessages: StateFlow<List<ChatMessage>> = _selectedConversationMessages.asStateFlow()

    private val _memoryStatusBanner = MutableStateFlow<String?>(null)
    val memoryStatusBanner: StateFlow<String?> = _memoryStatusBanner.asStateFlow()

    private val _isAddSheetOpen = MutableStateFlow(false)
    val isAddSheetOpen: StateFlow<Boolean> = _isAddSheetOpen.asStateFlow()

    fun openAddSheet() {
        _isAddSheetOpen.value = true
    }

    fun closeAddSheet() {
        _isAddSheetOpen.value = false
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun dismissBanner() {
        _memoryStatusBanner.value = null
    }

    fun addFactFromPrompt(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return
        val lower = trimmed.lowercase(Locale.US)
        val category = when {
            lower.contains("sister") || lower.contains("brother") || lower.contains("mom") ||
                lower.contains("dad") || lower.contains("friend") || lower.contains("boss") ||
                lower.contains("phone") || lower.contains("contact") -> MemoryCategory.CONTACT.name
            lower.contains("like") || lower.contains("prefer") || lower.contains("favourite") ||
                lower.contains("favorite") || lower.contains("hate") -> MemoryCategory.PREFERENCE.name
            lower.contains("name is") || lower.contains("live in") || lower.contains("birthday") ||
                lower.contains("city") -> MemoryCategory.PROFILE.name
            else -> MemoryCategory.NOTE.name
        }

        val key = trimmed.take(30).replace(Regex("(?i)^(remember( that)?|my)\\s+"), "").trim()
        saveMemory(category = category, key = key.ifEmpty { "Note" }, value = trimmed)
        closeAddSheet()
    }

    fun saveMemory(category: String, key: String, value: String, pinned: Boolean = false) {
        if (key.isBlank() || value.isBlank()) return
        val rejection = MemorySafetyFilter.validateFact(key, value)
        if (rejection != null) {
            _memoryStatusBanner.value = rejection
            return
        }
        viewModelScope.launch {
            memoryRepository.upsertMemory(
                category = category,
                key = key,
                value = value,
                source = MemorySource.USER_EXPLICIT.name,
                confidence = 1.0f,
                pinned = pinned
            )
            _memoryStatusBanner.value = null
        }
    }

    fun updateMemory(
        fact: MemoryFact,
        newCategory: String,
        newKey: String,
        newValue: String,
        pinned: Boolean
    ) {
        if (newKey.isBlank() || newValue.isBlank()) return
        val rejection = MemorySafetyFilter.validateFact(newKey, newValue)
        if (rejection != null) {
            _memoryStatusBanner.value = rejection
            return
        }
        viewModelScope.launch {
            memoryRepository.updateMemory(
                id = fact.id,
                category = newCategory,
                key = newKey.trim(),
                value = newValue.trim(),
                pinned = pinned
            )
        }
    }

    fun togglePinMemory(fact: MemoryFact) {
        viewModelScope.launch {
            memoryRepository.setPinned(fact.id, !fact.pinned)
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id)
        }
    }

    fun undoLearnedFact(fact: MemoryFact) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(fact.id)
            _memoryStatusBanner.value = "Undid learned fact: ${fact.key}"
        }
    }

    fun openConversationTranscript(conversation: ConversationItem) {
        _selectedConversation.value = conversation
        viewModelScope.launch {
            _selectedConversationMessages.value =
                chatRepository.getMessagesForConversation(conversation.id)
        }
    }

    fun closeConversationTranscript() {
        _selectedConversation.value = null
        _selectedConversationMessages.value = emptyList()
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            chatRepository.deleteConversation(conversationId)
            if (_selectedConversation.value?.id == conversationId) {
                closeConversationTranscript()
            }
        }
    }

    fun exportMemoriesJson(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val json = memoryRepository.exportAllAsJson()
            onReady(json)
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            memoryRepository.clearAllMemories()
        }
    }

    fun deleteAllAssistantData(confirmText: String, onCompleted: () -> Unit = {}) {
        if (confirmText.trim() != "DELETE") return
        viewModelScope.launch {
            memoryRepository.clearAllMemories()
            chatRepository.clearAllMessages()
            activityLogRepository.clearAllLogs()
            onCompleted()
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

    init {
        viewModelScope.launch {
            routineRepository.routinesFlow.collect { list ->
                val hasHardwareTriggers = list.any {
                    it.enabled && (
                        it.triggerType == RoutineTriggerType.HEADSET_CONNECTED ||
                            it.triggerType == RoutineTriggerType.CHARGING_STARTED
                        )
                }
                RoutineTriggerReceiver.syncDynamicReceivers(appContext, hasHardwareTriggers)
            }
        }
    }

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
            routineRepository.upsertRoutine(
                RoutineItem(
                    title = title.trim(),
                    triggerType = triggerType,
                    triggerValue = triggerValue.trim(),
                    actionsJson = actions,
                    enabled = true
                )
            )
        }
    }

    fun toggleRoutine(routine: RoutineItem, enabled: Boolean) {
        viewModelScope.launch {
            routineRepository.toggleRoutine(routine.id, enabled)
        }
    }

    fun deleteRoutine(routine: RoutineItem) {
        viewModelScope.launch {
            routineRepository.deleteRoutine(routine.id)
        }
    }

    fun runRoutineNow(routine: RoutineItem) {
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
            stepsEn = "Settings -> Apps -> JARVIS -> Battery -> Select 'Unrestricted' so scheduled routines and foreground tasks run reliably."
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
                rationaleEn = "Required to hear your spoken commands. Raw audio is never stored on disk.",
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
                rationaleEn = "Resolves spoken contact names to real phone numbers so JARVIS never dials wrong numbers.",
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
                rationaleEn = "Reads screen layout and executes user-approved taps and scrolls. Automatically blocked on banking/UPI apps.",
                isGranted = hasAccessibility,
                isOptionalInStoreSafeMode = true
            ),
            PermissionItemStatus(
                id = "NOTIFICATION_LISTENER",
                titleEn = "Notification Listener Access",
                rationaleEn = "Summarizes incoming notifications and sends confirmed inline replies.",
                isGranted = hasNotifListener
            ),
            PermissionItemStatus(
                id = "BATTERY_OPTIMIZATION",
                titleEn = "Unrestricted Battery Optimization",
                rationaleEn = "Prevents OEM battery savers from stopping active voice sessions or scheduled routines.",
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
    private val apiKeyStore: ApiKeyStore,
    private val geminiClient: GeminiClient,
    private val settingsRepository: SettingsRepository,
    private val voiceSessionManager: VoiceSessionManager,
    private val speechRecognizer: SpeechRecognizerWrapper,
    private val ttsEngine: TtsEngineWrapper,
    private val agentOrchestrator: AgentOrchestrator,
    private val killSwitch: KillSwitch
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val availableDeviceVoices: StateFlow<List<DeviceVoiceOption>> = ttsEngine.availableVoices

    private val _maskedApiKey = MutableStateFlow(apiKeyStore.mask())
    val maskedApiKey: StateFlow<String> = _maskedApiKey.asStateFlow()

    private val _keyStatusMessage = MutableStateFlow<String?>(null)
    val keyStatusMessage: StateFlow<String?> = _keyStatusMessage.asStateFlow()

    private val _isVerifyingKey = MutableStateFlow(false)
    val isVerifyingKey: StateFlow<Boolean> = _isVerifyingKey.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _testConnectionMessage = MutableStateFlow<String?>(null)
    val testConnectionMessage: StateFlow<String?> = _testConnectionMessage.asStateFlow()

    val voiceDiagnostics: StateFlow<VoiceDiagnosticsSnapshot> = combine(
        combine(
            voiceSessionManager.state,
            speechRecognizer.lastErrorCode,
            speechRecognizer.lastErrorMessage
        ) { state, errCode, errMsg -> Triple(state, errCode, errMsg) },
        combine(
            settings,
            speechRecognizer.lastSttLatencyMs,
            agentOrchestrator.lastAgentStepLatencyMs,
            ttsEngine.firstAudioLatencyMs
        ) { userSettings, sttLat, agentLat, ttsLat ->
            VoiceDiagnosticsSnapshot(
                isOfflineTtsActive = true,
                activeVoiceId = userSettings.selectedVoiceName.ifBlank { "system_default" },
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

    fun refreshMaskedKey() {
        _maskedApiKey.value = apiKeyStore.mask()
    }

    fun testAiConnection() {
        val currentKey = apiKeyStore.get()
        if (currentKey.isNullOrBlank()) {
            _testConnectionMessage.value = "No key configured"
            return
        }
        _isTestingConnection.value = true
        _testConnectionMessage.value = null
        viewModelScope.launch {
            val result = geminiClient.testConnection(currentKey)
            _isTestingConnection.value = false
            _testConnectionMessage.value = if (result.isSuccess) {
                "Connected to Gemini successfully"
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Connection failed"
            }
        }
    }

    fun verifyAndSaveNewKey(newKey: String, onSuccess: () -> Unit = {}) {
        val trimmed = newKey.trim()
        if (trimmed.isEmpty()) {
            _keyStatusMessage.value = "Invalid API key"
            return
        }
        _isVerifyingKey.value = true
        viewModelScope.launch {
            when (val res = geminiClient.verifyKeyAndListModels(trimmed)) {
                is KeyVerificationResult.Valid -> {
                    apiKeyStore.save(trimmed)
                    _maskedApiKey.value = apiKeyStore.mask()
                    val models = res.availableModels
                    settingsRepository.setAvailableModels(models)
                    val chosen = when {
                        models.contains(settings.value.selectedModelId) -> settings.value.selectedModelId
                        else -> res.defaultModelId
                    }
                    settingsRepository.setSelectedModelId(chosen)
                    settingsRepository.setSetupComplete(true)
                    agentOrchestrator.clearKeyRejectedBanner()
                    _keyStatusMessage.value = "Connected! Available models: ${models.size}"
                    onSuccess()
                }
                is KeyVerificationResult.Invalid -> {
                    _keyStatusMessage.value = res.message
                }
                is KeyVerificationResult.RateLimited -> {
                    _keyStatusMessage.value = res.message
                }
                is KeyVerificationResult.NetworkError -> {
                    _keyStatusMessage.value = res.message
                }
            }
            _isVerifyingKey.value = false
        }
    }

    fun reverifyCurrentKey() {
        val current = apiKeyStore.get()
        if (current.isNullOrBlank()) {
            _keyStatusMessage.value = "Not configured"
            return
        }
        verifyAndSaveNewKey(current)
    }

    fun removeApiKey(onRemoved: () -> Unit = {}) {
        apiKeyStore.clear()
        _maskedApiKey.value = ""
        _keyStatusMessage.value = null
        voiceSessionManager.stopAll()
        VoiceForegroundService.stopService(appContext)
        OverlayService.stop(appContext)
        viewModelScope.launch {
            settingsRepository.setSetupComplete(false)
            onRemoved()
        }
    }

    fun setSelectedModelId(modelId: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedModelId(modelId)
        }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.setLanguage(language)
        }
    }

    fun setSelectedVoiceName(voiceName: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedVoiceName(voiceName)
        }
    }

    fun setSpeechSpeed(speed: Float) {
        viewModelScope.launch {
            settingsRepository.setSpeechSpeed(speed.coerceIn(0.8f, 1.3f))
        }
    }

    fun previewVoice(samplePhrase: String) {
        val current = settings.value
        voiceSessionManager.speakReply(
            rawText = samplePhrase,
            language = current.language,
            selectedVoiceName = current.selectedVoiceName,
            speed = current.speechSpeed,
            followUpListening = false
        )
    }

    fun setAutoLearnEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoLearnEnabled(enabled)
        }
    }

    fun setAskBeforeSavingFact(ask: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAskBeforeSavingFact(ask)
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

    fun setVoiceEngine(engine: com.example.domain.model.VoiceEngineOption) {
        viewModelScope.launch {
            settingsRepository.setVoiceEngine(engine)
        }
    }

    fun setWakePhraseEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWakePhraseEnabled(enabled)
            if (enabled) {
                com.example.services.WakePhraseService.startService(appContext)
            } else {
                com.example.services.WakePhraseService.stopService(appContext)
            }
        }
    }

    fun setWakePhraseScreenOnOnly(screenOnOnly: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWakePhraseScreenOnOnly(screenOnOnly)
        }
    }

    fun setWakePhraseStopAfter30m(stopAfter30m: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWakePhraseStopAfter30m(stopAfter30m)
        }
    }

    fun clearVoiceLogs() {
        DiagnosticsLogger.clearVoiceLogs()
    }
}

class JarvisViewModelFactory(
    private val appContext: Context,
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(SetupViewModel::class.java) -> SetupViewModel(
                appContext = appContext,
                apiKeyStore = container.apiKeyStore,
                geminiClient = container.geminiClient,
                settingsRepository = container.settingsRepository,
                memoryRepository = container.memoryRepository
            ) as T

            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(
                agentOrchestrator = container.agentOrchestrator,
                chatRepository = container.chatRepository,
                settingsRepository = container.settingsRepository,
                memoryRepository = container.memoryRepository,
                safetyGate = container.safetyGate,
                killSwitch = container.killSwitch,
                voiceSessionManager = container.voiceSessionManager,
                speechRecognizer = container.speechRecognizerWrapper,
                ttsEngine = container.ttsEngineWrapper
            ) as T

            modelClass.isAssignableFrom(ActivityLogViewModel::class.java) -> ActivityLogViewModel(
                activityLogRepository = container.activityLogRepository
            ) as T

            modelClass.isAssignableFrom(MemoryViewModel::class.java) -> MemoryViewModel(
                appContext = appContext,
                memoryRepository = container.memoryRepository,
                chatRepository = container.chatRepository,
                activityLogRepository = container.activityLogRepository
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
                apiKeyStore = container.apiKeyStore,
                geminiClient = container.geminiClient,
                settingsRepository = container.settingsRepository,
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
