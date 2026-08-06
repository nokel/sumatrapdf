package com.sumatrapdf.reader.library

import java.io.File
import java.util.IdentityHashMap
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Port of audiobook/library/learn.py — grouping books into series by the
// shape of their names, and routing rows into the partitions the reader
// has taught by hand.
//
// learn.py's second grouping pass uses scikit-learn (a char n-gram
// TF-IDF followed by average-linkage agglomerative clustering). There is
// no sklearn on Android, so both pieces are implemented here directly:
// the vectoriser matches TfidfVectorizer(analyzer="char_wb",
// ngram_range=(3, 8), sublinear_tf=True) including its smoothed IDF and
// L2 row norm, and the clustering is UPGMA by Lance-Williams update,
// which produces the same dendrogram as the nearest-neighbour-chain
// algorithm scikit-learn ends up in for average linkage.

const val MIN_GROUP = 2
const val MAX_RUN = 4
const val MIN_RUN_CHARS = 4
const val MIN_SCORE = 6.0
const val CLUSTER_DISTANCE = 0.62
const val NAME_SAMPLE = 3000

const val PART_SAMPLE = 12
const val PART_MIN_TAUGHT = 2
const val PART_TERM_IDF = 1.8
const val PART_MEASURED_IDF = 1.3
const val PART_MEASURED_MIN = 5
const val PART_TERM_CHARS = 4
const val PART_CARRY = 0.4

val MEASURED_PREFIXES = listOf("pages_", "shelved_")

val FILLER = setOf(
    "the", "a", "an", "and", "of", "in", "on", "for", "to", "with", "by",
    "book", "books", "ebook", "ebooks", "vol", "volume", "part", "issue",
    "chapter", "no", "edition", "new", "complete", "collection", "series",
    "guide", "manual", "st", "nd", "rd", "th",
)

private val SPLIT_NON_ALNUM = Regex("""[^a-z0-9]+""")
private val TRAILING_DIGITS = Regex("""\d+$""")
private val LEADING_ORDINAL = Regex("""^\d+(?:st|nd|rd|th)?""")

fun learnTokens(text: String?): List<String> {
    val out = mutableListOf<String>()
    for (raw in SPLIT_NON_ALNUM.split((text ?: "").lowercase())) {
        if (raw.isEmpty()) continue
        val word = LEADING_ORDINAL.replace(TRAILING_DIGITS.replace(raw, ""), "")
        if (word.isEmpty() || word.all { it.isDigit() }) continue
        out.add(word)
    }
    return out
}

private fun stemOf(book: Book): String = File(book.path).nameWithoutExtension

private fun squashStem(book: Book): String =
    SPLIT_NON_ALNUM.replace(stemOf(book).lowercase(), "")

private fun documentFrequency(books: List<Book>): Map<String, Int> {
    val df = LinkedHashMap<String, Int>()
    for (b in books) {
        for (word in (learnTokens(stemOf(b)) + learnTokens(b.title)).toSet()) {
            df[word] = (df[word] ?: 0) + 1
        }
    }
    return df
}

private fun idfOf(df: Map<String, Int>, total: Int, word: String): Double =
    ln((total + 1.0) / ((df[word] ?: 0) + 1.0)) + 1.0

private fun runsOf(tokens: List<String>): List<Pair<String, List<String>>> {
    val out = mutableListOf<Pair<String, List<String>>>()
    for (size in 1..MAX_RUN) {
        if (tokens.size < size) break
        out.add("head" to tokens.take(size))
        out.add("tail" to tokens.takeLast(size))
    }
    return out
}

private fun worthOf(run: List<String>, df: Map<String, Int>, total: Int): Double {
    if (run.all { it in FILLER }) return 0.0
    if (run.joinToString("").length < MIN_RUN_CHARS) return 0.0
    val kept = run.filter { it !in FILLER }
    if (kept.isEmpty()) return 0.0
    val mean = kept.sumOf { idfOf(df, total, it) } / kept.size
    return mean * (1.0 + 0.3 * (kept.size - 1))
}

private class IdentitySet {
    private val map = IdentityHashMap<Book, Boolean>()
    fun add(b: Book) { map[b] = true }
    operator fun contains(b: Book): Boolean = map.containsKey(b)
}

private fun phraseGroups(
    books: List<Book>,
    df: Map<String, Int>,
    total: Int,
): Pair<List<Pair<List<String>, List<Book>>>, List<Book>> {
    val seen = LinkedHashMap<Pair<String, List<String>>, MutableList<Book>>()
    for (b in books) {
        val tokens = learnTokens(stemOf(b))
        if (tokens.isEmpty()) continue
        for (run in runsOf(tokens)) seen.getOrPut(run) { mutableListOf() }.add(b)
    }
    data class Ranked(val score: Double, val size: Int, val run: List<String>, val members: List<Book>)
    val ranked = mutableListOf<Ranked>()
    for ((key, members) in seen) {
        if (members.size < MIN_GROUP) continue
        val score = worthOf(key.second, df, total) * members.size
        if (score >= MIN_SCORE) ranked.add(Ranked(score, key.second.size, key.second, members))
    }
    val order = ranked.sortedWith(compareByDescending<Ranked> { it.score }.thenByDescending { it.size })
    val taken = IdentitySet()
    val groups = mutableListOf<Pair<List<String>, List<Book>>>()
    for (r in order) {
        val free = r.members.filter { it !in taken }
        if (free.size < MIN_GROUP) continue
        free.forEach { taken.add(it) }
        groups.add(r.run to free)
    }
    return groups to books.filter { it !in taken }
}

// ---- the scikit-learn replacement ----

// TfidfVectorizer(analyzer="char_wb") pads every word with a space and
// takes the n-grams that fall inside it; a word shorter than n yields the
// padded word once and stops.
fun charWbNgrams(text: String, minN: Int, maxN: Int): List<String> {
    val out = mutableListOf<String>()
    for (raw in Regex("""\s+""").replace(text, " ").split(" ")) {
        if (raw.isEmpty()) continue
        val w = " $raw "
        for (n in minN..maxN) {
            var offset = 0
            out.add(w.substring(offset, min(offset + n, w.length)))
            while (offset + n < w.length) {
                offset += 1
                out.add(w.substring(offset, min(offset + n, w.length)))
            }
            if (offset == 0) break
        }
    }
    return out
}

fun tfidfRows(names: List<String>, minN: Int = 3, maxN: Int = 8): List<Map<Int, Double>> {
    val vocabulary = LinkedHashMap<String, Int>()
    val counts = names.map { name ->
        val row = LinkedHashMap<Int, Int>()
        for (gram in charWbNgrams(name, minN, maxN)) {
            val idx = vocabulary.getOrPut(gram) { vocabulary.size }
            row[idx] = (row[idx] ?: 0) + 1
        }
        row
    }
    val n = names.size
    val df = IntArray(vocabulary.size)
    for (row in counts) for (idx in row.keys) df[idx]++
    val idf = DoubleArray(vocabulary.size) { ln((1.0 + n) / (1.0 + df[it])) + 1.0 }
    return counts.map { row ->
        val out = LinkedHashMap<Int, Double>()
        var norm = 0.0
        for ((idx, count) in row) {
            val value = (1.0 + ln(count.toDouble())) * idf[idx]
            out[idx] = value
            norm += value * value
        }
        norm = sqrt(norm)
        if (norm > 0.0) for (idx in out.keys.toList()) out[idx] = out[idx]!! / norm
        out
    }
}

fun cosineDistance(a: Map<Int, Double>, b: Map<Int, Double>): Double {
    if (a.isEmpty() || b.isEmpty()) return 1.0
    val (small, big) = if (a.size <= b.size) a to b else b to a
    var dot = 0.0
    for ((idx, value) in small) {
        val other = big[idx] ?: continue
        dot += value * other
    }
    return max(0.0, 1.0 - dot)
}

// UPGMA: the distance from a merged cluster to every other is the
// size-weighted mean of the two it came from, which is exactly what
// average linkage means.
fun averageLinkage(distance: Array<DoubleArray>, threshold: Double): List<List<Int>> {
    val n = distance.size
    if (n == 0) return emptyList()
    val d = Array(n) { distance[it].copyOf() }
    val members = MutableList(n) { mutableListOf(it) }
    val alive = BooleanArray(n) { true }
    while (true) {
        var bestI = -1
        var bestJ = -1
        var best = Double.MAX_VALUE
        for (i in 0 until n) {
            if (!alive[i]) continue
            for (j in i + 1 until n) {
                if (!alive[j]) continue
                if (d[i][j] < best) {
                    best = d[i][j]
                    bestI = i
                    bestJ = j
                }
            }
        }
        if (bestI < 0 || best >= threshold) break
        val ni = members[bestI].size
        val nj = members[bestJ].size
        for (k in 0 until n) {
            if (!alive[k] || k == bestI || k == bestJ) continue
            val merged = (ni * d[bestI][k] + nj * d[bestJ][k]) / (ni + nj)
            d[bestI][k] = merged
            d[k][bestI] = merged
        }
        members[bestI].addAll(members[bestJ])
        members[bestJ].clear()
        alive[bestJ] = false
    }
    return (0 until n).filter { alive[it] }.map { members[it].sorted() }
        .sortedBy { it.firstOrNull() ?: Int.MAX_VALUE }
}

private fun clusterGroups(books: List<Book>): Pair<List<Pair<List<String>, List<Book>>>, List<Book>> {
    if (books.size < MIN_GROUP) return emptyList<Pair<List<String>, List<Book>>>() to books
    val names = books.map { squashStem(it) }
    if (names.filter { it.isNotEmpty() }.toSet().size < MIN_GROUP) {
        return emptyList<Pair<List<String>, List<Book>>>() to books
    }
    val rows = tfidfRows(names)
    val n = books.size
    val distance = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val v = cosineDistance(rows[i], rows[j])
            distance[i][j] = v
            distance[j][i] = v
        }
    }
    val clusters = averageLinkage(distance, CLUSTER_DISTANCE)
    val groups = mutableListOf<Pair<List<String>, List<Book>>>()
    val rest = mutableListOf<Book>()
    for (cluster in clusters) {
        val members = cluster.map { books[it] }
        if (members.size < MIN_GROUP) {
            rest.addAll(members)
            continue
        }
        groups.add(sharedRun(members, books) to members)
    }
    return groups to rest
}

private fun sharedRun(members: List<Book>, pool: List<Book>): List<String> {
    val lists = members.map { learnTokens(stemOf(it)) }
    val shortest = lists.minOfOrNull { it.size } ?: 0
    val head = mutableListOf<String>()
    for (i in 0 until shortest) {
        val column = lists.map { it[i] }.toSet()
        if (column.size != 1) break
        head.add(lists[0][i])
    }
    if (head.isNotEmpty()) return head
    val tail = mutableListOf<String>()
    for (i in 0 until shortest) {
        val column = lists.map { it[it.size - 1 - i] }.toSet()
        if (column.size != 1) break
        tail.add(lists[0][lists[0].size - 1 - i])
    }
    if (tail.isNotEmpty()) return tail.reversed()
    val letters = commonLetters(members, pool)
    return if (letters.isEmpty()) emptyList() else listOf(letters)
}

private fun commonLetters(members: List<Book>, pool: List<Book>): String {
    val names = members.map { squashStem(it) }.sortedBy { it.length }
    if (names.isEmpty()) return ""
    val shortest = names[0]
    val shared = LinkedHashSet<String>()
    for (start in shortest.indices) {
        var end = shortest.length
        while (end > start + MIN_RUN_CHARS) {
            val piece = shortest.substring(start, end)
            if (names.drop(1).all { piece in it }) {
                shared.add(piece)
                break
            }
            end--
        }
    }
    if (shared.isEmpty()) return ""
    val others = if (pool.isEmpty()) names else pool.map { squashStem(it) }
    fun rarity(piece: String): Double {
        val seen = others.count { piece in it }
        return -ln((others.size + 1.0) / (seen + 1.0)) * piece.length
    }
    return shared.minByOrNull { rarity(it) } ?: ""
}

private fun isUpperPython(text: String): Boolean {
    val cased = text.filter { it.isLetter() }
    return cased.isNotEmpty() && cased.all { it.isUpperCase() }
}

private fun isLowerPython(text: String): Boolean {
    val cased = text.filter { it.isLetter() }
    return cased.isNotEmpty() && cased.all { it.isLowerCase() }
}

// learn.py::_spelled — the run of tokens is a squashed key; this looks
// for how the book itself spells it, so "hitchhikersguide" comes back as
// "Hitchhiker's Guide".
private fun spelledName(run: List<String>, members: List<Book>): String? {
    val joined = run.joinToString("")
    if (joined.isEmpty()) return null
    val pattern = Regex(
        joined.map { Regex.escape(it.toString()) }.joinToString("""[^A-Za-z0-9]{0,3}"""),
        RegexOption.IGNORE_CASE,
    )
    val tally = LinkedHashMap<String, Int>()
    for (b in members) {
        val page = "${b.title} ${b.sample.take(NAME_SAMPLE)}"
        for (found in pattern.findAll(page)) {
            var text = Regex("""\s+""").replace(found.value, " ").trim(' ', '-', '–', '—', ':', '.', ',')
            if (text.length < MIN_RUN_CHARS || isUpperPython(text)) continue
            text = text[0].uppercaseChar() + text.substring(1)
            if (isLowerPython(text)) continue
            tally[text] = (tally[text] ?: 0) + 1
        }
    }
    if (tally.isEmpty()) return null
    return tally.entries.maxWithOrNull(
        compareBy(
            { min(Regex("""[^A-Za-z0-9]""").findAll(it.key).count(), run.size) },
            { Regex("""(?<=[a-z])[A-Z]""").findAll(it.key).count() },
            { it.value },
            { it.key.length },
        ),
    )?.key
}

fun seriesGroups(books: List<Book>, pool: List<Book>? = null): Pair<List<Pair<String, List<Book>>>, List<Book>> {
    val use = pool ?: books
    val df = documentFrequency(use)
    val total = use.size
    val (groups, afterPhrase) = phraseGroups(books, df, total)
    val (more, rest) = clusterGroups(afterPhrase)
    val out = mutableListOf<Pair<String, List<Book>>>()
    for ((run, members) in groups + more) {
        val named = run.filter { it !in FILLER }.ifEmpty { run }
        if (named.isEmpty()) continue
        val name = spelledName(named, members)
            ?: named.joinToString(" ").split(" ").joinToString(" ") { w ->
                if (w.isEmpty()) w else w[0].uppercaseChar() + w.substring(1)
            }
        out.add(name to members)
    }
    return out to rest
}

// ---- partition routing ----

data class PartitionNote(
    val taught: Int,
    var took: Int = 0,
    var words: List<String> = emptyList(),
    var why: String? = null,
    var checked: Int = 0,
    var putBack: Int = 0,
)

data class PartitionPick(val partition: String, val share: Double, val on: List<String>)

data class RoutingReport(
    val taught: Int,
    val routed: Int,
    val partitions: Map<String, PartitionNote>,
    val keptOut: Int,
    var putBack: Int = 0,
    var notPutBack: Int = 0,
    var checked: Int = 0,
)

private fun namingTexts(row: SeriesRow): List<String> {
    val out = mutableListOf(row.name)
    for (b in row.members.take(PART_SAMPLE)) {
        out.add(b.title)
        out.add(stemOf(b))
    }
    return out
}

private fun pageWord(row: SeriesRow): String? {
    var picture = 0
    var seen = 0
    for (b in row.members.take(PART_SAMPLE)) {
        val art = b.art ?: continue
        seen++
        if (art >= COMIC_ART) picture++
    }
    if (seen == 0) return null
    return if (picture * 2 > seen) "pages_mostly_pictures" else "pages_mostly_text"
}

private fun toldTexts(row: SeriesRow): List<String> {
    val out = mutableListOf<String>()
    for (b in row.members.take(PART_SAMPLE)) {
        b.author?.let { out.add(it) }
        cachedSubjects(b.id)?.forEach { out.add(it) }
    }
    return out
}

private fun wordsIn(texts: List<String>): LinkedHashSet<String> {
    val out = LinkedHashSet<String>()
    for (text in texts) {
        for (word in learnTokens(text)) {
            if (word !in FILLER && word.length >= PART_TERM_CHARS) out.add(word)
        }
    }
    return out
}

private fun shelfWord(row: SeriesRow): String? {
    val votes = LinkedHashMap<String, Int>()
    for (b in row.members.take(PART_SAMPLE)) {
        val shelf = b.genre
        if (shelf != null && shelf != UNKNOWN_SHELF) votes[shelf] = (votes[shelf] ?: 0) + 1
    }
    if (votes.isEmpty()) return null
    val best = votes.entries.maxByOrNull { it.value }!!.key
    return "shelved_" + Regex("""[^a-z0-9]+""").replace(best.lowercase(), "_").trim('_')
}

private fun toldTerms(row: SeriesRow): LinkedHashSet<String> {
    val out = wordsIn(toldTexts(row))
    pageWord(row)?.let { out.add(it) }
    shelfWord(row)?.let { out.add(it) }
    return out
}

private fun namingLetters(row: SeriesRow): String =
    Regex("""[^a-z0-9]+""").replace(namingTexts(row).joinToString(" ").lowercase(), "")

private class RowTerms(
    val naming: LinkedHashSet<String>,
    val told: LinkedHashSet<String>,
    val letters: String,
) {
    fun kind(i: Int): LinkedHashSet<String> = if (i == 0) naming else told
}

private fun carries(word: String, terms: Set<String>, letters: String): Boolean =
    word in terms || word in letters

private fun definingTerms(
    members: List<String>,
    shelf: Map<String, RowTerms>,
    df: List<Map<String, Int>>,
    total: Int,
    kind: Int,
): LinkedHashMap<String, Double> {
    val seen = LinkedHashSet<String>()
    for (key in members) shelf[key]?.let { seen.addAll(it.kind(kind)) }
    val need = max(2, (members.size + 1) / 2)
    val marks = LinkedHashMap<String, Double>()
    for (word in seen) {
        val count = members.count { key ->
            val row = shelf[key] ?: return@count false
            carries(word, row.kind(kind), row.letters)
        }
        if (count < need) continue
        val measured = MEASURED_PREFIXES.any { word.startsWith(it) }
        if (measured && members.size < PART_MEASURED_MIN) continue
        val weight = idfOf(df[kind], total, word)
        if (weight >= (if (measured) PART_MEASURED_IDF else PART_TERM_IDF)) marks[word] = weight
    }
    return marks
}

private fun bestShare(
    marks: Map<String, Double>,
    terms: Set<String>,
    letters: String,
): Pair<Double, List<String>> {
    if (marks.isEmpty()) return 0.0 to emptyList()
    val shared = marks.keys.filter { carries(it, terms, letters) }
    val whole = marks.values.sum()
    if (whole <= 0.0) return 0.0 to shared
    return shared.sumOf { marks[it]!! } / whole to shared
}

private fun route(
    rows: List<SeriesRow>,
    taught: Map<String, String>,
    shelf: Map<String, RowTerms>,
    df: List<Map<String, Int>>,
    total: Int,
    keptOut: Map<String, String> = emptyMap(),
): Pair<LinkedHashMap<String, PartitionPick>, LinkedHashMap<String, PartitionNote>> {
    val groups = LinkedHashMap<String, MutableList<String>>()
    for ((key, part) in taught) {
        if (key in shelf) groups.getOrPut(part) { mutableListOf() }.add(key)
    }
    val picks = LinkedHashMap<String, PartitionPick>()
    val notes = LinkedHashMap<String, PartitionNote>()
    for ((part, members) in groups) {
        val named = definingTerms(members, shelf, df, total, 0)
        val told = definingTerms(members, shelf, df, total, 1)
        val words = (named.entries.sortedByDescending { it.value }.take(4).map { it.key } +
            told.entries.sortedByDescending { it.value }.take(4).map { it.key }).distinct()
        val note = PartitionNote(taught = members.size, words = words)
        notes[part] = note
        if (members.size < PART_MIN_TAUGHT) {
            note.why = "put $PART_MIN_TAUGHT in it by hand and it will start finding the rest"
            continue
        }
        if (named.isEmpty() && told.isEmpty()) {
            note.why = "what you put in it shares no distinctive word"
            continue
        }
        for (row in rows) {
            val key = row.key
            if (key in taught || keptOut[key] == part) continue
            val terms = shelf[key] ?: continue
            val (byName, onName) = bestShare(named, terms.naming, terms.letters)
            val (byWhat, onWhat) = bestShare(told, terms.told, terms.letters)
            val share = max(byName, byWhat)
            if (share < PART_CARRY) continue
            val standing = picks[key]
            if (standing != null && standing.share >= share) continue
            val marks = if (byName >= byWhat) named else told
            val shared = if (byName >= byWhat) onName else onWhat
            picks[key] = PartitionPick(
                partition = part,
                share = Math.round(share * 100) / 100.0,
                on = shared.sortedByDescending { marks[it] ?: 0.0 }.take(3),
            )
        }
    }
    for (pick in picks.values) notes[pick.partition]?.let { it.took += 1 }
    return picks to notes
}

// learn.py::_put_back — take one taught row out and see whether the rest
// still route it back where it was. This is the only honest measure of
// whether a partition has learnt anything.
private fun putBack(
    rows: List<SeriesRow>,
    taught: Map<String, String>,
    shelf: Map<String, RowTerms>,
    df: List<Map<String, Int>>,
    total: Int,
    notes: Map<String, PartitionNote>,
): Triple<Int, Int, Int> {
    val tally = LinkedHashMap<String, Int>()
    for (part in taught.values) tally[part] = (tally[part] ?: 0) + 1
    var right = 0
    var missed = 0
    for ((key, part) in taught) {
        if ((tally[part] ?: 0) - 1 < PART_MIN_TAUGHT) continue
        val less = taught.filterKeys { it != key }
        val found = route(rows, less, shelf, df, total).first[key]
        val note = notes[part]
        note?.let { it.checked += 1 }
        if (found != null && found.partition == part) {
            right++
            note?.let { it.putBack += 1 }
        } else {
            missed++
        }
    }
    return Triple(right, missed, right + missed)
}

fun routePartitions(
    rows: List<SeriesRow>,
    taught: Map<String, String>,
    keptOut: Map<String, String> = emptyMap(),
): Pair<Map<String, PartitionPick>, RoutingReport> {
    val shelf = LinkedHashMap<String, RowTerms>()
    for (row in rows) {
        shelf[row.key] = RowTerms(wordsIn(namingTexts(row)), toldTerms(row), namingLetters(row))
    }
    val df = listOf(LinkedHashMap<String, Int>(), LinkedHashMap<String, Int>())
    for (kind in 0..1) {
        val words = LinkedHashSet<String>()
        for (row in shelf.values) words.addAll(row.kind(kind))
        for (word in words) {
            df[kind][word] = shelf.values.count { carries(word, it.kind(kind), it.letters) }
        }
    }
    val total = rows.size
    val (picks, notes) = route(rows, taught, shelf, df, total, keptOut)
    val report = RoutingReport(
        taught = taught.size,
        routed = picks.size,
        partitions = notes,
        keptOut = keptOut.size,
    )
    val (right, missed, checked) = putBack(rows, taught, shelf, df, total, notes)
    report.putBack = right
    report.notPutBack = missed
    report.checked = checked
    return picks to report
}
