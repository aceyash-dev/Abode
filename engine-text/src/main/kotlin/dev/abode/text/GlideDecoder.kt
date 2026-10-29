package dev.abode.text

/**
 * Turns the letters a finger crossed during a swipe into a word.
 *
 * This is a deliberately small decoder. A production glide engine trains on
 * millions of swipe traces and models key geometry; this one asks a cheaper
 * question — which dictionary word's letters all appear, in order, along the
 * path the finger actually took — and breaks ties on frequency. It gets short
 * common words right most of the time and long words wrong often, which is the
 * honest trade for something that runs in a few milliseconds on device.
 */
class GlideDecoder(private val dictionary: TrieDictionary) {

    fun decode(path: List<Char>, limit: Int = 600): String? {
        if (path.size < MIN_PATH) return null

        val first = path.first()
        val last = path.last()

        // Candidates are the most frequent words starting with the first key
        // crossed. Bounded, because the whole dictionary is not scanned per swipe.
        val candidates = dictionary.suggestions(first.toString(), limit)

        var best: String? = null
        var bestScore = 0.0

        for (word in candidates) {
            if (word.length < 2 || word.length > path.size + 2) continue
            val score = score(word, path, last)
            if (score > bestScore) {
                bestScore = score
                best = word
            }
        }

        return if (bestScore >= MIN_SCORE) best else null
    }

    private fun score(word: String, path: List<Char>, last: Char): Double {
        // Longest prefix of the word that appears in order along the path.
        var matched = 0
        for (c in path) {
            if (matched < word.length && word[matched] == c) matched++
        }
        if (matched == 0) return 0.0

        val coverage = matched.toDouble() / word.length
        val endsWhereTheFingerDid = if (word.last() == last) 0.2 else 0.0
        val frequency = kotlin.math.ln(
            dictionary.frequencyOf(word).coerceAtLeast(1).toDouble(),
        ) / 40.0

        return coverage + endsWhereTheFingerDid + frequency.coerceAtMost(0.25)
    }

    private companion object {
        const val MIN_PATH = 3
        const val MIN_SCORE = 0.75
    }
}
