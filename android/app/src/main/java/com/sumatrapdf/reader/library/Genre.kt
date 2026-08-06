package com.sumatrapdf.reader.library

import org.json.JSONObject
import java.io.File

// Port of audiobook/library/genre.py. The shelf a book lands on is
// decided by the subjects the online catalogue reports, falling back to
// the title and file name when nothing is cached.

const val UNKNOWN_SHELF = "Unsorted"
const val COMIC_SHELF = "Comics & Manga"
const val MANGA = "Manga"
const val MANHWA = "Manhwa & Manhua"
const val COMICS = "Comics"
const val GRAPHIC = "Graphic Novels"

val COMIC_EXTS = listOf(".cbz", ".cbr")
const val COMIC_ART = 0.6
const val COMIC_INK = 1200

val SHELF_ORDER = listOf(
    "Science Fiction",
    "Fantasy",
    "Horror",
    "Mystery & Crime",
    "Thriller & Adventure",
    "Romance",
    "Humour",
    "Historical Fiction",
    "Children's & Young Adult",
    "Fiction",
    COMIC_SHELF,
    "Computing",
    "Electronics & Making",
    "Engineering",
    "Mathematics",
    "Science & Nature",
    "Medicine & Health",
    "Business & Money",
    "Languages",
    "Cooking",
    "Crafts & Hobbies",
    "Study Aids",
    "History",
    "Biography & Memoir",
    "Society & Politics",
    "Religion & Philosophy",
    "Arts & Media",
    "Health & Living",
    "Travel & Places",
    "Sport & Games",
    "Poetry & Drama",
    "Reference",
    UNKNOWN_SHELF,
)

val FICTION_SHELVES = listOf(
    "Science Fiction", "Fantasy", "Horror", "Mystery & Crime",
    "Thriller & Adventure", "Romance", "Humour", "Historical Fiction",
    "Children's & Young Adult", "Fiction", COMIC_SHELF,
)

val BROAD_SHELVES = listOf("Fiction", "Reference")

data class GenreRule(val shelf: String?, val sub: String?, val words: List<String>)

val STORY_RULES = listOf(
    GenreRule("Science Fiction", "Dystopian", listOf("dystopia", "dystopias", "dystopian", "post apocalyptic", "post-apocalyptic", "apocalyptic")),
    GenreRule("Science Fiction", "Cyberpunk", listOf("cyberpunk")),
    GenreRule("Science Fiction", "Time Travel", listOf("time travel")),
    GenreRule("Science Fiction", "Space Opera", listOf("space opera", "interplanetary", "interstellar", "space flight", "imaginary voyages", "space colonies")),
    GenreRule("Science Fiction", "Alien Contact", listOf("extraterrestrial", "extraterrestres", "human-alien", "life on other planets", "flying saucers")),
    GenreRule("Science Fiction", "Robots & AI", listOf("robot", "android", "cyborg")),
    GenreRule("Science Fiction", null, listOf("science fiction", "sci-fi", "scifi")),
    GenreRule("Fantasy", "Myth & Legend", listOf("mythology", "fairy tale", "folklore", "legends", "arthurian")),
    GenreRule("Fantasy", "Magic & Witches", listOf("magic", "wizard", "witch", "sorcery", "sorcerer", "spells")),
    GenreRule("Fantasy", "Quests & Dragons", listOf("dragon", "quests", "epic fantasy", "sword and sorcery")),
    GenreRule("Fantasy", null, listOf("fantasy", "imaginary place", "imaginary places")),
    GenreRule("Horror", null, listOf("horror", "ghost", "vampire", "zombie", "haunted", "supernatural", "occult", "monsters")),
    GenreRule("Mystery & Crime", "Espionage", listOf("espionage", "spies", "secret service", "intelligence service")),
    GenreRule("Mystery & Crime", "True Crime", listOf("true crime")),
    GenreRule("Mystery & Crime", null, listOf("mystery", "detective", "crime", "murder", "suspense", "thriller", "noir", "police")),
    GenreRule("Thriller & Adventure", null, listOf("adventure", "adventurers", "survival", "sea stories", "westerns", "war stories", "aventures")),
    GenreRule("Romance", "Boys' Love", listOf("yaoi", "boys love", "boys' love", "shounen ai", "shonen ai")),
    GenreRule("Romance", "Girls' Love", listOf("yuri", "girls love", "girls' love")),
    GenreRule("Romance", "Erotica", listOf("erotic", "erotica", "hentai")),
    GenreRule("Romance", null, listOf("romance", "love stories")),
    GenreRule("Historical Fiction", null, listOf("historical fiction", "historical novel")),
    GenreRule("Humour", null, listOf("humorous", "humour", "humor", "satire", "satirical", "comedy", "wit and humor", "wit and humour", "parody", "funny")),
)

val SUBJECT_RULES = listOf(
    GenreRule("Computing", null, listOf("computer", "computing", "programming", "software", "linux", "unix", "electronic data processing", "internet", "web site", "web development", "machine learning", "artificial intelligence", "raspberry pi", "microcomputer", "hacking", "penetration testing", "security measures", "operating system", "python (computer", "java (computer", "database", "algorithm", "machine theory", "natural language processing", "application software", "data processing", "information technology", "cryptography", "pocket computers", "programming languages")),
    GenreRule("Electronics & Making", null, listOf("electronics", "arduino", "robotics", "circuits", "microcontroller", "soldering", "3d printing", "makerspace")),
    GenreRule("Engineering", null, listOf("engineering", "mechanics", "construction", "aeronautics")),
    GenreRule("Mathematics", null, listOf("mathematics", "algebra", "geometry", "statistics", "calculus")),
    GenreRule("Science & Nature", null, listOf("science", "physics", "biology", "astronomy", "chemistry", "natural history", "evolution", "geology", "ecology", "botany", "zoology", "wildlife")),
    GenreRule("Medicine & Health", null, listOf("medicine", "medical", "anatomy", "nursing", "nutrition", "first aid")),
    GenreRule("Business & Money", null, listOf("business", "economics", "management", "finance", "marketing", "entrepreneurship", "leadership", "investing", "accounting")),
    GenreRule("Languages", null, listOf("language study", "grammar", "linguistics", "vocabulary", "foreign language")),
    GenreRule("Cooking", null, listOf("cooking", "cookery", "recipes", "baking")),
    GenreRule("Crafts & Hobbies", null, listOf("handicraft", "knitting", "woodwork", "gardening", "needlework", "origami")),
    GenreRule("Study Aids", null, listOf("study aids", "examinations", "textbook", "teaching", "curriculum", "education")),
    GenreRule("Reference", null, listOf("nonfiction", "non-fiction", "handbook", "manual", "how-to")),
)

val FACT_RULES = listOf(
    GenreRule("History", null, listOf("history", "historical", "civilization", "archaeology", "world war", "antiquities", "ancient")),
    GenreRule("Biography & Memoir", null, listOf("biography", "autobiography", "memoir", "diaries", "correspondence", "personal narratives")),
    GenreRule("Religion & Philosophy", null, listOf("religion", "philosophy", "spiritual", "theology", "bible", "buddhism", "ethics", "islam", "christianity", "mysticism")),
    GenreRule("Society & Politics", null, listOf("political", "politics", "sociology", "social science", "government", "feminism", "civil rights")),
    GenreRule("Arts & Media", null, listOf("art", "music", "painting", "photography", "architecture", "design", "motion picture", "theater", "theatre", "opera")),
    GenreRule("Health & Living", null, listOf("health", "fitness", "diet", "psychology", "self-help", "parenting", "yoga", "mindfulness", "pets")),
    GenreRule("Travel & Places", null, listOf("travel", "voyages and travels", "description and travel", "guidebook", "geography")),
    GenreRule("Sport & Games", null, listOf("sports", "chess", "football", "baseball", "video games", "board games")),
    GenreRule("Poetry & Drama", null, listOf("poetry", "poems", "drama", "plays", "shakespeare", "sonnets")),
)

val BROAD_RULES = listOf(
    GenreRule("Fiction", null, listOf("fiction", "novel", "short stories", "literature", "literary")),
    GenreRule("Reference", null, listOf("reference", "encyclopedia", "dictionary", "almanac")),
)

val RULES = STORY_RULES + SUBJECT_RULES + FACT_RULES + BROAD_RULES

val AUDIENCE_RULES = listOf(
    GenreRule(null, "Young Adult", listOf("young adult", "teenage", "teenagers", "coming of age", "school stories")),
    GenreRule(null, "Children's", listOf("juvenile fiction", "juvenile literature", "juvenile", "children", "children's fiction", "children's stories", "picture book", "nursery", "child and youth", "middle grade")),
)

val TITLE_RULES = listOf(
    GenreRule("Computing", null, listOf("raspberry pi", "linux", "kali", "yocto", "docker", "kubernetes", "devops", "javascript", "python", "shell scripting", "shell programming", "grep", "sed and awk", "sql", "claude code", "agentic coding", "internet of things", "ethical hacker", "penetration testing", "machine learning", "arduino")),
    GenreRule("Reference", null, listOf("for dummies", "in easy steps", "missing manual")),
)

val SUBJECT_FORM = listOf(
    MANGA to listOf("manga", "japanese comic", "yaoi", "yuri", "shounen", "shoujo", "seinen", "josei"),
    MANHWA to listOf("manhwa", "manhua", "korean comic", "webtoon"),
    GRAPHIC to listOf("graphic novel"),
    COMICS to listOf("comic", "comic book", "comic strip", "cartoons", "superhero"),
)

val PATH_FORM = listOf(
    MANHWA to listOf("manhwa", "manhua", "webtoon"),
    MANGA to listOf("manga", "doujin", "shounen", "shoujo", "seinen", "josei"),
    GRAPHIC to listOf("graphic novel"),
    COMICS to listOf("comic", "comix"),
)

val CROSS_SUB = mapOf(
    "Humour" to "Humorous",
    "Romance" to "Romance",
    "Horror" to "Horror",
    "Mystery & Crime" to "Mystery",
    "Thriller & Adventure" to "Adventure",
    "Historical Fiction" to "Historical",
)

private val ISSUE = Regex("""(?:issue|#)\s*\d""", RegexOption.IGNORE_CASE)
private val NOT_WORD_KEEP_APOSTROPHE = Regex("""[^a-z0-9']+""")
private val NOT_WORD = Regex("""[^a-z0-9]+""")
private val HAS_PUNCTUATION = Regex("""[^a-z0-9]""")

private fun spaced(text: String?): String =
    " " + NOT_WORD_KEEP_APOSTROPHE.replace((text ?: "").lowercase(), " ").trim() + " "

private fun tight(text: String?): String = NOT_WORD.replace((text ?: "").lowercase(), "")

private fun has(word: String, spacedText: String, tightText: String, joined: Boolean): Boolean {
    if (joined || HAS_PUNCTUATION.containsMatchIn(word)) return tight(word) in tightText
    return " $word " in spacedText || " ${word}s " in spacedText
}

private fun match(text: String?, rules: List<GenreRule>, joined: Boolean = false): Pair<String?, String?>? {
    val s = spaced(text)
    val t = tight(text)
    for (rule in rules) {
        for (w in rule.words) {
            if (has(w, s, t, joined)) return rule.shelf to rule.sub
        }
    }
    return null
}

fun shelfRank(name: String?): Int {
    val i = SHELF_ORDER.indexOf(name)
    return if (i < 0) SHELF_ORDER.size else i
}

private fun pickShelf(votes: Map<String, Int>): String =
    votes.entries.minWith(
        compareBy(
            { if (it.key in BROAD_SHELVES) 1 else 0 },
            { -it.value },
            { shelfRank(it.key) },
        ),
    ).key

private fun pickSub(
    winner: String,
    votes: Map<String, Int>,
    subs: Map<String, Map<String, Int>>,
    audience: Map<String, Int>,
): String? {
    val picks = mutableListOf<Triple<Int, Int, String>>()
    subs[winner]?.forEach { (name, count) -> picks.add(Triple(0, -count, name)) }
    if (winner in FICTION_SHELVES) {
        for ((shelf, count) in votes) {
            val name = CROSS_SUB[shelf]
            if (name != null && shelf != winner) picks.add(Triple(1, -count, name))
        }
    }
    for ((name, count) in audience) picks.add(Triple(2, -count, name))
    if (picks.isEmpty()) return null
    return picks.minWith(compareBy({ it.first }, { it.second }, { it.third })).third
}

fun genreFromText(
    items: List<String?>,
    rules: List<GenreRule> = RULES,
    joined: Boolean = false,
): Pair<String?, String?> {
    val votes = LinkedHashMap<String, Int>()
    val subs = LinkedHashMap<String, LinkedHashMap<String, Int>>()
    val audience = LinkedHashMap<String, Int>()
    for (text in items) {
        val hit = match(text, rules, joined)
        if (hit != null) {
            val shelf = hit.first
            if (shelf != null) {
                votes[shelf] = (votes[shelf] ?: 0) + 1
                val sub = hit.second
                if (sub != null) {
                    val found = subs.getOrPut(shelf) { LinkedHashMap() }
                    found[sub] = (found[sub] ?: 0) + 1
                }
            }
        }
        val seen = match(text, AUDIENCE_RULES)
        if (seen != null) {
            val name = seen.second
            if (name != null) audience[name] = (audience[name] ?: 0) + 1
        }
    }
    if (votes.isEmpty()) {
        if (audience.isNotEmpty()) {
            val best = audience.entries.minWith(compareBy { -it.value }).key
            return "Children's & Young Adult" to best
        }
        return null to null
    }
    val winner = pickShelf(votes)
    return winner to pickSub(winner, votes, subs, audience)
}

fun cachedSubjects(bookId: String?): List<String>? {
    if (bookId.isNullOrBlank()) return null
    val path = File(LibraryCache.dir("meta"), "$bookId.json")
    if (!path.exists()) return null
    return try {
        val info = JSONObject(path.readText())
        val arr = info.optJSONArray("subjects") ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (_: Throwable) {
        null
    }
}

fun isComic(book: Book): Boolean {
    if (book.ext.lowercase() in COMIC_EXTS) return true
    val art = book.art ?: return false
    return art >= COMIC_ART && book.ink < COMIC_INK
}

private fun comicForm(book: Book, subjects: List<String>): String {
    for (text in subjects) {
        val s = spaced(text)
        val t = tight(text)
        for ((name, words) in SUBJECT_FORM) {
            if (words.any { has(it, s, t, false) }) return name
        }
    }
    if (ISSUE.containsMatchIn(File(book.path).name)) return COMICS
    if (ISSUE.containsMatchIn(book.title)) return COMICS
    val s = spaced(book.path)
    val t = tight(book.path)
    for ((name, words) in PATH_FORM) {
        if (words.any { has(it, s, t, false) }) return name
    }
    return COMICS
}

fun genreFromCatalogue(book: Book): Pair<String?, String?> =
    genreFromText(cachedSubjects(book.id) ?: emptyList())

fun shelveBook(book: Book, shelf: String?, sub: String?): Pair<String?, String?> {
    if (isComic(book)) {
        val form = comicForm(book, cachedSubjects(book.id) ?: emptyList())
        if (shelf != null && shelf !in FICTION_SHELVES) return shelf to form
        return COMIC_SHELF to form
    }
    return shelf to sub
}

fun classifyGenre(book: Book): Pair<String?, String?> {
    var (shelf, sub) = genreFromCatalogue(book)
    if (shelf == null) {
        val stem = File(book.path).nameWithoutExtension
        val named = listOf<String?>(book.title, stem)
        val byTitle = genreFromText(named, TITLE_RULES, joined = true)
        shelf = byTitle.first
        sub = byTitle.second
        if (shelf == null) {
            val byWords = genreFromText(named, RULES)
            shelf = byWords.first
            sub = byWords.second
        }
    }
    return shelveBook(book, shelf, sub)
}

private fun fillGenreByAuthor(books: List<Book>) {
    val votes = LinkedHashMap<String, LinkedHashMap<Pair<String, String?>, Int>>()
    for (b in books) {
        val author = b.author ?: continue
        val shelf = b.genre ?: continue
        val found = votes.getOrPut(author.lowercase()) { LinkedHashMap() }
        val key = shelf to b.subgenre
        found[key] = (found[key] ?: 0) + 1
    }
    for (b in books) {
        if (b.genre != null) continue
        val author = b.author ?: continue
        val found = votes[author.lowercase()] ?: continue
        val best = found.entries.maxByOrNull { it.value } ?: continue
        b.genre = best.key.first
        b.subgenre = best.key.second
    }
}

fun assignGenres(books: List<Book>) {
    for (b in books) {
        val (shelf, sub) = classifyGenre(b)
        b.genre = shelf
        b.subgenre = if (shelf != null) sub else null
    }
    fillGenreByAuthor(books)
    for (b in books) {
        if (b.genre == null) {
            b.genre = UNKNOWN_SHELF
            b.subgenre = null
        }
    }
}

fun mostCommonGenre(books: Collection<Book>): Pair<String, String?> {
    val votes = LinkedHashMap<String, Int>()
    val subs = LinkedHashMap<String, LinkedHashMap<String, Int>>()
    for (b in books) {
        val shelf = b.genre
        if (shelf == null || shelf == UNKNOWN_SHELF) continue
        votes[shelf] = (votes[shelf] ?: 0) + 1
        val sub = b.subgenre
        if (sub != null) {
            val found = subs.getOrPut(shelf) { LinkedHashMap() }
            found[sub] = (found[sub] ?: 0) + 1
        }
    }
    if (votes.isEmpty()) return UNKNOWN_SHELF to null
    val winner = votes.entries.minWith(compareBy({ -it.value }, { shelfRank(it.key) })).key
    val found = subs[winner]
    val sub = found?.entries?.minWith(compareBy({ -it.value }, { it.key }))?.key
    return winner to sub
}
