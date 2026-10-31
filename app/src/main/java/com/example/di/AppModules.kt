package com.example.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.example.BuildConfig
import com.example.data.local.ActivityLogDao
import com.example.data.local.ChatMessageDao
import com.example.data.local.JarvisDatabase
import com.example.data.local.MemoryItemDao
import com.example.data.local.RoutineDao
import com.example.data.remote.ApiService
import com.example.data.remote.FirebaseProxyAuthInterceptor
import com.example.env.FlavorEnvironment
import com.example.env.FlavorEnvironmentImpl
import com.example.data.repository.ActivityLogRepositoryImpl
import com.example.data.repository.AgentBackendRepositoryImpl
import com.example.data.repository.ChatRepositoryImpl
import com.example.data.repository.MemoryRepositoryImpl
import com.example.data.repository.RoutineRepositoryImpl
import com.example.data.repository.SettingsRepositoryImpl
import com.example.data.security.VaultCipherManager
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.AgentBackendRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.RoutineRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.safety.KillSwitch
import com.example.domain.safety.KillSwitchImpl
import com.example.domain.safety.SafetyGate
import com.example.domain.safety.SafetyGateImpl
import com.example.domain.tool.Tool
import com.example.domain.tool.ToolRegistry
import com.example.domain.voice.AudioRecorder
import com.example.domain.voice.SpeechRecognizerWrapper
import com.example.domain.voice.TtsEngineWrapper
import com.example.domain.voice.WakeWordDetector
import com.example.services.voice.AndroidSpeechRecognizerWrapper
import com.example.services.voice.AndroidTtsEngineWrapper
import com.example.services.voice.PcmAudioRecorderImpl
import com.example.services.voice.PorcupineWakeWordEngineImpl
import com.example.tools.CallContactTool
import com.example.tools.FlashlightTool
import com.example.tools.NavigateTool
import com.example.tools.NotificationActionTool
import com.example.tools.OpenAppAndFindTool
import com.example.tools.OpenAppTool
import com.example.tools.OpenUrlTool
import com.example.tools.PlayMediaTool
import com.example.tools.ReadScreenTool
import com.example.tools.ScrollTool
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
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `triggerType` TEXT NOT NULL, `triggerValue` TEXT NOT NULL, `actionsPrompt` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `lastRunTimestamp` INTEGER)"
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(context: Context): JarvisDatabase {
        val builder = Room.databaseBuilder(
            context.applicationContext,
            JarvisDatabase::class.java,
            "jarvis_encrypted_vault.db"
        ).addMigrations(MIGRATION_1_2)

        if (BuildConfig.IS_DEV_FLAVOR) {
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }
        return builder.build()
    }

    @Provides
    fun provideChatDao(db: JarvisDatabase): ChatMessageDao = db.chatMessageDao()

    @Provides
    fun provideMemoryDao(db: JarvisDatabase): MemoryItemDao = db.memoryItemDao()

    @Provides
    fun provideActivityLogDao(db: JarvisDatabase): ActivityLogDao = db.activityLogDao()

    @Provides
    fun provideRoutineDao(db: JarvisDatabase): RoutineDao = db.routineDao()

    @Provides
    @Singleton
    fun provideDataStore(context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            produceFile = { context.applicationContext.preferencesDataStoreFile("jarvis_settings") }
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(FirebaseProxyAuthInterceptor())
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private const val FALLBACK_UNCONFIGURED_BASE_URL = "https://127.0.0.1/"

    fun isValidBackendUrlConfigured(rawUrl: String?): Boolean {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val withTrailingSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val parsed = okhttp3.HttpUrl.Companion.run { withTrailingSlash.toHttpUrlOrNull() }
            ?: return false
        return parsed.host.isNotBlank() && parsed.host != "127.0.0.1"
    }

    /**
     * Validates and normalizes a raw base URL string for Retrofit.
     * If no valid URL is configured yet, uses a local loopback placeholder so Retrofit
     * initializes safely without pointing to any fake external domain like example.com.
     */
    fun sanitizeBaseUrl(rawUrl: String?): String {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return FALLBACK_UNCONFIGURED_BASE_URL

        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return FALLBACK_UNCONFIGURED_BASE_URL
        }

        val withTrailingSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val parsed = okhttp3.HttpUrl.Companion.run { withTrailingSlash.toHttpUrlOrNull() }
            ?: return FALLBACK_UNCONFIGURED_BASE_URL
        return if (parsed.host.isBlank()) FALLBACK_UNCONFIGURED_BASE_URL else parsed.toString()
    }

    @Provides
    @Singleton
    fun provideApiService(okHttpClient: OkHttpClient, moshi: Moshi): ApiService {
        val safeBaseUrl = sanitizeBaseUrl(BuildConfig.JARVIS_BACKEND_BASE_URL)
        return runCatching {
            Retrofit.Builder()
                .baseUrl(safeBaseUrl)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(ApiService::class.java)
        }.getOrElse {
            Retrofit.Builder()
                .baseUrl(FALLBACK_UNCONFIGURED_BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(ApiService::class.java)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ToolMultibindingModule {
    @Provides
    @IntoSet
    fun provideToolsSet(
        context: Context,
        contactsResolver: com.example.domain.contact.ContactsResolver
    ): Set<Tool> {
        val openAppTool = OpenAppTool(context)
        return setOf(
            // Stage 1: 11 Intent-based Tools
            openAppTool,
            CallContactTool(context, contactsResolver),
            SendSmsTool(context, contactsResolver),
            SetAlarmTool(context),
            SetTimerTool(context),
            FlashlightTool(context),
            VolumeTool(context),
            OpenUrlTool(context),
            WebSearchTool(context),
            PlayMediaTool(context),
            NavigateTool(context),
            // Stage 4: 7 Full Phone Control (AccessibilityService) Tools
            ReadScreenTool(),
            TapElementTool(),
            TypeTextTool(),
            ScrollTool(),
            SystemNavigationTool(),
            WaitForElementTool(),
            OpenAppAndFindTool(openAppTool),
            // Stage 5: 2 Notification & Messaging Tools
            NotificationActionTool(),
            WhatsAppTelegramTool(context, contactsResolver)
        )
    }
}

/**
 * Singleton DI Container wiring all Modules for ViewModels & Services.
 */
@Singleton
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: JarvisDatabase = DatabaseModule.provideDatabase(appContext)
    val dataStore: DataStore<Preferences> = DatabaseModule.provideDataStore(appContext)
    val vaultCipherManager: VaultCipherManager = VaultCipherManager()

    val flavorEnvironment: FlavorEnvironment = FlavorEnvironmentImpl()
    val moshi: Moshi = NetworkModule.provideMoshi()
    val okHttpClient: OkHttpClient = NetworkModule.provideOkHttpClient()
    val remoteApiService: ApiService = NetworkModule.provideApiService(okHttpClient, moshi)

    val killSwitch: KillSwitch = KillSwitchImpl()
    val audioRecorder: AudioRecorder = PcmAudioRecorderImpl(appContext, killSwitch)
    val speechRecognizer: SpeechRecognizerWrapper = AndroidSpeechRecognizerWrapper(appContext, killSwitch)
    val ttsEngine: TtsEngineWrapper = AndroidTtsEngineWrapper(appContext, remoteApiService, killSwitch)
    val wakeWordDetector: WakeWordDetector = PorcupineWakeWordEngineImpl(audioRecorder)
    val safetyGate: SafetyGate = SafetyGateImpl(ttsEngine, killSwitch)

    val authRepository: com.example.domain.repository.AuthRepository =
        com.example.data.repository.FirebaseAuthRepositoryImpl()
    val chatRepository: ChatRepository = ChatRepositoryImpl(database.chatMessageDao())
    val memoryRepository: MemoryRepository = MemoryRepositoryImpl(database.memoryItemDao(), vaultCipherManager)
    val routineRepository: RoutineRepository = RoutineRepositoryImpl(database.routineDao())
    val activityLogRepository: ActivityLogRepository = ActivityLogRepositoryImpl(database.activityLogDao())
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(dataStore)

    val contactsResolver: com.example.domain.contact.ContactsResolver =
        com.example.domain.contact.ContactsResolver(
            dataSource = com.example.domain.contact.AndroidContactsDataSource(appContext),
            memoryRepository = memoryRepository
        )

    val toolsSet: Set<Tool> = ToolMultibindingModule.provideToolsSet(appContext, contactsResolver)
    val toolRegistry: ToolRegistry = ToolRegistry(toolsSet)

    val backendRepository: AgentBackendRepository = AgentBackendRepositoryImpl(
        context = appContext,
        remoteApiService = remoteApiService,
        optionalFakeApiService = flavorEnvironment.provideOptionalFakeApiService()
    )

    val agentOrchestrator: AgentOrchestrator = AgentOrchestrator(
        backendRepository = backendRepository,
        chatRepository = chatRepository,
        memoryRepository = memoryRepository,
        routineRepository = routineRepository,
        activityLogRepository = activityLogRepository,
        settingsRepository = settingsRepository,
        toolRegistry = toolRegistry,
        safetyGate = safetyGate,
        ttsEngine = ttsEngine,
        killSwitch = killSwitch,
        contactsResolver = contactsResolver
    )
}
