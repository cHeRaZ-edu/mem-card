package com.cheraz.memcard.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiHelpersTest {
    @Test
    fun expandsEnvAndFormatsSizes() {
        val home = System.getenv("HOME") ?: System.getenv("USERPROFILE")
        if (home != null) {
            val key = if (System.getenv("HOME") != null) "HOME" else "USERPROFILE"
            assertEquals("$home\\Azahar", expandEnv("%$key%\\Azahar"))
        }
        assertEquals("%NO_EXISTE_MEMCARD%\\x", expandEnv("%NO_EXISTE_MEMCARD%\\x"))
        assertEquals("512 B", human(512))
        assertTrue(human(3 * 1024 * 1024).endsWith("MB"))
    }

    @Test
    fun settingsPickMuve() {
        val s = AppSettings(server = "s", apiKey = "k", muvePath = "C:\\muve.exe")
        assertEquals("C:\\muve.exe", s.toMuveConfig().executable)
        assertTrue(s.configured)
    }
}
