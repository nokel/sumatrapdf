package com.sumatrapdf.library

import java.io.File

object Genre {
    const val UNKNOWN = "Unsorted"
    const val COMIC_SHELF = "Comics & Manga"
    const val MANGA = "Manga"
    const val MANHWA = "Manhwa & Manhua"
    const val COMICS = "Comics"
    const val GRAPHIC = "Graphic Novels"

    private val COMIC_EXTS = listOf(".cbz", ".cbr")
    private const val COMIC_ART = 0.6
    private const val COMIC_INK = 1200

    val SHELF_ORDER = listOf(
        "Science Fiction", "Fantasy", "Horror", "Mystery & Crime",
        "Thriller & Adventure", "Romance", "Humour", "Historical Fiction",
        "Children's & Young Adult", "Fiction", COMIC_SHELF, "Computing",
        "Electronics & Making", "Engineering", "Mathematics",
        "Science & Nature", "Medicine & Health", "Business & Money",
        "Languages", "Cooking", "Crafts & Hobbies", "Study Aids", "History",
        "Biography & Memoir", "Society & Politics", "Religion & Philosophy",
        "Arts & Media", "Health & Living", "Travel & Places", "Sport & Games",
        "Poetry & Drama", "Reference", UNKNOWN,
    )

    private val FICTION_SHELVES = setOf(
        "Science Fiction", "Fantasy", "Horror", "Mystery & Crime",
        "Thriller & Adventure", "Romance", "Humour", "Historical Fiction",
        "Children's & Young Adult", "Fiction", COMIC_SHELF,
    )

    private val BROAD_SHELVES = setOf("Fiction", "Reference")

    private class Rule(val shelf: String?, val sub: String?, val words: List<String>)

    private val STORY_RULES = listOf(
        Rule("Science Fiction", "Dystopian", listOf("dystopia", "dystopias", "dystopian", "post apocalyptic", "post-apocalyptic", "apocalyptic")),
        Rule("Science Fiction", "Cyberpunk", listOf("cyberpunk")),
        Rule("Science Fiction", "Time Travel", listOf("time travel")),
        Rule("Science Fiction", "Space Opera", listOf("space opera", "interplanetary", "interstellar", "space flight", "imaginary voyages", "space colonies")),
        Rule("Science Fiction", "Alien Contact", listOf("extraterrestrial", "extraterrestres", "human-alien", "life on other planets", "flying saucers")),
        Rule("Science Fiction", "Robots & AI", listOf("robot", "android", "cyborg")),
        Rule("Science Fiction", null, listOf("science fiction", "sci-fi", "scifi")),
        Rule("Fantasy", "Myth & Legend", listOf("mythology", "fairy tale", "folklore", "legends", "arthurian")),
        Rule("Fantasy", "Magic & Witches", listOf("magic", "wizard", "witch", "sorcery", "sorcerer", "spells")),
        Rule("Fantasy", "Quests & Dragons", listOf("dragon", "quests", "epic fantasy", "sword and sorcery")),
        Rule("Fantasy", null, listOf("fantasy", "imaginary place", "imaginary places")),
        Rule("Horror", null, listOf("horror", "ghost", "vampire", "zombie", "haunted", "supernatural", "occult", "monsters")),
        Rule("Mystery & Crime", "Espionage", listOf("espionage", "spies", "secret service", "intelligence service")),
        Rule("Mystery & Crime", "True Crime", listOf("true crime")),
        Rule("Mystery & Crime", null, listOf("mystery", "detective", "crime", "murder", "suspense", "thriller", "noir", "police")),
        Rule("Thriller & Adventure", null, listOf("adventure", "adventurers", "survival", "sea stories", "westerns", "war stories", "aventures")),
        Rule("Romance", "Boys' Love", listOf("yaoi", "boys love", "boys' love", "shounen ai", "shonen ai")),
        Rule("Romance", "Girls' Love", listOf("yuri", "girls love", "girls' love")),
        Rule("Romance", "Erotica", listOf("erotic", "erotica", "hentai")),
        Rule("Romance", null, listOf("romance", "love stories")),
        Rule("Historical Fiction", null, listOf("historical fiction", "historical novel")),
        Rule("Humour", null, listOf("humorous", "humour", "humor", "satire", "satirical", "comedy", "wit and humor", "wit and humour", "parody", "funny")),
    )

    private val SUBJECT_RULES = listOf(
        Rule("Computing", null, listOf("computer", "computing", "programming", "software", "linux", "unix", "electronic data processing", "internet", "web site", "web development", "machine learning", "artificial intelligence", "raspberry pi", "microcomputer", "hacking", "penetration testing", "security measures", "operating system", "python (computer", "java (computer", "database", "algorithm", "machine theory", "natural language processing", "application software", "data processing", "information technology", "cryptography", "pocket computers", "programming languages")),
        Rule("Electronics & Making", null, listOf("electronics", "arduino", "robotics", "circuits", "microcontroller", "soldering", "3d printing", "makerspace")),
        Rule("Engineering", null, listOf("engineering", "mechanics", "construction", "aeronautics")),
        Rule("Mathematics", null, listOf("mathematics", "algebra", "geometry", "statistics", "calculus")),
        Rule("Science & Nature", null, listOf("science", "physics", "biology", "astronomy", "chemistry", "natural history", "evolution", "geology", "ecology", "botany", "zoology", "wildlife")),
        Rule("Medicine & Health", null, listOf("medicine", "medical", "anatomy", "nursing", "nutrition", "first aid")),
        Rule("Business & Money", null, listOf("business", "economics", "management", "finance", "marketing", "entrepreneurship", "leadership", "investing", "accounting")),
        Rule("Languages", null, listOf("language study", "grammar", "linguistics", "vocabulary", "foreign language")),
        Rule("Cooking", null, listOf("cooking", "cookery", "recipes", "baking")),
        Rule("Crafts & Hobbies", null, listOf("handicraft", "knitting", "woodwork", "gardening", "needlework", "origami")),
        Rule("Study Aids", null, listOf("study aids", "examinations", "textbook", "teaching", "curriculum", "education")),
        Rule("Reference", null, listOf("nonfiction", "non-fiction", "handbook", "manual", "how-to")),
    )

    private val FACT_RULES = listOf(
        Rule("History", null, listOf("history", "historical", "civilization", "archaeology", "world war", "antiquities", "ancient")),
        Rule("Biography & Memoir", null, listOf("biography", "autobiography", "memoir", "diaries", "correspondence", "personal narratives")),
        Rule("Religion & Philosophy", null, listOf("religion", "philosophy", "spiritual", "theology", "bible", "buddhism", "ethics", "islam", "christianity", "mysticism")),
        Rule("Society & Politics", null, listOf("political", "politics", "sociology", "social science", "government", "feminism", "civil rights")),
        Rule("Arts & Media", null, listOf("art", "music", "painting", "photography", "architecture", "design", "motion picture", "theater", "theatre", "opera")),
        Rule("Health & Living", null, listOf("health", "fitness", "diet", "psychology", "self-help", "parenting", "yoga", "mindfulness", "pets")),
        Rule("Travel & Places", null, listOf("travel", "voyages and travels", "description and travel", "guidebook", "geography")),
        Rule("Sport & Games", null, listOf("sports", "chess", "football", "baseball", "video games", "board games")),
        Rule("Poetry & Drama", null, listOf("poetry", "poems", "drama", "plays", "shakespeare", "sonnets")),
    )

    private val BROAD_RULES = listOf(
        Rule("Fiction", null, listOf("fiction", "novel", "short stories", "literature", "literary")),
        Rule("Reference", null, listOf("reference", "encyclopedia", "dictionary", "almanac")),
    )

    private val RULES = STORY_RULES + SUBJECT_RULES + FACT_RULES + BROAD_RULES

    private val AUDIENCE_RULES = listOf(
        Rule(null, "Young Adult", listOf("young adult", "teenage", "teenagers", "coming of age", "school stories")),
        Rule(null, "Children's", listOf("juvenile fiction", "juvenile literature", "juvenile", "children", "children's fiction", "children's stories", "picture book", "nursery", "child and youth", "middle grade")),
    )

    private val TITLE_RULES = listOf(
        Rule("Computing", null, listOf("raspberry pi", "linux", "kali", "yocto", "docker", "kubernetes", "devops", "javascript", "python", "shell scripting", "shell programming", "grep", "sed and awk", "sql", "claude code", "agentic coding", "internet of things", "ethical hacker", "penetration testing", "machine learning", "arduino")),
        Rule("Reference", null, listOf("for dummies", "in easy steps", "missing manual")),
    )

    private val PATH_FORM = listOf(
        MANHWA to listOf("manhwa", "manhua", "webtoon"),
        MANGA to listOf("manga", "doujin", "shounen", "shoujo", "seinen", "josei"),
        GRAPHIC to listOf("graphic novel"),
        COMICS to listOf("comic", "comix"),
    )

    private val CROSS_SUB = mapOf(
        "Humour" to "Humorous",
        "Romance" to "Romance",
        "Horror" to "Horror",
        "Mystery & Crime" to "Mystery",
        "Thriller & Adventure" to "Adventure",
        "Historical Fiction" to "Historical",
    )

    private val ISSUE = Regex("""(?:issue|#)\s*\d""", RegexOption.IGNORE_CASE)
    private val NOT_WORD = Regex("[^a-z0-9']+")
    private val NOT_ALNUM = Regex("[^a-z0-9]+")

    private fun spaced(text: String?) =
        " " + NOT_WORD.replace((text ?: "").lowercase(), " ").trim() + " "

    private fun tight(text: String?) =
        NOT_ALNUM.replace((text ?: "").lowercase(), "")

    private fun has(word: String, spacedText: String, tightText: String, joined: Boolean): Boolean {
        if (joined || NOT_ALNUM.containsMatchIn(word)) {
            return tightText.contains(tight(word))
        }
        return spacedText.contains(" $word ") || spacedText.contains(" ${word}s ")
    }

    private fun match(text: String?, rules: List<Rule>, joined: Boolean): Rule? {
        val sp = spaced(text)
        val ti = tight(text)
        for (rule in rules) {
            for (w in rule.words) {
                if (has(w, sp, ti, joined)) return rule
            }
        }
        return null
    }

    fun shelfRank(name: String?): Int {
        val at = SHELF_ORDER.indexOf(name)
        return if (at < 0) SHELF_ORDER.size else at
    }

    private fun pickShelf(votes: Map<String, Int>): String =
        votes.entries.sortedWith(
            compareBy({ it.key in BROAD_SHELVES }, { -it.value }, { shelfRank(it.key) })
        ).first().key

    private fun pickSub(
        winner: String,
        votes: Map<String, Int>,
        subs: Map<String, Map<String, Int>>,
        audience: Map<String, Int>,
    ): String? {
        val picks = ArrayList<Triple<Int, Int, String>>()
        subs[winner]?.forEach { (name, count) -> picks.add(Triple(0, -count, name)) }
        if (winner in FICTION_SHELVES) {
            for ((shelf, count) in votes) {
                val name = CROSS_SUB[shelf]
                if (name != null && shelf != winner) picks.add(Triple(1, -count, name))
            }
        }
        for ((name, count) in audience) picks.add(Triple(2, -count, name))
        if (picks.isEmpty()) return null
        return picks.sortedWith(
            compareBy({ it.first }, { it.second }, { it.third })
        ).first().third
    }

    private fun fromText(
        items: List<String?>,
        rules: List<Rule> = RULES,
        joined: Boolean = false,
    ): Pair<String?, String?> {
        val votes = HashMap<String, Int>()
        val subs = HashMap<String, HashMap<String, Int>>()
        val audience = HashMap<String, Int>()
        for (text in items) {
            val hit = match(text, rules, joined)
            if (hit?.shelf != null) {
                votes[hit.shelf] = (votes[hit.shelf] ?: 0) + 1
                if (hit.sub != null) {
                    val found = subs.getOrPut(hit.shelf) { HashMap() }
                    found[hit.sub] = (found[hit.sub] ?: 0) + 1
                }
            }
            val seen = match(text, AUDIENCE_RULES, false)
            if (seen?.sub != null) {
                audience[seen.sub] = (audience[seen.sub] ?: 0) + 1
            }
        }
        if (votes.isEmpty()) {
            if (audience.isNotEmpty()) {
                val best = audience.entries.sortedBy { -it.value }.first().key
                return Pair("Children's & Young Adult", best)
            }
            return Pair(null, null)
        }
        val winner = pickShelf(votes)
        return Pair(winner, pickSub(winner, votes, subs, audience))
    }

    fun isComic(book: Book): Boolean {
        if (book.ext.lowercase() in COMIC_EXTS) return true
        val art = book.art ?: return false
        return art >= COMIC_ART && book.ink < COMIC_INK
    }

    private fun comicForm(book: Book): String {
        if (ISSUE.containsMatchIn(File(book.path).name)) return COMICS
        if (ISSUE.containsMatchIn(book.title)) return COMICS
        val sp = spaced(book.path)
        val ti = tight(book.path)
        for ((name, words) in PATH_FORM) {
            if (words.any { has(it, sp, ti, false) }) return name
        }
        return COMICS
    }

    private fun shelve(book: Book, shelf: String?, sub: String?): Pair<String?, String?> {
        if (isComic(book)) {
            val form = comicForm(book)
            if (shelf != null && shelf !in FICTION_SHELVES) return Pair(shelf, form)
            return Pair(COMIC_SHELF, form)
        }
        return Pair(shelf, sub)
    }

    private fun classify(book: Book): Pair<String?, String?> {
        val stem = File(book.path).nameWithoutExtension
        val named = listOf(book.title, stem)
        var (shelf, sub) = fromText(named, TITLE_RULES, joined = true)
        if (shelf == null) {
            val plain = fromText(named)
            shelf = plain.first
            sub = plain.second
        }
        return shelve(book, shelf, sub)
    }

    private fun fillByAuthor(books: List<Book>) {
        val votes = HashMap<String, HashMap<Pair<String, String?>, Int>>()
        for (b in books) {
            val author = b.author ?: continue
            val shelf = b.genre ?: continue
            val found = votes.getOrPut(author.lowercase()) { HashMap() }
            val key = Pair(shelf, b.subgenre)
            found[key] = (found[key] ?: 0) + 1
        }
        for (b in books) {
            if (b.genre != null) continue
            val author = b.author ?: continue
            val found = votes[author.lowercase()] ?: continue
            val best = found.entries.maxByOrNull { it.value }!!.key
            b.genre = best.first
            b.subgenre = best.second
        }
    }

    fun assign(books: List<Book>) {
        for (b in books) {
            val (shelf, sub) = classify(b)
            b.genre = shelf
            b.subgenre = if (shelf != null) sub else null
        }
        fillByAuthor(books)
        for (b in books) {
            if (b.genre == null) {
                b.genre = UNKNOWN
                b.subgenre = null
            }
        }
    }

    fun mostCommon(books: List<Book>): Pair<String, String?> {
        val votes = HashMap<String, Int>()
        val subs = HashMap<String, HashMap<String, Int>>()
        for (b in books) {
            val shelf = b.genre
            if (shelf == null || shelf == UNKNOWN) continue
            votes[shelf] = (votes[shelf] ?: 0) + 1
            val sub = b.subgenre
            if (sub != null) {
                val found = subs.getOrPut(shelf) { HashMap() }
                found[sub] = (found[sub] ?: 0) + 1
            }
        }
        if (votes.isEmpty()) return Pair(UNKNOWN, null)
        val winner = votes.entries.sortedWith(
            compareBy({ -it.value }, { shelfRank(it.key) })
        ).first().key
        val found = subs[winner]
        val sub = found?.entries?.sortedWith(
            compareBy({ -it.value }, { it.key })
        )?.first()?.key
        return Pair(winner, sub)
    }
}
