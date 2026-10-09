package com.tankful.android

import com.tankful.android.catalog.reminderStringHasFormatArgs
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShippedReminderCopyTest {
    @Test
    fun reminderSentencesHaveNoFormatArguments() {
        val root = File("src/main/res")
        val english = read(File(root, "values/strings.xml"))
        assertTrue(english.keys.contains("reminder_odometer"))
        val folders = root.listFiles { file -> file.isDirectory && file.name.startsWith("values") }
        assertTrue(folders != null && folders.any { it.name == "values-it" })
        for (folder in folders!!) {
            val file = File(folder, "strings.xml")
            if (!file.isFile) continue
            val strings = read(file)
            for (name in english.keys) {
                val value = strings[name]
                assertTrue(folder.name + " " + name, value != null)
                assertFalse(folder.name + " " + name, reminderStringHasFormatArgs(value!!))
                if (folder.name != "values" && name == "reminder_insurance") {
                    assertFalse(folder.name, value == english.getValue(name))
                }
            }
        }
    }

    private fun read(file: File): Map<String, String> = sentences(file.readText())

    private fun sentences(xml: String): Map<String, String> {
        val pattern = Regex("""name="(reminder_[^"]+)">([^<]*)</string>""")
        return pattern.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }
}
