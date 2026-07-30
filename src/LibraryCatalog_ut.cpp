/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"
#include "base/File.h"

#include "LibraryCatalog.h"

#include "base/UtAssert.h"

struct ParseCase {
    const char* stem;
    const char* title;
    const char* author;
    const char* volumes;
    int year;
};

static const ParseCase gParseCases[] = {
    {"Dune", "Dune", "", "", 0},
    {"DUNE", "Dune", "", "", 0},
    {"dune messiah", "Dune Messiah", "", "", 0},
    {"Vol. 3 - The Return", "Vol. 3 - the Return", "", "", 0},
    {"volume 12 Something", "Something", "", "12", 0},
    {"Book 4: The Reckoning", "Book 4: the Reckoning", "", "", 0},
    {"Part 2 - A Tale", "Part 2 - a Tale", "", "", 0},
    {"#7 The Seventh", "The Seventh", "", "7", 0},
    {"no 9 Ninth", "Ninth", "", "9", 0},
    {"01-The Invasion", "The Invasion", "", "1", 0},
    {"1, 2 & 3 - Omnibus", "Omnibus", "", "1,2,3", 0},
    {"3 6 12 - The Witches Trilogy - Terry Pratchett", "The Witches Trilogy", "Terry Pratchett", "3,6,12", 0},
    {"The Hobbit (1937)", "The Hobbit", "", "", 1937},
    {"The Hobbit 1937", "The Hobbit", "", "", 1937},
    {"Foundation (1951) - Isaac Asimov", "Foundation", "Isaac Asimov", "", 1951},
    {"Neuromancer by William Gibson", "Neuromancer", "William Gibson", "", 0},
    {"Neuromancer by the sea", "Neuromancer by the Sea", "", "", 0},
    {"Snow Crash - Neal Stephenson", "Snow Crash", "Neal Stephenson", "", 0},
    {"Neal Stephenson - Snow Crash", "Neal Stephenson", "Snow Crash", "", 0},
    {"Terry Pratchett - Guards Guards - Discworld", "Guards Guards - Discworld", "Terry Pratchett", "", 0},
    {"[retail] Dune (epub) {v2}", "Dune", "", "", 0},
    {"Dune [Frank Herbert] (2021 scan ocr)", "Dune", "", "", 2021},
    {"Some_Book_With_Underscores", "Some Book with Underscores", "", "", 0},
    {"Cryptonomicon - unabridged - calibre", "Cryptonomicon", "", "", 0},
    {"A Book vol 3", "A Book", "", "3", 0},
    {"A Book, Vol. 12", "A Book", "", "12", 0},
    {"A Book Part 4", "A Book", "", "4", 0},
    {"A Book Volume 100", "A Book", "", "100", 0},
    {"War and Peace - Leo Tolstoy - annas archive", "War and Peace", "Leo Tolstoy", "", 0},
    {"The Lord of the Rings \xe2\x80\x93 J R R Tolkien", "The Lord of the Rings", "J R R Tolkien", "", 0},
    {"The Lord of the Rings \xe2\x80\x94 J R R Tolkien", "The Lord of the Rings", "J R R Tolkien", "", 0},
    {"de la Cruz - The Book", "The Book", "de la Cruz", "", 0},
    {"van Gogh - Letters", "Letters", "van Gogh", "", 0},
    {"Book by A B C D E", "Book by a B C D E", "", "", 0},
    {"2001 A Space Odyssey", "A Space Odyssey", "", "", 2001},
    {"1984", "1984", "", "", 1984},
    {"Fahrenheit 451", "Fahrenheit 451", "", "", 0},
    {"The 39 Steps", "The 39 Steps", "", "", 0},
    {"vol 3", "Vol 3", "", "3", 0},
    {"12", "12", "", "", 0},
    {"   ", "", "", "", 0},
    {"The Book - ", "The Book", "", "", 0},
    {"- The Book", "The Book", "", "", 0},
    {"Mr. Smith - The Case", "The Case", "Mr. Smith", "", 0},
    {"THE GREAT GATSBY - F SCOTT FITZGERALD", "The Great Gatsby", "F SCOTT FITZGERALD", "", 0},
    {"harry potter and the chamber of secrets", "Harry Potter and the Chamber of Secrets", "", "", 0},
    {"X", "X", "", "", 0},
    {"A - B", "A - B", "", "", 0},
    {"Alpha - Beta - Gamma", "Alpha - Beta - Gamma", "", "", 0},
    {"Book 1 of 3 - The Start", "Book 1 of 3 - the Start", "", "", 0},
    {"Z-Library Dune", "Dune", "", "", 0},
    {"Dune libgen", "Dune", "", "", 0},
    {"Dune v3", "Dune", "", "", 0},
    {"Dune V12 Something", "Dune V12 Something", "", "", 0},
};

static TempStr VolumesToStrTemp(const Vec<int>& v) {
    str::Builder b;
    for (int i = 0; i < v.len; i++) {
        if (i > 0) {
            b.AppendChar(',');
        }
        b.Append(fmt("%d", v[i]));
    }
    return ToStrTemp(b);
}

static bool CheckOne(Str stem, Str wantTitle, Str wantAuthor, Str wantVolumes, int wantYear) {
    ParsedBookName got;
    LibraryParseBookName(stem, &got);
    Str gotTitle = got.title.len ? got.title : Str("");
    Str gotAuthor = got.author.len ? got.author : Str("");
    TempStr gotVolumes = VolumesToStrTemp(got.volumes);
    bool ok = str::Eq(gotTitle, wantTitle) && str::Eq(gotAuthor, wantAuthor) &&
              str::Eq(gotVolumes, wantVolumes) && got.year == wantYear;
    if (!ok) {
        printf("LibraryParseBookName('%s')\n", str::DupTemp(stem).s);
        printf("  title  want='%s' got='%s'\n", str::DupTemp(wantTitle).s, str::DupTemp(gotTitle).s);
        printf("  author want='%s' got='%s'\n", str::DupTemp(wantAuthor).s, str::DupTemp(gotAuthor).s);
        printf("  vols   want='%s' got='%s'\n", str::DupTemp(wantVolumes).s, gotVolumes.s);
        printf("  year   want=%d got=%d\n", wantYear, got.year);
    }
    return ok;
}

static void RunOracleFile(const char* path) {
    Str all = file::ReadFile(path);
    if (all.len == 0) {
        printf("oracle: could not read %s\n", path);
        return;
    }
    Str rest = all;
    Str line;
    int total = 0;
    int bad = 0;
    while (str::NextLine(rest, line, rest)) {
        if (line.len == 0) {
            continue;
        }
        StrVec cols;
        Str cur = line;
        for (int i = 0; i < 4; i++) {
            Str before;
            Str after;
            if (!str::CutChar(cur, '\t', &before, &after)) {
                break;
            }
            cols.Append(before);
            cur = after;
        }
        if (len(cols) != 4) {
            continue;
        }
        cols.Append(cur);
        total++;
        int wantYear = atoi(str::DupTemp(cols.At(4)).s);
        if (!CheckOne(cols.At(0), cols.At(1), cols.At(2), cols.At(3), wantYear)) {
            bad++;
        }
    }
    printf("oracle: %d rows, %d matched, %d mismatched\n", total, total - bad, bad);
    str::Free(all);
}

void LibraryCatalogTest() {
    for (const ParseCase& c : gParseCases) {
        utassert(CheckOne(c.stem, c.title, c.author, c.volumes, c.year));
    }
    const char* oracle = getenv("SUMATRA_PARSE_ORACLE");
    if (oracle) {
        RunOracleFile(oracle);
    }
}
