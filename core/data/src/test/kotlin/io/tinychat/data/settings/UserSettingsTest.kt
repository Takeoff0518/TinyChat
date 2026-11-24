package io.tinychat.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.tinychat.model.ThinkingEffort
import io.tinychat.model.TtsVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UserSettingsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var settings: UserSettings
    private lateinit var storeScope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        storeFile = File(temp.root, "user_settings.preferences_pb")
        settings = openSettings()
    }

    @After
    fun tearDown() = runBlocking { closeSettings() }

    private fun openSettings(): UserSettings {
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return UserSettings(PreferenceDataStoreFactory.create(scope = storeScope) { storeFile })
    }

    private suspend fun closeSettings() {
        storeScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun `默认不自动朗读、音色是海星`() = runTest {
        assertFalse(settings.autoSpeak.first())
        assertEquals(TtsVoice.STELLA, settings.ttsVoice.first())
    }

    @Test
    fun `改了能读回来`() = runTest {
        settings.setAutoSpeak(true)
        settings.setTtsVoice(TtsVoice.TIDE)
        assertTrue(settings.autoSpeak.first())
        assertEquals(TtsVoice.TIDE, settings.ttsVoice.first())
    }

    @Test
    fun `认不出的音色值退回默认`() {
        assertEquals(TtsVoice.STELLA, UserSettings.voiceOf(null))
        assertEquals(TtsVoice.STELLA, UserSettings.voiceOf("bogus"))
        assertEquals(TtsVoice.STELLA, UserSettings.voiceOf("stella"))
        assertEquals(TtsVoice.TIDE, UserSettings.voiceOf("tide"))
    }

    @Test
    fun `从旧会话恢复开关但保留旧版已选模型和服务商`() = runTest {
        settings.setActiveProviderId("gateway")
        settings.setLastModel("gateway", "model-new")

        val lastSession = ChatDefaults("deepseek", "old-model", ThinkingEffort.MAX, false, true)
        val selected = settings.loadChatDefaults { lastSession }

        assertEquals(ChatDefaults("gateway", "model-new", ThinkingEffort.MAX, false, true), selected)
        assertEquals(selected, settings.loadChatDefaults { error("已迁移后不应读取历史会话") })
    }

    @Test
    fun `空白会话的选择可在新实例中读取`() = runTest {
        val choice = ChatDefaults("gateway", "model-picked", ThinkingEffort.HIGH, true, true)
        settings.setChatDefaults(choice)
        closeSettings()
        val reopened = openSettings()

        assertEquals(choice, reopened.loadChatDefaults { error("有已保存的选择") })
        assertEquals("gateway", reopened.activeProviderId.first())
        assertEquals("model-picked", reopened.lastModel("gateway").first())
    }

    @Test
    fun `启动读取旧会话时保存的新选择有优先权`() = runTest {
        val choice = ChatDefaults("gateway", "model-picked", ThinkingEffort.HIGH, true, true)
        val lastSession = ChatDefaults("deepseek", "old-model", ThinkingEffort.LOW, false, false)

        val selected = settings.loadChatDefaults {
            settings.setChatDefaults(choice)
            lastSession
        }

        assertEquals(choice, selected)
    }
}
