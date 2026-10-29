package dev.abode.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrieDictionaryTest {

    private fun dictionary() = TrieDictionary.parse(
        sequenceOf(
            "the 900",
            "there 300",
            "these 200",
            "them 100",
            "other 500",
        ),
    )

    @Test
    fun `inserts and finds words`() {
        val dict = dictionary()
        assertTrue(dict.contains("the"))
        assertTrue(dict.contains("there"))
        assertEquals(5, dict.wordCount)
    }

    @Test
    fun `returns completions ordered by frequency`() {
        assertEquals(listOf("the", "there", "these"), dictionary().suggestions("th", 3))
    }

    @Test
    fun `respects the limit`() {
        assertEquals(2, dictionary().suggestions("th", 2).size)
    }

    @Test
    fun `returns nothing for an unknown prefix`() {
        assertTrue(dictionary().suggestions("zz", 3).isEmpty())
    }

    @Test
    fun `ignores blank lines and comments`() {
        val dict = TrieDictionary.parse(sequenceOf("", "# comment", "word 5"))
        assertEquals(1, dict.wordCount)
    }
}
