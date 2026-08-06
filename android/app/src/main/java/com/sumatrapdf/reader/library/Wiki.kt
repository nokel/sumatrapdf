package com.sumatrapdf.reader.library

import org.json.JSONObject
import java.io.File

// Port of the read side of audiobook/lore — the fact store the BookNLP
// analysis writes, and the queries audiobook/library/wiki.py answers for
// the book detail page. Building facts needs BookNLP and an LLM, neither
// of which runs on a phone; a facts.json copied across from the desktop
// is read here unchanged.

const val LORE_FACTS_FILE = "facts.json"
const val LORE_BOOKNLP_SUBDIR = "booknlp"
const val LORE_READY_FILE = "ready"
const val TOP_PEOPLE = 40
const val TOP_PLACES = 25
const val TOP_TOPICS = 25

val KIN_TERM_VALUES = setOf(
    "mother", "father", "parent", "brother", "sister", "sibling", "son",
    "daughter", "child", "uncle", "aunt", "nephew", "niece", "cousin",
    "grandmother", "grandfather", "grandson", "granddaughter", "grandchild",
    "wife", "husband", "spouse", "stepmother", "stepfather", "stepbrother",
    "stepsister",
)

val ALL_KIN = KIN_TERM_VALUES + setOf(
    "sibling", "parent", "child", "spouse", "cousin", "grandparent",
    "grandchild", "uncle_or_aunt", "nephew_or_niece",
)

val TRAIT_PREDICATES = setOf("describes", "describes_not")
val VOICE_PREDICATES = setOf("voice", "voice_not", "speaks", "speech_verb")
val PLACE_PREDICATES = setOf("seen_at", "has_place")
val INFO_PREDICATES = setOf("knows")
val PLACE_RELATIONS = listOf("inside", "beneath", "above", "near", "outside", "behind", "at", "on")

data class Evidence(val book: String?, val page: Int, val text: String)

data class Fact(
    val subject: String,
    val predicate: String,
    val obj: String,
    val confidence: Double,
    val count: Int,
    val inferred: Boolean,
    val evidence: List<Evidence>,
)

class FactStore(val facts: List<Fact>) {
    fun all(): List<Fact> = facts

    fun bySubject(subject: String, predicate: String? = null): List<Fact> =
        facts.filter { it.subject == subject && (predicate == null || it.predicate == predicate) }
            .sortedWith(compareByDescending<Fact> { it.confidence }.thenByDescending { it.count })

    fun byObject(obj: String, predicate: String? = null): List<Fact> =
        facts.filter { it.obj == obj && (predicate == null || it.predicate == predicate) }
            .sortedWith(compareByDescending<Fact> { it.confidence }.thenByDescending { it.count })

    fun byPredicate(predicate: String): List<Fact> =
        facts.filter { it.predicate == predicate }
            .sortedWith(compareByDescending<Fact> { it.confidence }.thenByDescending { it.count })

    fun counts(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (f in facts) out[f.predicate] = (out[f.predicate] ?: 0) + 1
        return out
    }

    companion object {
        fun load(path: File): FactStore? {
            if (!path.exists()) return null
            return try {
                val root = JSONObject(path.readText())
                val arr = root.optJSONArray("facts") ?: return FactStore(emptyList())
                val out = mutableListOf<Fact>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val evidence = mutableListOf<Evidence>()
                    val ev = o.optJSONArray("evidence")
                    if (ev != null) {
                        for (j in 0 until ev.length()) {
                            val e = ev.optJSONObject(j) ?: continue
                            evidence.add(
                                Evidence(
                                    book = e.optString("source", "").takeIf { it.isNotBlank() },
                                    page = e.optInt("page", 0),
                                    text = e.optString("text", "").take(240),
                                ),
                            )
                        }
                    }
                    out.add(
                        Fact(
                            subject = o.optString("subject", ""),
                            predicate = o.optString("predicate", ""),
                            obj = o.optString("object", ""),
                            confidence = o.optDouble("confidence", 0.0),
                            count = o.optInt("count", 0),
                            inferred = o.optBoolean("inferred", false),
                            evidence = evidence,
                        ),
                    )
                }
                FactStore(out)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

object LorePaths {
    val analysisRoot: File
        get() = File(LibraryCache.root.parentFile, "analysis").also { it.mkdirs() }

    val loreRoot: File
        get() = File(LibraryCache.root.parentFile, "lore").also { it.mkdirs() }
}

fun loreSlug(text: String): String =
    text.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_').lowercase().take(60)

fun seriesFactsPath(name: String): File = File(LorePaths.loreRoot, loreSlug(name) + ".json")

data class LoreSeriesMeta(val series: String, val facts: Int, val books: Int)

fun seriesMeta(name: String?): LoreSeriesMeta? {
    if (name.isNullOrBlank()) return null
    val path = File(LorePaths.loreRoot, loreSlug(name) + ".meta.json")
    if (!path.exists()) return null
    return try {
        val o = JSONObject(path.readText())
        LoreSeriesMeta(
            series = o.optString("series", name),
            facts = o.optInt("facts", 0),
            books = o.optJSONArray("books")?.length() ?: 0,
        )
    } catch (_: Throwable) {
        null
    }
}

private val storeCache = LinkedHashMap<String, FactStore?>()

fun seriesStore(name: String?): FactStore? {
    if (name.isNullOrBlank()) return null
    synchronized(storeCache) { if (storeCache.containsKey(name)) return storeCache[name] }
    val store = FactStore.load(seriesFactsPath(name))
    synchronized(storeCache) { storeCache[name] = store }
    return store
}

fun bookAnalysisDir(hash: String?): File? =
    if (hash.isNullOrBlank()) null else File(LorePaths.analysisRoot, hash)

fun bookStore(hash: String?): FactStore? {
    val dir = bookAnalysisDir(hash) ?: return null
    val key = "book:" + dir.path
    synchronized(storeCache) { if (storeCache.containsKey(key)) return storeCache[key] }
    val store = FactStore.load(File(dir, LORE_FACTS_FILE))
    synchronized(storeCache) { storeCache[key] = store }
    return store
}

fun forgetWiki() = synchronized(storeCache) { storeCache.clear() }

// ---- query.py ----

data class WikiValue(
    val value: String,
    val confidence: Double,
    val mentions: Int,
    val inferred: Boolean,
    val evidence: List<Evidence>,
)

private fun groupBy(store: FactStore, name: String, predicates: Set<String>): Map<String, List<WikiValue>> {
    val out = LinkedHashMap<String, MutableList<WikiValue>>()
    for (f in store.bySubject(name)) {
        if (f.predicate !in predicates) continue
        out.getOrPut(f.predicate) { mutableListOf() }.add(
            WikiValue(
                value = f.obj,
                confidence = Math.round(f.confidence * 100) / 100.0,
                mentions = f.count,
                inferred = f.inferred,
                evidence = f.evidence.take(2),
            ),
        )
    }
    for (k in out.keys) {
        out[k] = out[k]!!.sortedWith(
            compareByDescending<WikiValue> { it.mentions }.thenByDescending { it.confidence },
        ).toMutableList()
    }
    return out
}

fun wikiPeople(store: FactStore): List<String> {
    val seen = LinkedHashMap<String, Int>()
    for (f in store.all()) {
        if (f.predicate in ALL_KIN || f.predicate in TRAIT_PREDICATES ||
            f.predicate in VOICE_PREDICATES || f.predicate in INFO_PREDICATES
        ) {
            seen[f.subject] = (seen[f.subject] ?: 0) + f.count
        }
    }
    return seen.entries.sortedByDescending { it.value }.map { it.key }
}

data class WikiPerson(
    val name: String,
    val family: Map<String, List<WikiValue>>,
    val description: Map<String, List<WikiValue>>,
    val voice: Map<String, List<WikiValue>>,
    val places: Map<String, List<WikiValue>>,
    val knows: Map<String, List<WikiValue>>,
    val books: List<String>,
) {
    val isEmpty: Boolean
        get() = family.isEmpty() && description.isEmpty() && voice.isEmpty() &&
            places.isEmpty() && knows.isEmpty() && books.isEmpty()
}

fun wikiCharacter(store: FactStore, name: String): WikiPerson? {
    val books = LinkedHashMap<String, Int>()
    for (f in store.bySubject(name)) {
        for (e in f.evidence) {
            val src = e.book ?: continue
            books[src] = (books[src] ?: 0) + 1
        }
    }
    val page = WikiPerson(
        name = name,
        family = groupBy(store, name, ALL_KIN),
        description = groupBy(store, name, TRAIT_PREDICATES),
        voice = groupBy(store, name, VOICE_PREDICATES),
        places = groupBy(store, name, PLACE_PREDICATES),
        knows = groupBy(store, name, INFO_PREDICATES),
        books = books.entries.sortedByDescending { it.value }.map { it.key },
    )
    return if (page.isEmpty) null else page
}

data class WikiPlace(
    val name: String,
    val kind: String,
    val mentions: Int,
    val parts: List<Pair<String, String>>,
)

fun wikiWorldMap(store: FactStore): List<WikiPlace> {
    val nodes = LinkedHashMap<String, WikiPlace>()
    val parts = LinkedHashMap<String, MutableList<Pair<String, String>>>()
    for (f in store.byPredicate("kind")) {
        if (f.subject !in nodes) {
            nodes[f.subject] = WikiPlace(f.subject, f.obj, f.count, emptyList())
        }
    }
    for (rel in PLACE_RELATIONS) {
        for (f in store.byPredicate(rel)) {
            if (f.obj !in nodes) continue
            parts.getOrPut(f.obj) { mutableListOf() }.add(f.subject to rel)
        }
    }
    return nodes.values
        .map { it.copy(parts = parts[it.name] ?: emptyList()) }
        .sortedByDescending { it.mentions }
}

fun wikiTopics(store: FactStore): List<String> {
    val seen = LinkedHashMap<String, Int>()
    for (f in store.byPredicate("knows")) seen[f.obj] = (seen[f.obj] ?: 0) + f.count
    return seen.entries.sortedByDescending { it.value }.map { it.key }
}

data class WikiKnower(val name: String, val confidence: Double, val mentions: Int, val evidence: Evidence?)

fun wikiWhoKnows(store: FactStore, topic: String): List<WikiKnower> =
    store.byObject(topic, "knows").map {
        WikiKnower(
            name = it.subject,
            confidence = Math.round(it.confidence * 100) / 100.0,
            mentions = it.count,
            evidence = it.evidence.firstOrNull(),
        )
    }

data class WikiSummary(
    val people: List<String>,
    val places: List<String>,
    val topics: List<String>,
    val facts: Int,
    val predicates: Map<String, Int>,
)

fun wikiSummary(store: FactStore?): WikiSummary? {
    if (store == null) return null
    return WikiSummary(
        people = wikiPeople(store).take(TOP_PEOPLE),
        places = wikiWorldMap(store).take(TOP_PLACES).map { it.name },
        topics = wikiTopics(store).take(TOP_TOPICS),
        facts = store.all().size,
        predicates = store.counts(),
    )
}

// shelf.py::wiki_series_for — walk up from the book's folder until a
// folder name matches a series the lore has been built for.
fun wikiSeriesFor(folder: String?, roots: List<String> = emptyList()): String? {
    if (folder.isNullOrBlank()) return null
    val stops = roots.map { File(it).absolutePath.lowercase() }.toSet()
    var here = File(folder).absoluteFile
    repeat(6) {
        val name = here.name
        if (name.isEmpty() || here.path.lowercase() in stops) return null
        if (seriesMeta(name) != null) return name
        val parent = here.parentFile ?: return null
        if (parent.path == here.path) return null
        here = parent
    }
    return null
}

// shelf.py::_attach_analysis — a BookNLP analysis copied across from the
// desktop is keyed by the book's content hash.
fun attachAnalysis(books: List<Book>) {
    for (b in books) {
        b.booknlp = false
        if (b.ext != ".pdf") continue
        if (b.hash == null) b.hash = contentHash(b.path)
        val dir = bookAnalysisDir(b.hash) ?: continue
        b.booknlp = File(File(dir, LORE_BOOKNLP_SUBDIR), LORE_READY_FILE).exists()
        b.facts = File(dir, LORE_FACTS_FILE).exists()
        b.analyzed = File(dir, "analysis.json").exists()
    }
}
