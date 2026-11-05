package com.netino.desktop.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchTest {
    @Test fun spellingsAndDigitsFold() {
        val card = both("اشتراک گوشی کانفیگ پورت 10811", "share phone port 10811")
        assertTrue(matches("كانفيگ", card), "Arabic kaf and yeh find the Persian spelling")
        assertTrue(matches("۱۰۸۱۱", card), "Persian digits find Latin ones")
        assertTrue(matches("PHONE", card))
        assertTrue(matches("", card))
        assertFalse(matches("تونل", card))
        assertTrue(matches("گوشی پورت", card), "every word must appear")
        assertFalse(matches("گوشی تونل", card))
    }
}
