/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"

#include "LibraryCatalog.h"

ParsedBookName::~ParsedBookName() {
    str::Free(title);
    str::Free(author);
}

static const char* kNoiseWords[] = {
    "ebook", "e-book", "retail",    "epub",     "mobi",  "azw3",   "pdf",
    "scan",  "scanned", "ocr",      "unabridged", "complete", "calibre",
    "z-library", "z-lib", "libgen", "annas archive", "anna's archive",
};

static const char* kSmallWords[] = {
    "a",  "an", "and", "as",  "at", "but",  "by", "for", "from", "in",
    "into", "of", "on", "or", "the", "to", "vs", "with",
};

static const char* kNameParticles[] = {"de", "van", "von", "der", "la"};

static const char* kTrimChars = " -,.;";

static int CLen(const char* s) {
    return (int)strlen(s);
}

static Str Sub(Str s, int start, int end) {
    return Str(s.s + start, end - start);
}

static bool IsWordChar(char c) {
    return str::IsAlNum(c) || c == '\'';
}

static bool AtWordBoundary(Str s, int start, int end) {
    if (start > 0 && IsWordChar(s.s[start - 1])) {
        return false;
    }
    if (end < s.len && IsWordChar(s.s[end])) {
        return false;
    }
    return true;
}

static bool IsLongDashAt(Str s, int i) {
    if (i + 3 > s.len) {
        return false;
    }
    u8 a = (u8)s.s[i];
    u8 b = (u8)s.s[i + 1];
    u8 c = (u8)s.s[i + 2];
    return a == 0xE2 && b == 0x80 && (c == 0x93 || c == 0x94);
}

static int DashLenAt(Str s, int i) {
    if (s.s[i] == '-') {
        return 1;
    }
    if (IsLongDashAt(s, i)) {
        return 3;
    }
    return 0;
}

static TempStr TrimEdgesTemp(Str s) {
    int start = 0;
    int end = s.len;
    while (start < end) {
        char c = s.s[start];
        if (c == ' ' || c == '\t') {
            start++;
            continue;
        }
        int dash = DashLenAt(s, start);
        if (dash > 0) {
            start += dash;
            continue;
        }
        if (str::ContainsChar(kTrimChars, c)) {
            start++;
            continue;
        }
        break;
    }
    while (end > start) {
        char last = s.s[end - 1];
        if (last == ' ' || last == '\t' || str::ContainsChar(kTrimChars, last)) {
            end--;
            continue;
        }
        if (end - start >= 3 && IsLongDashAt(s, end - 3)) {
            end -= 3;
            continue;
        }
        break;
    }
    return str::DupTemp(Sub(s, start, end));
}

static TempStr CollapseSpacesTemp(Str s) {
    str::Builder b;
    bool pendingSpace = false;
    bool any = false;
    for (int i = 0; i < s.len; i++) {
        char c = s.s[i];
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            pendingSpace = any;
            continue;
        }
        if (pendingSpace) {
            b.AppendChar(' ');
            pendingSpace = false;
        }
        b.AppendChar(c);
        any = true;
    }
    return ToStrTemp(b);
}

static TempStr DropBracketGroupsTemp(Str s) {
    str::Builder b;
    int depth = 0;
    for (int i = 0; i < s.len; i++) {
        char c = s.s[i];
        if (c == '[' || c == '{') {
            if (depth == 0) {
                b.AppendChar(' ');
            }
            depth++;
            continue;
        }
        if (c == ']' || c == '}') {
            if (depth > 0) {
                depth--;
            }
            continue;
        }
        if (depth == 0) {
            b.AppendChar(c);
        }
    }
    return ToStrTemp(b);
}

static bool NoiseWordAt(Str s, int i, int* lenOut) {
    for (const char* w : kNoiseWords) {
        int n = CLen(w);
        if (i + n > s.len) {
            continue;
        }
        if (!str::EqNI(Sub(s, i, i + n), w, n)) {
            continue;
        }
        if (!AtWordBoundary(s, i, i + n)) {
            continue;
        }
        *lenOut = n;
        return true;
    }
    if (i + 2 <= s.len && (s.s[i] == 'v' || s.s[i] == 'V') && str::IsDigit(s.s[i + 1])) {
        if (AtWordBoundary(s, i, i + 2)) {
            *lenOut = 2;
            return true;
        }
    }
    return false;
}

static TempStr DropNoiseTemp(Str s) {
    str::Builder b;
    int i = 0;
    while (i < s.len) {
        int n = 0;
        if (NoiseWordAt(s, i, &n)) {
            b.AppendChar(' ');
            i += n;
            continue;
        }
        b.AppendChar(s.s[i]);
        i++;
    }
    return ToStrTemp(b);
}

TempStr LibraryCleanNameTemp(Str text) {
    if (text.len == 0) {
        return str::DupTemp("");
    }
    TempStr s = DropBracketGroupsTemp(text);
    s = str::ReplaceTemp(s, "_", " ");
    s = str::ReplaceTemp(s, "\xE2\x80\x99", "'");
    s = DropNoiseTemp(s);
    s = CollapseSpacesTemp(s);
    return TrimEdgesTemp(s);
}

static bool IsSmallWord(Str w) {
    for (const char* s : kSmallWords) {
        if (str::EqI(w, s)) {
            return true;
        }
    }
    return false;
}

static bool IsNameParticle(Str w) {
    for (const char* s : kNameParticles) {
        if (str::EqI(w, s)) {
            return true;
        }
    }
    return false;
}

static void SplitWords(Str s, StrVec& out) {
    int i = 0;
    while (i < s.len) {
        while (i < s.len && (s.s[i] == ' ' || s.s[i] == '\t')) {
            i++;
        }
        int start = i;
        while (i < s.len && s.s[i] != ' ' && s.s[i] != '\t') {
            i++;
        }
        if (i > start) {
            out.Append(Sub(s, start, i));
        }
    }
}

static bool HasLower(Str s) {
    for (int i = 0; i < s.len; i++) {
        char c = s.s[i];
        if (c >= 'a' && c <= 'z') {
            return true;
        }
    }
    return false;
}

static bool HasUpperAfterFirst(Str s) {
    for (int i = 1; i < s.len; i++) {
        char c = s.s[i];
        if (c >= 'A' && c <= 'Z') {
            return true;
        }
    }
    return false;
}

TempStr LibraryTitleCaseTemp(Str text) {
    TempStr src = str::DupTemp(text);
    if (!HasLower(src)) {
        str::ToLowerInPlace(src);
    }
    StrVec words;
    SplitWords(src, words);
    str::Builder b;
    int n = len(words);
    for (int i = 0; i < n; i++) {
        Str w = words.At(i);
        if (i > 0) {
            b.AppendChar(' ');
        }
        if (HasUpperAfterFirst(w)) {
            b.Append(w);
            continue;
        }
        TempStr low = str::DupTemp(w);
        str::ToLowerInPlace(low);
        if (i > 0 && IsSmallWord(low)) {
            b.Append(low);
            continue;
        }
        if (low.len > 0 && low.s[0] >= 'a' && low.s[0] <= 'z') {
            low.s[0] = (char)(low.s[0] - 'a' + 'A');
        }
        b.Append(low);
    }
    return ToStrTemp(b);
}

bool LibraryLooksLikePerson(Str text) {
    if (text.len > 40) {
        return false;
    }
    StrVec toks;
    SplitWords(text, toks);
    int n = len(toks);
    if (n <= 1 || n > 4) {
        return false;
    }
    for (int i = 0; i < text.len; i++) {
        if (str::IsDigit(text.s[i])) {
            return false;
        }
    }
    if (IsSmallWord(toks.At(0))) {
        return false;
    }
    for (int i = 0; i < n; i++) {
        Str t = toks.At(i);
        char c = t.len > 0 ? t.s[0] : 0;
        bool upper = c >= 'A' && c <= 'Z';
        if (!upper && !IsNameParticle(t)) {
            return false;
        }
    }
    return true;
}

static int ParseIntAt(Str s, int start, int end) {
    TempStr t = str::DupTemp(Sub(s, start, end));
    return atoi(t.s);
}

static bool VolumeKeywordAt(Str s, int i, int* lenOut) {
    static const char* kWords[] = {"volume", "vol", "book", "part", "no"};
    for (const char* w : kWords) {
        int n = CLen(w);
        if (i + n > s.len) {
            continue;
        }
        if (str::EqN(Sub(s, i, i + n), w, n)) {
            *lenOut = n;
            return true;
        }
    }
    if (i < s.len && s.s[i] == '#') {
        *lenOut = 1;
        return true;
    }
    return false;
}

static void CollectNumbers(Str s, Vec<int>& out) {
    int i = 0;
    while (i < s.len) {
        if (!str::IsDigit(s.s[i])) {
            i++;
            continue;
        }
        int start = i;
        while (i < s.len && str::IsDigit(s.s[i])) {
            i++;
        }
        out.Append(ParseIntAt(s, start, i));
    }
}

static int MatchLeadingVolume(Str s, Vec<int>& volumes) {
    int i = 0;
    while (i < s.len && s.s[i] == ' ') {
        i++;
    }
    int kw = 0;
    if (VolumeKeywordAt(s, i, &kw)) {
        i += kw;
    }
    while (i < s.len && s.s[i] == ' ') {
        i++;
    }
    if (i < s.len && s.s[i] == '.') {
        i++;
    }
    while (i < s.len && s.s[i] == ' ') {
        i++;
    }
    int numStart = i;
    if (i >= s.len || !str::IsDigit(s.s[i])) {
        return 0;
    }
    int digits = 0;
    while (i < s.len && str::IsDigit(s.s[i])) {
        i++;
        digits++;
    }
    if (digits > 3) {
        return 0;
    }
    for (;;) {
        int save = i;
        int afterWs = i;
        while (afterWs < s.len && s.s[afterWs] == ' ') {
            afterWs++;
        }
        int j = i;
        if (afterWs < s.len && (s.s[afterWs] == ',' || s.s[afterWs] == '&')) {
            j = afterWs + 1;
        }
        int spaces = 0;
        while (j < s.len && s.s[j] == ' ') {
            j++;
            spaces++;
        }
        if (spaces == 0 || j >= s.len || !str::IsDigit(s.s[j])) {
            i = save;
            break;
        }
        digits = 0;
        while (j < s.len && str::IsDigit(s.s[j])) {
            j++;
            digits++;
        }
        if (digits > 3) {
            i = save;
            break;
        }
        i = j;
    }
    int numEnd = i;
    int afterNum = i;
    while (i < s.len && s.s[i] == ' ') {
        i++;
    }
    bool sawSep = false;
    if (i < s.len) {
        char c = s.s[i];
        int dash = DashLenAt(s, i);
        if (dash > 0) {
            i += dash;
            sawSep = true;
        } else if (c == ':' || c == '.' || c == ')' || c == ']') {
            i++;
            sawSep = true;
        }
    }
    if (sawSep) {
        while (i < s.len && s.s[i] == ' ') {
            i++;
        }
    } else {
        if (afterNum >= s.len || s.s[afterNum] != ' ') {
            return 0;
        }
        i = afterNum;
        while (i < s.len && s.s[i] == ' ') {
            i++;
        }
    }
    CollectNumbers(Sub(s, numStart, numEnd), volumes);
    return i;
}

static bool FindYear(Str s, int* startOut, int* endOut, int* yearOut) {
    for (int i = 0; i + 4 <= s.len; i++) {
        char a = s.s[i];
        char b = s.s[i + 1];
        bool ok = false;
        if (a == '1' && b >= '5' && b <= '9') {
            ok = str::IsDigit(s.s[i + 2]) && str::IsDigit(s.s[i + 3]);
        } else if (a == '2' && b == '0') {
            ok = str::IsDigit(s.s[i + 2]) && str::IsDigit(s.s[i + 3]);
        }
        if (!ok) {
            continue;
        }
        if (!AtWordBoundary(s, i, i + 4)) {
            continue;
        }
        int start = i;
        int end = i + 4;
        if (start > 0 && s.s[start - 1] == '(') {
            start--;
        }
        if (end < s.len && s.s[end] == ')') {
            end++;
        }
        *startOut = start;
        *endOut = end;
        *yearOut = ParseIntAt(s, i, i + 4);
        return true;
    }
    return false;
}

static void SplitOnSpacedDash(Str s, StrVec& out) {
    int start = 0;
    int i = 0;
    while (i < s.len) {
        if (s.s[i] != ' ') {
            i++;
            continue;
        }
        int j = i;
        while (j < s.len && s.s[j] == ' ') {
            j++;
        }
        int dash = j < s.len ? DashLenAt(s, j) : 0;
        if (dash == 0) {
            i = j > i ? j : i + 1;
            continue;
        }
        int k = j + dash;
        int spaces = 0;
        while (k < s.len && s.s[k] == ' ') {
            k++;
            spaces++;
        }
        if (spaces == 0) {
            i = k;
            continue;
        }
        TempStr piece = TrimEdgesTemp(Sub(s, start, i));
        if (piece.len > 0) {
            out.Append(piece);
        }
        start = k;
        i = k;
    }
    TempStr last = TrimEdgesTemp(Sub(s, start, s.len));
    if (last.len > 0) {
        out.Append(last);
    }
}

static TempStr JoinWithDashTemp(const StrVec& parts, int from, int to) {
    str::Builder b;
    for (int i = from; i < to; i++) {
        if (i > from) {
            b.Append(" - ");
        }
        b.Append(parts.At(i));
    }
    return ToStrTemp(b);
}

static int MatchTrailingByAuthor(Str s, TempStr* authorOut) {
    int end = s.len;
    while (end > 0 && s.s[end - 1] == ' ') {
        end--;
    }
    int wordStart = end;
    for (int words = 0; words < 4; words++) {
        int j = wordStart;
        while (j > 0 && s.s[j - 1] != ' ') {
            j--;
        }
        if (j == wordStart) {
            break;
        }
        int beforeSpaces = j;
        while (beforeSpaces > 0 && s.s[beforeSpaces - 1] == ' ') {
            beforeSpaces--;
        }
        if (beforeSpaces >= 2 && str::EqNI(Sub(s, beforeSpaces - 2, beforeSpaces), "by", 2) &&
            AtWordBoundary(s, beforeSpaces - 2, beforeSpaces)) {
            TempStr cand = TrimEdgesTemp(Sub(s, j, end));
            if (LibraryLooksLikePerson(cand)) {
                *authorOut = cand;
                return beforeSpaces - 2;
            }
            return -1;
        }
        wordStart = beforeSpaces;
        if (wordStart <= 0) {
            break;
        }
    }
    return -1;
}

static int MatchTrailingVolume(Str s, int* volOut) {
    int i = s.len;
    while (i > 0 && str::IsDigit(s.s[i - 1])) {
        i--;
    }
    int digits = s.len - i;
    if (digits < 1 || digits > 3) {
        return -1;
    }
    int numStart = i;
    while (i > 0 && s.s[i - 1] == ' ') {
        i--;
    }
    if (i > 0 && s.s[i - 1] == '.') {
        i--;
    }
    static const char* kWords[] = {"volume", "vol", "book", "part"};
    for (const char* w : kWords) {
        int n = CLen(w);
        if (i - n < 0) {
            continue;
        }
        if (!str::EqNI(Sub(s, i - n, i), w, n)) {
            continue;
        }
        if (i - n > 0 && IsWordChar(s.s[i - n - 1])) {
            continue;
        }
        int cut = i - n;
        while (cut > 0 && (s.s[cut - 1] == ' ' || s.s[cut - 1] == ',')) {
            cut--;
        }
        *volOut = ParseIntAt(s, numStart, s.len);
        return cut;
    }
    return -1;
}

void LibraryParseBookName(Str stem, ParsedBookName* out) {
    TempStr cleaned = LibraryCleanNameTemp(stem);
    TempStr s = str::DupTemp(cleaned);

    int consumed = MatchLeadingVolume(s, out->volumes);
    if (consumed > 0) {
        TempStr rest = TrimEdgesTemp(Sub(s, consumed, s.len));
        if (rest.len > 0) {
            s = rest;
        } else {
            out->volumes.Reset();
        }
    } else {
        out->volumes.Reset();
    }

    int ys = 0;
    int ye = 0;
    int year = 0;
    if (FindYear(s, &ys, &ye, &year)) {
        out->year = year;
        str::Builder b;
        b.Append(Sub(s, 0, ys));
        b.AppendChar(' ');
        b.Append(Sub(s, ye, s.len));
        s = ToStrTemp(b);
    }

    s = str::ReplaceTemp(s, "(", " ");
    s = str::ReplaceTemp(s, ")", " ");
    s = CollapseSpacesTemp(s);
    s = TrimEdgesTemp(s);

    StrVec parts;
    SplitOnSpacedDash(s, parts);
    int n = len(parts);
    if (n >= 2) {
        Str first = parts.At(0);
        Str last = parts.At(n - 1);
        bool firstPerson = LibraryLooksLikePerson(first);
        bool lastPerson = LibraryLooksLikePerson(last);
        if (lastPerson && !firstPerson) {
            out->author = str::Dup(last);
            s = JoinWithDashTemp(parts, 0, n - 1);
        } else if (firstPerson && !lastPerson) {
            out->author = str::Dup(first);
            s = JoinWithDashTemp(parts, 1, n);
        } else if (lastPerson) {
            out->author = str::Dup(last);
            s = JoinWithDashTemp(parts, 0, n - 1);
        } else {
            s = JoinWithDashTemp(parts, 0, n);
        }
    }

    if (out->author.len == 0) {
        TempStr byAuthor;
        int cut = MatchTrailingByAuthor(s, &byAuthor);
        if (cut >= 0) {
            out->author = str::Dup(byAuthor);
            s = TrimEdgesTemp(Sub(s, 0, cut));
        }
    }

    int trailVol = 0;
    int cutAt = MatchTrailingVolume(s, &trailVol);
    if (cutAt >= 0) {
        if (out->volumes.IsEmpty()) {
            out->volumes.Append(trailVol);
        }
        int end = cutAt;
        while (end > 0 && s.s[end - 1] == ' ') {
            end--;
        }
        s = str::DupTemp(Sub(s, 0, end));
    }

    if (s.len == 0) {
        s = cleaned;
    }
    out->title = str::Dup(LibraryTitleCaseTemp(s));
}
