package com.sumatrapdf.library

import java.io.File
import kotlin.math.ln

object Phrases {
    private const val MIN_GROUP = 2
    private const val MAX_RUN = 4
    private const val MIN_RUN_CHARS = 4
    private const val MIN_SCORE = 6.0
    private const val NAME_SAMPLE = 3000

    private val FILLER = setOf(
        "the", "a", "an", "and", "of", "in", "on", "for", "to", "with", "by",
        "book", "books", "ebook", "ebooks", "vol", "volume", "part", "issue",
        "chapter", "no", "edition", "new", "complete", "collection", "series",
        "guide", "manual", "st", "nd", "rd", "th",
    )

    private val NOT_ALNUM_RUN = Regex("[^a-z0-9]+")
    private val LEADING_ORDINAL = Regex("""^\d+(?:st|nd|rd|th)?""")
    private val TRAILING_DIGITS = Regex("""\d+$""")
    private val WHITESPACE = Regex("""\s+""")
    private val GAP_CHARS = Regex("[^A-Za-z0-9]")
    private val CAMEL_BREAK = Regex("(?<=[a-z])[A-Z]")

    private fun tokens(text: String?): List<String> {
        val out = ArrayList<String>()
        for (raw in NOT_ALNUM_RUN.split((text ?: "").lowercase())) {
            if (raw.isEmpty()) continue
            val word = LEADING_ORDINAL.replace(TRAILING_DIGITS.replace(raw, ""), "")
            if (word.isEmpty() || word.all { it.isDigit() }) continue
            out.add(word)
        }
        return out
    }

    private fun stem(book: Book) = File(book.path).nameWithoutExtension

    private fun documentFrequency(books: List<Book>): Map<String, Int> {
        val df = HashMap<String, Int>()
        for (b in books) {
            for (word in (tokens(stem(b)) + tokens(b.title)).toSet()) {
                df[word] = (df[word] ?: 0) + 1
            }
        }
        return df
    }

    private fun idf(df: Map<String, Int>, total: Int, word: String) =
        ln((total + 1.0) / ((df[word] ?: 0) + 1.0)) + 1.0

    private fun runs(tokens: List<String>): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        for (size in 1..MAX_RUN) {
            if (tokens.size < size) break
            out.add(Pair("head", tokens.subList(0, size).toList()))
            out.add(Pair("tail", tokens.subList(tokens.size - size, tokens.size).toList()))
        }
        return out
    }

    private fun worth(run: List<String>, df: Map<String, Int>, total: Int): Double {
        if (run.all { it in FILLER }) return 0.0
        if (run.joinToString("").length < MIN_RUN_CHARS) return 0.0
        val kept = run.filter { it !in FILLER }
        if (kept.isEmpty()) return 0.0
        val mean = kept.sumOf { idf(df, total, it) } / kept.size
        return mean * (1.0 + 0.3 * (kept.size - 1))
    }

    private class Candidate(
        val score: Double,
        val size: Int,
        val run: List<String>,
        val members: List<Book>,
    )

    private fun phraseGroups(
        books: List<Book>,
        df: Map<String, Int>,
        total: Int,
    ): Pair<List<Pair<List<String>, List<Book>>>, List<Book>> {
        val seen = LinkedHashMap<Pair<String, List<String>>, ArrayList<Book>>()
        for (b in books) {
            val toks = tokens(stem(b))
            if (toks.isEmpty()) continue
            for (run in runs(toks)) seen.getOrPut(run) { ArrayList() }.add(b)
        }
        val ranked = ArrayList<Candidate>()
        for ((where, members) in seen) {
            if (members.size < MIN_GROUP) continue
            val score = worth(where.second, df, total) * members.size
            if (score >= MIN_SCORE) ranked.add(Candidate(score, where.second.size, where.second, members))
        }
        ranked.sortWith(compareBy({ -it.score }, { -it.size }))
        val taken = HashSet<Book>()
        val groups = ArrayList<Pair<List<String>, List<Book>>>()
        for (c in ranked) {
            val free = c.members.filter { it !in taken }
            if (free.size < MIN_GROUP) continue
            taken.addAll(free)
            groups.add(Pair(c.run, free))
        }
        return Pair(groups, books.filter { it !in taken })
    }

    private fun spelled(run: List<String>, members: List<Book>): String? {
        val letters = run.joinToString("")
        if (letters.isEmpty()) return null
        val pattern = Regex(
            letters.map { Regex.escape(it.toString()) }.joinToString("[^A-Za-z0-9]{0,3}"),
            RegexOption.IGNORE_CASE)
        val tally = LinkedHashMap<String, Int>()
        for (b in members) {
            val page = "${b.title} ${b.sample.take(NAME_SAMPLE)}"
            for (found in pattern.findAll(page)) {
                var text = WHITESPACE.replace(found.value, " ").trim { it in " -–—:.," }
                if (text.length < MIN_RUN_CHARS || text == text.uppercase()) continue
                text = text.replaceFirstChar { it.uppercaseChar() }
                if (text == text.lowercase()) continue
                tally[text] = (tally[text] ?: 0) + 1
            }
        }
        if (tally.isEmpty()) return null
        return tally.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> {
                minOf(GAP_CHARS.findAll(it.key).count(), run.size)
            }.thenByDescending {
                CAMEL_BREAK.findAll(it.key).count()
            }.thenByDescending { it.value }.thenByDescending { it.key.length }
        ).first().key
    }

    fun seriesGroups(
        books: List<Book>,
        pool: List<Book>,
    ): Pair<List<Pair<String, List<Book>>>, List<Book>> {
        val df = documentFrequency(pool)
        val groups = phraseGroups(books, df, pool.size)
        val out = ArrayList<Pair<String, List<Book>>>()
        for ((run, members) in groups.first) {
            val named = run.filter { it !in FILLER }.ifEmpty { run }
            if (named.isEmpty()) continue
            val name = spelled(named, members)
                ?: named.joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            out.add(Pair(name, members))
        }
        return Pair(out, groups.second)
    }
}
