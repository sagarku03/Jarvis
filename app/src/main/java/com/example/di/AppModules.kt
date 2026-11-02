package com.example.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.example.BuildConfig
import com.example.data.local.ApiKeyStore
import com.example.data.local.JarvisDatabase
import com.example.data.remote.GeminiClient
import com.example.data.repository.ActivityLogRepositoryImpl
import com.example.data.repository.AgentBackendRepositoryImpl
import com.example.data.repository.ChatRepositoryImpl
import com.example.data.repository.MemoryRepositoryImpl
import com.example.data.repository.RoutineRepositoryImpl
import com.example.data.repository.SettingsRepositoryImpl
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.agent.MemoryLearner
import com.example.domain.contacts.ContactsResolver
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.AgentBackendRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.safety.KillSwitch
import com.example.domain.safety.SafetyGate
import com.example.domain.safety.SafetyGateImpl
import com.example.domain.tool.ToolRegistry
import com.example.services.voice.AndroidSpeechRecognizerWrapper
import com.example.services.voice.AndroidTtsEngineWrapper
import com.example.services.voice.FreeWakeWordEngineImpl
import com.example.services.voice.SpeechRecognizerWrapper
import com.example.services.voice.TtsEngineWrapper
import com.example.services.voice.VoiceSessionManager
import com.example.services.voice.WakeWordEngine
import com.example.tools.CallContactTool
import com.example.tools.FlashlightTool
import com.example.tools.ForgetMemoryTool
import com.example.tools.NavigateTool
import com.example.tools.NotificationActionTool
import com.example.tools.OpenAppAndFindTool
import com.example.tools.OpenAppTool
import com.example.tools.OpenUrlTool
import com.example.tools.PlayMediaTool
import com.example.tools.ReadScreenTool
import com.example.tools.SaveMemoryTool
import com.example.tools.ScrollTool
import com.example.tools.SearchHistoryTool
import com.example.tools.SearchMemoryTool
import com.example.tools.SendSmsTool
import com.example.tools.SetAlarmTool
import com.example.tools.SetTimerTool
import com.example.tools.SystemNavigationTool
import com.example.tools.TapElementTool
import com.example.tools.TypeTextTool
import com.example.tools.VolumeTool
import com.example.tools.WaitForElementTool
import com.example.tools.WebSearchTool
import com.example.tools.WhatsAppTelegramTool
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit

@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Inject

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Singleton

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class HiltViewModel

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AndroidEntryPoint

/**
 * Application dependency container providing constructor-injected singletons across all layers.
 * Bring-Your-Own-Key edition: direct Gemini API client, Keystore AES-256-GCM ApiKeyStore,
 * and SQLCipher encrypted Room database.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val killSwitch: KillSwitch = KillSwitch()

    val apiKeyStore: ApiKeyStore by lazy {
        ApiKeyStore(appContext)
    }

    val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile("jarvis_settings.preferences_pb") }
        )
    }

    val database: JarvisDatabase by lazy {
        val builder = Room.databaseBuilder(
            appContext,
            JarvisDatabase::class.java,
            "jarvis_assistant.db"
        ).addMigrations(
            JarvisDatabase.MIGRATION_1_2,
            JarvisDatabase.MIGRATION_2_3,
            JarvisDatabase.MIGRATION_3_4,
            JarvisDatabase.MIGRATION_4_5
        )

        // Load SQLCipher native library on Android device; fall back cleanly on host JVM unit tests
        runCatching {
            System.loadLibrary("sqlcipher")
            val passphrase = apiKeyStore.getOrCreateDatabasePassphrase()
            builder.openHelperFactory(SupportOpenHelperFactory(passphrase))
        }

        builder.build()
    }

    fun getDatabaseStorageSizeBytes(): Long {
        val dbFile: File = appContext.getDatabasePath("jarvis_assistant.db") ?: return 0L
        val walFile = File(dbFile.path + "-wal")
        val shmFile = File(dbFile.path + "-shm")
        return (if (dbFile.exists()) dbFile.length() else 0L) +
            (if (walFile.exists()) walFile.length() else 0L) +
            (if (shmFile.exists()) shmFile.length() else 0L)
    }

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            redactHeader("x-goog-api-key")
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val geminiClient: GeminiClient by lazy {
        GeminiClient(
            okHttpClient = okHttpClient,
            moshi = moshi,
            apiKeyStore = apiKeyStore
        )
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepositoryImpl(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            summaryDao = database.summaryDao()
        )
    }

    val memoryRepository: MemoryRepository by lazy {
        MemoryRepositoryImpl(
            memoryDao = database.memoryDao(),
            moshi = moshi
        )
    }

    val activityLogRepository: ActivityLogRepository by lazy {
        ActivityLogRepositoryImpl(database.activityLogDao())
    }

    val routineRepository: RoutineRepository by lazy {
        RoutineRepositoryImpl(database.routineDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(dataStore)
    }

    val contactsResolver: ContactsResolver by lazy {
        ContactsResolver(appContext, memoryRepository)
    }

    val safetyGate: SafetyGate by lazy {
        SafetyGateImpl(contactsResolver)
    }

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(
            tools = setOf(
                OpenAppTool(appContext),
                OpenAppAndFindTool(appContext),
                CallContactTool(appContext, contactsResolver),
                SendSmsTool(appContext, contactsResolver),
                WhatsAppTelegramTool(appContext, contactsResolver),
                SetAlarmTool(appContext),
                SetTimerTool(appContext),
                VolumeTool(appContext),
                FlashlightTool(appContext),
                PlayMediaTool(appContext),
                NavigateTool(appContext),
                WebSearchTool(appContext),
                OpenUrlTool(appContext),
                NotificationActionTool(appContext),
                ReadScreenTool(),
                TapElementTool(),
                TypeTextTool(),
                ScrollTool(),
                WaitForElementTool(),
                SystemNavigationTool(),
                SaveMemoryTool(memoryRepository),
                ForgetMemoryTool(memoryRepository),
                SearchMemoryTool(memoryRepository),
                SearchHistoryTool(chatRepository)
            )
        )
    }

    val agentBackendRepository: AgentBackendRepository by lazy {
        AgentBackendRepositoryImpl(
            context = appContext,
            geminiClient = geminiClient,
            settingsRepository = settingsRepository
        )
    }

    val memoryLearner: MemoryLearner by lazy {
        MemoryLearner(
            geminiClient = geminiClient,
            memoryRepository = memoryRepository,
            chatRepository = chatRepository,
            settingsRepository = settingsRepository,
            moshi = moshi
        )
    }

    val ttsManager: com.example.services.voice.TtsManager by lazy {
        com.example.services.voice.TtsManager(
            context = appContext,
            deviceTts = ttsEngineWrapper as AndroidTtsEngineWrapper,
            apiKeyStore = apiKeyStore,
            okHttpClient = okHttpClient,
            settingsRepository = settingsRepository
        )
    }

    val geminiLiveWebSocketManager: com.example.services.voice.GeminiLiveWebSocketManager by lazy {
        com.example.services.voice.GeminiLiveWebSocketManager(
            context = appContext,
            apiKeyStore = apiKeyStore,
            okHttpClient = okHttpClient,
            moshi = moshi
        )
    }

    val voiceSessionManager: VoiceSessionManager by lazy {
        VoiceSessionManager(
            speechRecognizer = speechRecognizerWrapper,
            ttsEngine = ttsEngineWrapper,
            audioRecorder = null,
            wakeWordEngine = wakeWordEngine,
            ttsManager = ttsManager,
            geminiLiveWebSocketManager = geminiLiveWebSocketManager
        )
    }

    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            agentBackendRepository = agentBackendRepository,
            chatRepository = chatRepository,
            memoryRepository = memoryRepository,
            activityLogRepository = activityLogRepository,
            routineRepository = routineRepository,
            settingsRepository = settingsRepository,
            toolRegistry = toolRegistry,
            safetyGate = safetyGate,
            killSwitch = killSwitch,
            memoryLearner = memoryLearner,
            voiceSessionManager = voiceSessionManager
        )
    }

    val speechRecognizerWrapper: SpeechRecognizerWrapper by lazy {
        AndroidSpeechRecognizerWrapper(appContext)
    }

    val ttsEngineWrapper: TtsEngineWrapper by lazy {
        AndroidTtsEngineWrapper(appContext)
    }

    val wakeWordEngine: WakeWordEngine by lazy {
        FreeWakeWordEngineImpl()
    }
}
