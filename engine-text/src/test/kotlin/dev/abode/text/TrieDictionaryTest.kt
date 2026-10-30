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
    fun `a wide sample is ranked, not a narrow one`() {
        // The candidate cap used to be twenty-four, taken in trie order, so a
        // prefix with many completions was ranked on whatever the walk happened
        // to reach first. A frequent word late in the trie must still win.
        val words = (1..80).map { "th${'a' + (it % 20)}$it 10" } +
            listOf("the 9000")
        val dict = TrieDictionary.parse(words.asSequence())
        assertEquals("the", dict.suggestions("th", 1).first())
    }

    @Test
    fun `a shorter completion wins a tie on frequency`() {
        val dict = TrieDictionary.parse(
            sequenceOf("thank 100", "thanksgiving 100"),
        )
        assertEquals(listOf("thank", "thanksgiving"), dict.suggestions("tha", 2))
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

    @Test
    fun `a swapped pair of letters is one edit, not two`() {
        assertEquals(1, TrieDictionary.editDistance("teh", "the"))
        assertEquals(3, TrieDictionary.editDistance("abc", "xyz"))
    }

    @Test
    fun `autocorrect finds a transposed word`() {
        val dict = TrieDictionary.parse(sequenceOf("the 900", "tea 50"))
        assertEquals("the", dict.nearest("teh", maxDistance = 1))
    }

    @Test
    fun `a learned word rises in the suggestions`() {
        val dict = TrieDictionary.parse(sequenceOf("the 900", "there 300", "these 200"))
        dict.insert("these", 500)
        assertEquals(listOf("the", "these", "there"), dict.suggestions("th", 3))
    }
}
