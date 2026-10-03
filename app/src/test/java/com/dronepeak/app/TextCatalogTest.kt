package com.dronepeak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCatalogTest {
    @Test
    fun `translation preserves the aircraft serial and timestamp`() {
        assertEquals("[14:32:08] Hava aracı seri numarası: 1581FZJD000001 (kaydedildi)",
            TextCatalog.operationMessage("[14:32:08] Aircraft serial: 1581FZJD000001 (cached)", AppLanguage.TR))
    }

    @Test
    fun `English write result never claims aircraft confirmation`() {
        val message = TextCatalog.operationMessage("FCC mode enabled — 21 frames sent", AppLanguage.EN)
        assertEquals("FCC commands sent — 21 frames sent", message)
        assertFalse(message.contains("confirmed"))
    }

    @Test
    fun `4G write result keeps the instruction to verify on the aircraft`() {
        val message = "All activation frames written successfully — check 4G status on the aircraft."
        assertTrue(TextCatalog.operationMessage(message, AppLanguage.TR).contains("hava aracından kontrol et"))
        assertEquals(message, TextCatalog.operationMessage(message, AppLanguage.EN))
    }

    @Test
    fun `error localization retains the diagnostic detail`() {
        assertEquals("4G hatası: /duss/mb/0x205 ECONNREFUSED",
            TextCatalog.operationMessage("4G error: /duss/mb/0x205 ECONNREFUSED", AppLanguage.TR))
    }

    @Test
    fun `unknown payload is preserved`() {
        assertEquals("55 03 ff 21", TextCatalog.operationMessage("55 03 ff 21", AppLanguage.TR))
    }
}
