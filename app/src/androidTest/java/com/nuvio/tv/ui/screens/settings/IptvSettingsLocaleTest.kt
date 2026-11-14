package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.livetv.crash.AddFromPhoneServer
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.record.RecordingStorage
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IptvSettingsLocaleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var previousLanguage: String
    private lateinit var previousActivityConfiguration: Configuration
    private val base: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun localized(tag: String): Context = base.createConfigurationContext(
        Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
    )

    private fun screenshot(language: String) {
        composeRule.waitForIdle()
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(base.getExternalFilesDir(null), "iptv-settings-$language.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            screenshot.recycle()
        }
    }

    @Before
    fun rememberLanguage() {
        previousLanguage = LocaleCache.localeTag
        previousActivityConfiguration = Configuration(composeRule.activity.resources.configuration)
    }

    @After
    @Suppress("DEPRECATION")
    fun restoreLanguage() {
        LocaleCache.localeTag = previousLanguage
        composeRule.runOnUiThread {
            val resources = composeRule.activity.resources
            resources.updateConfiguration(previousActivityConfiguration, resources.displayMetrics)
        }
    }

    // Dialog windows read their Activity's resources. Match the localized Activity that the
    // app recreates on language changes, rather than overriding only the parent composition.
    @Suppress("DEPRECATION")
    private fun setActivityLanguage(tag: String) {
        LocaleCache.localeTag = tag
        val resources = composeRule.activity.resources
        val configuration = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    @Test
    fun appLanguageOverridesDeviceAndCanReturnToEnglishOrSystem() {
        val englishDevice = localized("en-US")
        LocaleCache.localeTag = "es"
        val spanish = englishDevice.withAppLocale()
        assertEquals("TV en directo", spanish.getString(LiveTvPage.MAIN.title))
        assertEquals("Canales rápidos", spanish.getString(R.string.iptv_quick_channels_title))
        assertEquals("Guardar", spanish.getString(R.string.action_save))

        LocaleCache.localeTag = "en"
        assertEquals("Live TV", englishDevice.withAppLocale().getString(LiveTvPage.MAIN.title))
        LocaleCache.localeTag = ""
        assertEquals("Live TV", englishDevice.withAppLocale().getString(LiveTvPage.MAIN.title))
    }

    @Test
    fun quantitiesAndFormatsUseTheSelectedResources() {
        LocaleCache.localeTag = "es"
        val spanish = localized("es")
        val resources = spanish.resources
        assertEquals("1 día", resources.getQuantityString(R.plurals.iptv_settings_days, 1, 1))
        assertEquals("2 días", resources.getQuantityString(R.plurals.iptv_settings_days, 2, 2))
        assertEquals("1 canal", resources.getQuantityString(R.plurals.iptv_settings_channel_count, 1, 1))
        assertEquals("2 canales", resources.getQuantityString(R.plurals.iptv_settings_channel_count, 2, 2))
        assertEquals("1,5 GB", resources.getString(R.string.iptv_settings_1f_gb, 1.5f))
        assertTrue(resources.getString(R.string.iptv_settings_delete_1_s, "Provider % & <TV>").contains("Provider % & <TV>"))
        assertEquals("Este dispositivo", RecordingStorage.choices(spanish).first { it.isDevice }.label)
        assertEquals("1,5 GB", RecordingStorage.sizeLabel(1536L * 1024 * 1024, Locale.forLanguageTag("es")))
        assertEquals("1.5 GB", RecordingStorage.sizeLabel(1536L * 1024 * 1024))
    }

    @Test
    fun quickChannelsUsesTheActivityLanguageAfterSwitchingAndReturning() {
        composeRule.runOnUiThread { setActivityLanguage("en") }
        val context = mutableStateOf(localized("en"))
        composeRule.setContent {
            key(context.value) {
                CompositionLocalProvider(
                    LocalContext provides context.value,
                    LocalConfiguration provides Configuration(context.value.resources.configuration)
                ) {
                    NuvioTheme {
                        ChannelRowDialog(LiveTvSettings(), emptyList(), onUpdate = {}, onDismiss = {})
                    }
                }
            }
        }
        composeRule.onNodeWithText("Quick Channels").assertIsDisplayed()
        screenshot("en")
        composeRule.runOnIdle {
            setActivityLanguage("es")
            context.value = localized("es")
        }
        composeRule.onNodeWithText("Canales rápidos").assertIsDisplayed()
        screenshot("es")
        composeRule.onNodeWithText("Mostrar Canales rápidos").assertIsDisplayed()
        composeRule.runOnIdle {
            setActivityLanguage("en")
            context.value = localized("en")
        }
        composeRule.onNodeWithText("Quick Channels").assertIsDisplayed()
        composeRule.onNodeWithText("Show Quick Channels").assertIsDisplayed()
    }

    @Test
    fun translatedTabsStayReachableInANarrowPaneWithTheDpad() {
        val spanish = localized("es")
        val selected = mutableStateOf(LiveTvPage.MAIN)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides spanish,
                LocalConfiguration provides Configuration(spanish.resources.configuration)
            ) {
                NuvioTheme {
                    Column {
                        Box(Modifier.width(250.dp)) {
                            LiveTabRow(selected.value, first = null, onSelect = { selected.value = it })
                        }
                        SettingsDialogActionButton(text = "Content", onClick = {})
                    }
                }
            }
        }
        val setup = spanish.getString(LiveTvPage.MAIN.tab)
        val integration = spanish.getString(LiveTvPage.INTEGRATION.tab)
        composeRule.onNodeWithText(setup).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(3) {
            composeRule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText(integration).assertIsFocused().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(LiveTvPage.INTEGRATION, selected.value) }
        composeRule.onNodeWithText("Content").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.waitForIdle()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText(integration).assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun phonePageKeepsItsAppLanguageAndEscapesTranslatedText() {
        val spanish = AddFromPhoneServer.Texts.from(localized("es").resources, "TV <Living room>")
        val english = AddFromPhoneServer.Texts.from(localized("en").resources, "TV")
        assertEquals("es", spanish.languageTag)
        assertFalse(spanish.heading == english.heading)
        assertTrue(spanish.connectedTo.contains("TV <Living room>"))

        val hostile = "\"</script><script>alert(1)</script>&\u2028\n"
        val texts = spanish.copy(instructions = hostile, unreachable = hostile)
        val page = AddFromPhoneServer::class.java.getDeclaredMethod(
            "page", String::class.java, AddFromPhoneServer.Texts::class.java, String::class.java
        ).apply { isAccessible = true }.invoke(AddFromPhoneServer, "test-token", texts, "#123456") as String
        assertTrue(page.contains("lang=\"es\""))
        assertTrue(page.contains("&lt;/script&gt;"))
        assertTrue(page.contains("\\u003c/script\\u003e"))
        assertFalse(page.contains(hostile))

        val arabic = AddFromPhoneServer.Texts.from(localized("ar").resources, "TV")
        assertTrue(arabic.isRtl)
        // Collecting another language must not mutate a running server's text snapshot.
        assertEquals("es", spanish.languageTag)
    }
}
