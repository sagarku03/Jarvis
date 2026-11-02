package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ApiKeyStore
import com.example.data.local.JarvisDatabase
import com.example.data.repository.ChatRepositoryImpl
import com.example.data.repository.MemoryRepositoryImpl
import com.example.domain.agent.LocalIntentRouter
import com.example.domain.agent.LocalRouteResult
import com.example.domain.model.ChatMessage
import com.example.domain.model.MemoryCategory
import com.example.domain.model.MemorySource
import com.example.domain.model.MessageRole
import com.example.ui.viewmodel.SettingsViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: JarvisDatabase
    private lateinit var memoryRepository: MemoryRepositoryImpl
    private lateinit var chatRepository: ChatRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        memoryRepository = MemoryRepositoryImpl(db.memoryDao(), moshi)
        chatRepository = ChatRepositoryImpl(db.conversationDao(), db.messageDao(), db.summaryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `read string from context and verify tool registry in full and store safe modes`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("JARVIS", appName)
        assertEquals(
            "Hi, I'm Jarvis. How can I help?",
            context.getString(R.string.home_greeting_default)
        )

        val app = context as JarvisApplication
        val fullControlTools = app.appContainer.toolRegistry.getAvailableTools(fullControlOverride = true)
        assertEquals(24, fullControlTools.size)
        assertTrue(fullControlTools.any { it.name == "toggle_flashlight" })
        assertTrue(fullControlTools.any { it.name == "call_contact" })
        assertTrue(fullControlTools.any { it.name == "send_sms" })
        assertTrue(fullControlTools.any { it.name == "read_screen" })
        assertTrue(fullControlTools.any { it.name == "tap_element" })
        assertTrue(fullControlTools.any { it.name == "save_memory" })
        assertTrue(fullControlTools.any { it.name == "forget_memory" })
        assertTrue(fullControlTools.any { it.name == "search_memory" })
        assertTrue(fullControlTools.any { it.name == "search_history" })
        assertTrue(fullControlTools.any { it.name == "adjust_volume" })
        assertNotNull(app.appContainer.toolRegistry.findTool("set_volume"))
        assertNotNull(app.appContainer.toolRegistry.findTool("adjust_volume"))

        val storeSafeTools = app.appContainer.toolRegistry.getAvailableTools(fullControlOverride = false)
        assertEquals(18, storeSafeTools.size)
        assertTrue(storeSafeTools.none { it.requiresFullControlMode })
    }

    @Test
    fun `apiKeyStore roundTrip mask and clear`() {
        val store = ApiKeyStore(context)
        store.clear()
        assertFalse(store.exists())
        assertNull(store.get())
        assertEquals("Not configured", store.mask())

        val secretKey = "AIzaSyD9876543210abcdXYZ"
        store.save(secretKey)
        assertTrue(store.exists())
        assertEquals(secretKey, store.get())
        assertEquals("••••••••dXYZ", store.mask())

        store.clear()
        assertFalse(store.exists())
        assertNull(store.get())
    }

    @Test
    fun `memoryRepository deduplicatesNormalizedKeys and linksSupersededById`() = runBlocking {
        // Insert initial fact: Favorite IPL Team = MI
        val firstId = memoryRepository.upsertMemory(
            category = MemoryCategory.PREFERENCE.name,
            key = "Favorite IPL Team",
            value = "MI",
            source = MemorySource.USER_EXPLICIT.name,
            confidence = 1.0f,
            pinned = false
        )
        assertTrue(firstId > 0L)

        // Update with different casing & punctuation: favorite ipl team! = CSK
        val secondId = memoryRepository.upsertMemory(
            category = MemoryCategory.PREFERENCE.name,
            key = "favorite ipl team!",
            value = "CSK",
            source = MemorySource.USER_EXPLICIT.name,
            confidence = 1.0f,
            pinned = true
        )
        assertTrue(secondId > firstId)

        // Active memories should contain only 1 active row with value CSK
        val activeFacts = memoryRepository.getAllMemoriesSnapshot()
        assertEquals(1, activeFacts.size)
        assertEquals("favorite_ipl_team", activeFacts.first().key)
        assertEquals("CSK", activeFacts.first().value)
        assertTrue(activeFacts.first().pinned)

        // Verify old row in DAO has supersededById = secondId
        val oldEntity = db.memoryDao().getById(firstId)
        assertNotNull(oldEntity)
        assertEquals(secondId, oldEntity?.supersededById)
    }

    @Test
    fun `chatRepository createsNewConversationAfterIdleAndSearchesHistory`() = runBlocking {
        val conv1 = chatRepository.getOrCreateActiveConversationId(nowMs = 1_000_000L)
        chatRepository.appendMessage(
            ChatMessage(
                conversationId = conv1,
                role = MessageRole.USER,
                content = "My brother's name is Rohan and he lives in Bengaluru",
                timestampMs = 1_000_100L
            )
        )

        // Within 30 minutes -> reuses same conversation
        val convSame = chatRepository.getOrCreateActiveConversationId(nowMs = 1_000_000L + 5 * 60_000L)
        assertEquals(conv1, convSame)

        // After 31 minutes -> starts a new conversation
        val conv2 = chatRepository.getOrCreateActiveConversationId(nowMs = 1_000_000L + 31 * 60_000L)
        assertTrue(conv2 > conv1)

        // Search across conversations finds "Rohan"
        val searchHits = chatRepository.searchMessages("Rohan")
        assertEquals(1, searchHits.size)
        assertTrue(searchHits.first().content.contains("Rohan"))

        // Verify no seeded routines exist on fresh launch
        val app = context as JarvisApplication
        val routines = app.appContainer.routineRepository.routinesFlow.first()
        assertTrue("Fresh database must have zero seeded routines", routines.isEmpty())
    }

    @Test
    fun `chatRepository deletesConversationAndAssociatedMessages`() = runBlocking {
        val convId = chatRepository.startNewConversation("To Delete")
        chatRepository.appendMessage(
            ChatMessage(
                conversationId = convId,
                role = MessageRole.USER,
                content = "Test message"
            )
        )
        val msgsBefore = chatRepository.getMessagesForConversation(convId)
        assertEquals(1, msgsBefore.size)

        chatRepository.deleteConversation(convId)

        val msgsAfter = chatRepository.getMessagesForConversation(convId)
        assertTrue(msgsAfter.isEmpty())
    }

    @Test
    fun `settingsRepository dual-model selection voice gender and timbre persistence`() = runBlocking {
        val app = context as JarvisApplication
        val settingsRepo = app.appContainer.settingsRepository

        // 1. Dual model persistence
        settingsRepo.setSelectedTextModelId("gemini-3.8-flash")
        settingsRepo.setSelectedVoiceModelId("gemini-3.1-flash-live-preview")

        val state1 = settingsRepo.settingsFlow.first()
        assertEquals("gemini-3.8-flash", state1.selectedTextModelId)
        assertEquals("gemini-3.1-flash-live-preview", state1.selectedVoiceModelId)

        // 2. Voice gender and live voice name persistence
        settingsRepo.setSelectedVoiceGender("FEMALE")
        settingsRepo.setSelectedLiveVoiceName("Aoede")

        val state2 = settingsRepo.settingsFlow.first()
        assertEquals("FEMALE", state2.selectedVoiceGender)
        assertEquals("Aoede", state2.selectedLiveVoiceName)

        // 3. Changing live voice timbre to Kore
        settingsRepo.setSelectedLiveVoiceName("Kore")
        val state3 = settingsRepo.settingsFlow.first()
        assertEquals("Kore", state3.selectedLiveVoiceName)

        // 4. Changing voice gender to MALE and timbre to Fenrir
        settingsRepo.setSelectedVoiceGender("MALE")
        settingsRepo.setSelectedLiveVoiceName("Fenrir")
        val state4 = settingsRepo.settingsFlow.first()
        assertEquals("MALE", state4.selectedVoiceGender)
        assertEquals("Fenrir", state4.selectedLiveVoiceName)
    }

    @Test
    fun `localIntentRouter resolves Hindi and English phrases in Robolectric context`() {
        val phrases = listOf(
            "YouTube open karo" to Pair("open_app", "YouTube"),
            "WhatsApp kholo" to Pair("open_app", "WhatsApp"),
            "Instagram chalu karo" to Pair("open_app", "Instagram"),
            "open YouTube" to Pair("open_app", "YouTube"),
            "torch jala do" to Pair("toggle_flashlight", "true"),
            "torch band karo" to Pair("toggle_flashlight", "false"),
            "battery kitni hai" to null // DirectReply
        )
        for ((phrase, expected) in phrases) {
            val res = LocalIntentRouter.route(phrase, context)
            assertNotNull("Phrase '$phrase' should route cleanly", res)
            if (expected == null) {
                assertTrue(res is LocalRouteResult.DirectReply)
            } else {
                assertTrue(res is LocalRouteResult.ToolAction)
                val action = res as LocalRouteResult.ToolAction
                assertEquals(expected.first, action.toolName)
                if (action.toolName == "open_app") {
                    assertEquals(expected.second, action.args["app_name"])
                } else if (action.toolName == "toggle_flashlight") {
                    assertEquals(expected.second, action.args["enabled"])
                }
            }
        }
    }
}
