/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

struct ParsedBookName {
    Str title;
    Str author;
    Vec<int> volumes;
    int year = 0;

    ~ParsedBookName();
};

TempStr LibraryCleanNameTemp(Str text);
TempStr LibraryTitleCaseTemp(Str text);
bool LibraryLooksLikePerson(Str text);
void LibraryParseBookName(Str stem, ParsedBookName* out);
