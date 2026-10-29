package dev.abode.text

import org.junit.Assert.assertEquals
import org.junit.Test

class DevanagariTransliteratorTest {

    @Test
    fun `maps a regular word`() {
        assertEquals("नमस्ते", DevanagariTransliterator.transliterate("namaste"))
    }

    @Test
    fun `uses the inherent vowel after a consonant`() {
        assertEquals("भरत", DevanagariTransliterator.transliterate("bharat"))
    }

    @Test
    fun `joins two consonants with a virama`() {
        assertEquals("कर्म", DevanagariTransliterator.transliterate("karma"))
    }

    @Test
    fun `leaves digits alone`() {
        assertEquals("123", DevanagariTransliterator.transliterate("123"))
    }

    @Test
    fun `empty stays empty`() {
        assertEquals("", DevanagariTransliterator.transliterate(""))
    }
}
