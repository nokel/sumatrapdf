package com.sumatrapdf.reader.library

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The Kotlin name parser is checked against the Python it was ported
// from, not against hand-written expectations. parse_oracle_lib.json is
// the output of audiobook/library/shelf.py::_parse_name run over the 187
// real file names in the desktop's own library.json, produced by
// scratchpad/parse_oracle_lib.py with shelf.py's heavy imports stubbed so
// the real source executes.
class BookNameOracleTest {

    private data class Row(
        val stem: String,
        val title: String,
        val author: String?,
        val volumes: List<Int>,
        val year: Int?,
        val clean: String,
        val poor: Boolean,
    )

    private fun oracle(): List<Row> {
        val text = javaClass.classLoader!!
            .getResourceAsStream("parse_oracle_lib.json")!!
            .bufferedReader().readText()
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Row(
                stem = o.getString("stem"),
                title = o.getString("title"),
                author = if (o.isNull("author")) null else o.getString("author"),
                volumes = o.getJSONArray("volumes").let { v ->
                    (0 until v.length()).map { v.getInt(it) }
                },
                year = if (o.isNull("year")) null else o.getInt("year"),
                clean = o.getString("clean"),
                poor = o.getBoolean("poor"),
            )
        }
    }

    @Test
    fun theOracleIsTheRealLibrary() {
        val rows = oracle()
        assertEquals(187, rows.size)
        assertTrue(rows.count { it.author != null } > 0)
        assertTrue(rows.count { it.volumes.isNotEmpty() } > 0)
    }

    @Test
    fun parseNameMatchesThePythonOnEveryRealFileName() {
        val misses = mutableListOf<String>()
        for (r in oracle()) {
            val got = parseName(r.stem)
            if (got.title != r.title || got.author != r.author ||
                got.volumes != r.volumes || got.year != r.year
            ) {
                misses += "${r.stem}\n" +
                    "   want title=${r.title} author=${r.author} vol=${r.volumes} year=${r.year}\n" +
                    "   got  title=${got.title} author=${got.author} vol=${got.volumes} year=${got.year}"
            }
        }
        assertTrue(
            "${misses.size} of 187 file names parse differently to the Python:\n" +
                misses.take(20).joinToString("\n"),
            misses.isEmpty(),
        )
    }

    @Test
    fun cleanMatchesThePython() {
        val misses = mutableListOf<String>()
        for (r in oracle()) {
            val got = cleanName(r.stem)
            if (got != r.clean) misses += "${r.stem}: want '${r.clean}' got '$got'"
        }
        assertTrue(
            "${misses.size} of 187 clean differently:\n" + misses.take(20).joinToString("\n"),
            misses.isEmpty(),
        )
    }

    @Test
    fun poorStemDetectionMatchesThePython() {
        val misses = mutableListOf<String>()
        for (r in oracle()) {
            val got = stemIsPoor(r.stem)
            if (got != r.poor) misses += "${r.stem}: want ${r.poor} got $got"
        }
        assertTrue(
            "${misses.size} of 187 differ:\n" + misses.take(20).joinToString("\n"),
            misses.isEmpty(),
        )
    }
}
