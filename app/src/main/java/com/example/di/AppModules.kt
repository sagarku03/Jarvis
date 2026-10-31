package com.example.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.example.BuildConfig
import com.example.data.local.JarvisDatabase
import com.example.data.remote.AgentStepRequestDto
import com.example.data.remote.AgentStepResponseDto
import com.example.data.remote.ApiService
import com.example.data.remote.AuthHeaderInterceptor
import com.example.data.remote.FakeBackend
import com.example.data.remote.TokenProvider
import com.example.data.remote.TtsRequestDto
import com.example.data.repository.ActivityLogRepositoryImpl
import com.example.data.repository.AgentBackendRepositoryImpl
import com.example.data.repository.AuthRepositoryImpl
import com.example.data.repository.ChatRepositoryImpl
import com.example.data.repository.MemoryRepositoryImpl
import com.example.data.repository.RoutineRepositoryImpl
import com.example.data.repository.SettingsRepositoryImpl
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.contacts.ContactsResolver
import com.example.domain.repository.ActivityLogRepository
import com.example.domain.repository.AgentBackendRepository
import com.example.domain.repository.AuthRepository
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
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
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
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val killSwitch: KillSwitch = KillSwitch()

    val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile("jarvis_settings.preferences_pb") }
        )
    }

    val database: JarvisDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            JarvisDatabase::class.java,
            "jarvis_assistant.db"
        )
            .addMigrations(JarvisDatabase.MIGRATION_1_2, JarvisDatabase.MIGRATION_2_3)
            .build()
    }

    val firebaseAuth: FirebaseAuth? by lazy {
        try {
            val apps = FirebaseApp.getApps(appContext)
            val app = if (apps.isNotEmpty()) {
                FirebaseApp.getInstance()
            } else {
                FirebaseApp.initializeApp(appContext)
            }
            if (app != null) {
                FirebaseAuth.getInstance(app)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(firebaseAuth)
    }

    val tokenProvider: TokenProvider by lazy {
        object : TokenProvider {
            override fun getFirebaseIdToken(): String? {
                val user = firebaseAuth?.currentUser ?: return null
                return runCatching {
                    runBlocking { user.getIdToken(false).await().token }
                }.getOrNull()
            }
        }
    }

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        OkHttpClient.Builder()
            .addInterceptor(AuthHeaderInterceptor(tokenProvider))
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val retrofit: Retrofit by lazy {
        val baseUrl = sanitizeBaseUrl(BuildConfig.JARVIS_BACKEND_BASE_URL)
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    private val networkApiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }

    private val fakeBackend: FakeBackend by lazy {
        FakeBackend()
    }

    val apiService: ApiService by lazy {
        if (!BuildConfig.USE_FAKE_BACKEND) {
            networkApiService
        } else {
            object : ApiService {
                private val hasConfiguredBackend: Boolean
                    get() {
                        val url = BuildConfig.JARVIS_BACKEND_BASE_URL.trim()
                        return url.startsWith("http") && !url.contains("jarvis-proxy.example.com")
                    }

                override suspend fun agentStep(request: AgentStepRequestDto): AgentStepResponseDto {
                    if (hasConfiguredBackend) {
                        return try {
                            networkApiService.agentStep(request)
                        } catch (_: Exception) {
                            fakeBackend.agentStep(request)
                        }
                    }
                    return fakeBackend.agentStep(request)
                }

                override suspend fun synthesizeSpeech(request: TtsRequestDto): ResponseBody {
                    if (BuildConfig.AI_VOICE && hasConfiguredBackend) {
                        return try {
                            networkApiService.synthesizeSpeech(request)
                        } catch (_: Exception) {
                            fakeBackend.synthesizeSpeech(request)
                        }
                    }
                    return fakeBackend.synthesizeSpeech(request)
                }
            }
        }
    }

    private fun sanitizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://jarvis-proxy.example.com/"
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepositoryImpl(database.chatMessageDao())
    }

    val memoryRepository: MemoryRepository by lazy {
        MemoryRepositoryImpl(database.memoryDao())
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
                SystemNavigationTool()
            )
        )
    }

    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            agentBackendRepository = AgentBackendRepositoryImpl(appContext, apiService),
            chatRepository = chatRepository,
            memoryRepository = memoryRepository,
            activityLogRepository = activityLogRepository,
            routineRepository = routineRepository,
            settingsRepository = settingsRepository,
            toolRegistry = toolRegistry,
            safetyGate = safetyGate,
            killSwitch = killSwitch
        )
    }

    val speechRecognizerWrapper: SpeechRecognizerWrapper by lazy {
        AndroidSpeechRecognizerWrapper(appContext)
    }

    val ttsEngineWrapper: TtsEngineWrapper by lazy {
        AndroidTtsEngineWrapper(appContext, apiService)
    }

    val wakeWordEngine: WakeWordEngine by lazy {
        FreeWakeWordEngineImpl()
    }

    val voiceSessionManager: VoiceSessionManager by lazy {
        VoiceSessionManager(
            speechRecognizer = speechRecognizerWrapper,
            ttsEngine = ttsEngineWrapper,
            audioRecorder = null,
            wakeWordEngine = wakeWordEngine
        )
    }
}
