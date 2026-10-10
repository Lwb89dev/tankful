package com.tankful.android

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SystemLocaleTest {
    @Test
    fun deviceLanguageSelectsTheMatchingCatalog() {
        for (language in LANGUAGES) {
            val expected = continueLocally(File("src/main/res/values-$language/strings.xml"))
            assertEquals(language, expected, text(Locale.forLanguageTag(language)))
            val regional = "$language-${COUNTRY.getValue(language)}"
            assertEquals(regional, expected, text(Locale.forLanguageTag(regional)))
        }
        val chinese = continueLocally(File("src/main/res/values-zh/strings.xml"))
        for (tag in CHINESE) {
            assertEquals(tag, chinese, text(Locale.forLanguageTag(tag)))
        }
    }

    @Test
    fun unrecognizedLanguageUsesEnglish() {
        val english = continueLocally(File("src/main/res/values/strings.xml"))
        for (tag in listOf("ar", "ga", "ga-IE", "mt", "mt-MT")) {
            assertEquals(tag, english, text(Locale.forLanguageTag(tag)))
        }
    }

    @Test
    fun shippedCatalogsMatchThePerAppList() {
        val root = File("src/main/res")
        val folders = root.listFiles { file ->
            file.isDirectory && file.name.startsWith("values-")
        }!!.map { it.name.removePrefix("values-") }.toSet()
        assertEquals(LANGUAGES + CHINESE_FOLDERS, folders)
        assertFalse(File(root, "values-ga").exists())
        assertFalse(File(root, "values-mt").exists())
        val simplified = File(root, "values-zh/strings.xml").readText()
        for (folder in CHINESE_FOLDERS) {
            assertEquals(folder, simplified, File(root, "values-$folder/strings.xml").readText())
        }
        val listed = Regex("""android:name="([^"]+)"""")
            .findAll(File(root, "xml/locales_config.xml").readText())
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(LANGUAGES + "en" + "zh-CN", listed)
    }

    private fun text(locale: Locale): String {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config).getString(R.string.continue_locally)
    }

    private fun continueLocally(file: File): String {
        val match = Regex("""name="continue_locally">([^<]*)</string>""").find(file.readText())
        return match!!.groupValues[1]
    }

    private companion object {
        val LANGUAGES = setOf(
            "bg", "hr", "cs", "da", "nl", "et", "fi", "fr", "de", "el", "hu",
            "it", "lv", "lt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ja", "ru",
        )
        val COUNTRY = mapOf(
            "bg" to "BG", "hr" to "HR", "cs" to "CZ", "da" to "DK", "nl" to "NL",
            "et" to "EE", "fi" to "FI", "fr" to "FR", "de" to "DE", "el" to "GR",
            "hu" to "HU", "it" to "IT", "lv" to "LV", "lt" to "LT", "pl" to "PL",
            "pt" to "PT", "ro" to "RO", "sk" to "SK", "sl" to "SI", "es" to "ES",
            "sv" to "SE", "ja" to "JP", "ru" to "RU",
        )
        val CHINESE = listOf("zh", "zh-CN", "zh-Hans", "zh-SG", "zh-TW", "zh-HK", "zh-Hant")
        val CHINESE_FOLDERS = setOf(
            "zh", "zh-rCN", "zh-rTW", "zh-rHK", "b+zh+Hans", "b+zh+Hant",
        )
    }
}
