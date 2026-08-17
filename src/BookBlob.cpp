/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include <Lzma2Enc.h>
#include <Lzma2Dec.h>
#include "base/Crypto.h"

#include "BookBlob.h"

static const u8 kBlobMagic[4] = {'S', 'B', 'B', '1'};

struct BlobWriter {
    Vec<u8> out;

    void Raw(const void* d, int n) {
        if (n > 0) {
            out.Append((const u8*)d, n);
        }
    }

    void U(u64 v) {
        for (;;) {
            u8 piece = (u8)(v & 0x7f);
            v >>= 7;
            if (v) {
                out.Append((u8)(piece | 0x80));
            } else {
                out.Append(piece);
                return;
            }
        }
    }

    void UInt(i64 v) {
        ReportIf(v < 0);
        U((u64)v);
    }

    void SInt(i64 v) { U(((u64)v << 1) ^ (u64)(v >> 63)); }

    void BlobOf(const void* d, int n) {
        UInt(n);
        Raw(d, n);
    }

    void Real(double v) {
        u64 bits = 0;
        memcpy(&bits, &v, sizeof(bits));
        u8 b[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (u8)((bits >> (8 * i)) & 0xff);
        }
        Raw(b, 8);
    }
};

struct BlobStrings {
    StrVec order;
    Vec<u32> hashes;

    static u32 Hash(const char* s, int n) {
        u32 h = 2166136261u;
        for (int i = 0; i < n; i++) {
            h ^= (u32)(u8)s[i];
            h *= 16777619u;
        }
        return h;
    }

    int Put(const char* s) {
        if (!s) {
            return 0;
        }
        int n = (int)strlen(s);
        u32 h = Hash(s, n);
        for (int i = 0; i < hashes.len; i++) {
            if (hashes[i] != h) {
                continue;
            }
            Str have = order.At(i);
            if (have.len == n && (n == 0 || memcmp(have.s, s, (size_t)n) == 0)) {
                return i + 1;
            }
        }
        order.Append(Str(s, n));
        hashes.Append(h);
        return hashes.len;
    }

    void Write(BlobWriter& w) {
        w.UInt(order.size);
        for (int i = 0; i < order.size; i++) {
            Str s = order.At(i);
            w.BlobOf(s.s, s.len);
        }
    }
};

struct BlobReader {
    const u8* data = nullptr;
    int size = 0;
    int at = 0;
    bool bad = false;

    BlobReader(const u8* d, int n) : data(d), size(n) {}

    u64 U() {
        int shift = 0;
        u64 value = 0;
        for (;;) {
            if (at >= size) {
                bad = true;
                return 0;
            }
            u8 piece = data[at++];
            value |= (u64)(piece & 0x7f) << shift;
            if (!(piece & 0x80)) {
                return value;
            }
            shift += 7;
            if (shift > 63) {
                bad = true;
                return 0;
            }
        }
    }

    i64 S() {
        u64 raw = U();
        return (raw & 1) ? -(i64)((raw + 1) >> 1) : (i64)(raw >> 1);
    }

    int Int() {
        u64 v = U();
        if (v > (u64)INT_MAX) {
            bad = true;
            return 0;
        }
        return (int)v;
    }

    int SIntAsInt() {
        i64 v = S();
        if (v < INT_MIN || v > INT_MAX) {
            bad = true;
            return 0;
        }
        return (int)v;
    }

    int Count() {
        int n = Int();
        if (bad || n < 0 || n > size - at) {
            bad = true;
            return 0;
        }
        return n;
    }

    const u8* Raw(int n) {
        if (bad || n < 0 || n > size - at) {
            bad = true;
            return nullptr;
        }
        const u8* res = data + at;
        at += n;
        return res;
    }

    double Real() {
        const u8* b = Raw(8);
        if (!b) {
            return 0;
        }
        u64 bits = 0;
        for (int i = 7; i >= 0; i--) {
            bits = (bits << 8) | (u64)b[i];
        }
        double v = 0;
        memcpy(&v, &bits, sizeof(v));
        return v;
    }
};

static void ReadUInts(BlobReader& r, Vec<int>& out) {
    out.Reset();
    int n = r.Count();
    for (int i = 0; i < n && !r.bad; i++) {
        out.Append(r.Int());
    }
}

static void ReadSInts(BlobReader& r, Vec<int>& out) {
    out.Reset();
    int n = r.Count();
    for (int i = 0; i < n && !r.bad; i++) {
        out.Append(r.SIntAsInt());
    }
}

static void ReadDeltas(BlobReader& r, Vec<int>& out) {
    out.Reset();
    int n = r.Count();
    int previous = 0;
    for (int i = 0; i < n && !r.bad; i++) {
        previous += r.SIntAsInt();
        out.Append(previous);
    }
}

static void ReadReals(BlobReader& r, Vec<double>& out) {
    out.Reset();
    Vec<double> order;
    int n = r.Count();
    for (int i = 0; i < n && !r.bad; i++) {
        order.Append(r.Real());
    }
    int count = r.Count();
    for (int i = 0; i < count && !r.bad; i++) {
        int at = r.Int();
        if (at < 0 || at >= order.len) {
            r.bad = true;
            return;
        }
        out.Append(order[at]);
    }
}

static const char* StrAt(const Vec<const char*>& table, int idx, BlobReader& r) {
    if (idx < 0 || idx >= table.len) {
        r.bad = true;
        return nullptr;
    }
    return table[idx];
}

static void ReadStrings(BlobReader& r, const Vec<const char*>& table, Vec<const char*>& out) {
    out.Reset();
    int n = r.Count();
    for (int i = 0; i < n && !r.bad; i++) {
        out.Append(StrAt(table, r.Int(), r));
    }
}

static void WriteIdentity(BlobWriter& w, BlobStrings& s, const BlobIdentity& it) {
    w.UInt(s.Put(it.fingerprint));
    w.BlobOf(it.textMd5.LendData(), it.textMd5.len);
    w.UInt(it.textLength);
    w.UInt(it.offsetBasis);
    w.UInt(s.Put(it.title));
    w.UInt(s.Put(it.author));
    w.UInt(s.Put(it.source));
    w.UInt(it.pages);
}

static void ReadIdentity(BlobReader& r, const Vec<const char*>& t, BlobIdentity& it) {
    it.fingerprint = StrAt(t, r.Int(), r);
    int n = r.Count();
    const u8* md5 = r.Raw(n);
    if (md5) {
        it.textMd5.Append(md5, n);
    }
    it.textLength = (i64)r.U();
    it.offsetBasis = r.Int();
    it.title = StrAt(t, r.Int(), r);
    it.author = StrAt(t, r.Int(), r);
    it.source = StrAt(t, r.Int(), r);
    it.pages = r.Int();
}

static void WriteShelf(BlobWriter& w, BlobStrings& s, const BlobShelf& it) {
    w.UInt(s.Put(it.genre));
    w.UInt(s.Put(it.subgenre));
    w.UInt(s.Put(it.series));
    w.UInt(s.Put(it.seriesParent));
    w.SInt(it.seriesIndex);
    w.UInt(s.Put(it.collection));
    w.UInt(it.partitions.len);
    for (int i = 0; i < it.partitions.len; i++) {
        w.UInt(s.Put(it.partitions[i]));
    }
    w.UInt(it.tags.len);
    for (int i = 0; i < it.tags.len; i++) {
        w.UInt(s.Put(it.tags[i]));
    }
}

static void ReadShelf(BlobReader& r, const Vec<const char*>& t, BlobShelf& it) {
    it.genre = StrAt(t, r.Int(), r);
    it.subgenre = StrAt(t, r.Int(), r);
    it.series = StrAt(t, r.Int(), r);
    it.seriesParent = StrAt(t, r.Int(), r);
    it.seriesIndex = r.SIntAsInt();
    it.collection = StrAt(t, r.Int(), r);
    ReadStrings(r, t, it.partitions);
    ReadStrings(r, t, it.tags);
}

static void WriteCover(BlobWriter& w, BlobStrings& s, const BlobCover& it) {
    w.UInt(it.kind);
    if (it.kind == kBlobCoverImage) {
        w.UInt(s.Put(it.format));
        w.BlobOf(it.data.LendData(), it.data.len);
    } else if (it.kind == kBlobCoverPage) {
        w.UInt(it.page);
        w.Real(it.x0);
        w.Real(it.y0);
        w.Real(it.x1);
        w.Real(it.y1);
    }
}

static void ReadCover(BlobReader& r, const Vec<const char*>& t, BlobCover& it) {
    it.kind = r.Int();
    if (it.kind == kBlobCoverImage) {
        it.format = StrAt(t, r.Int(), r);
        int n = r.Count();
        const u8* d = r.Raw(n);
        if (d) {
            it.data.Append(d, n);
        }
    } else if (it.kind == kBlobCoverPage) {
        it.page = r.Int();
        it.x0 = r.Real();
        it.y0 = r.Real();
        it.x1 = r.Real();
        it.y1 = r.Real();
    }
}

static void WriteCast(BlobWriter& w, BlobStrings& s, const BlobCast& it) {
    w.UInt(s.Put(it.narratorVoice));
    w.UInt(it.people.len);
    for (int i = 0; i < it.people.len; i++) {
        w.UInt(s.Put(it.people[i].name));
    }
    w.UInt(it.people.len);
    for (int i = 0; i < it.people.len; i++) {
        w.UInt(it.people[i].lines);
    }
    w.UInt(it.people.len);
    for (int i = 0; i < it.people.len; i++) {
        w.UInt(s.Put(it.people[i].voice));
    }
    for (int i = 0; i < it.people.len; i++) {
        const BlobPerson& p = it.people[i];
        w.UInt(p.aliasCount);
        for (int k = 0; k < p.aliasCount; k++) {
            w.UInt(s.Put(it.aliases[p.aliasAt + k]));
        }
    }
}

static void ReadCast(BlobReader& r, const Vec<const char*>& t, BlobCast& it) {
    it.narratorVoice = StrAt(t, r.Int(), r);
    Vec<const char*> names;
    Vec<const char*> voices;
    Vec<int> lines;
    ReadStrings(r, t, names);
    ReadUInts(r, lines);
    ReadStrings(r, t, voices);
    if (r.bad || lines.len != names.len || voices.len != names.len) {
        r.bad = true;
        return;
    }
    for (int i = 0; i < names.len && !r.bad; i++) {
        Vec<const char*> aliases;
        ReadStrings(r, t, aliases);
        BlobPerson p;
        p.name = names[i];
        p.lines = lines[i];
        p.voice = voices[i];
        p.aliasAt = it.aliases.len;
        p.aliasCount = aliases.len;
        for (int k = 0; k < aliases.len; k++) {
            it.aliases.Append(aliases[k]);
        }
        it.people.Append(p);
    }
}

static void WriteSpeakers(BlobWriter& w, const Vec<BlobQuote>& quotes) {
    int n = quotes.len;
    w.UInt(n);
    i64 previous = 0;
    for (int i = 0; i < n; i++) {
        w.SInt((i64)quotes[i].start - previous);
        previous = quotes[i].start;
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt((i64)quotes[i].end - quotes[i].start);
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.SInt((i64)quotes[i].mentionStart - quotes[i].start);
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt((i64)quotes[i].mentionEnd - quotes[i].mentionStart);
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.SInt(quotes[i].character);
    }
}

static void ReadSpeakers(BlobReader& r, Vec<BlobQuote>& out) {
    Vec<int> starts;
    Vec<int> lengths;
    Vec<int> mentionOffsets;
    Vec<int> mentionLengths;
    Vec<int> characters;
    ReadDeltas(r, starts);
    ReadUInts(r, lengths);
    ReadSInts(r, mentionOffsets);
    ReadUInts(r, mentionLengths);
    ReadSInts(r, characters);
    int n = starts.len;
    if (r.bad || lengths.len != n || mentionOffsets.len != n || mentionLengths.len != n || characters.len != n) {
        r.bad = true;
        return;
    }
    for (int i = 0; i < n; i++) {
        BlobQuote q;
        q.start = starts[i];
        q.end = starts[i] + lengths[i];
        q.mentionStart = starts[i] + mentionOffsets[i];
        q.mentionEnd = q.mentionStart + mentionLengths[i];
        q.character = characters[i];
        out.Append(q);
    }
}

static void WriteEntities(BlobWriter& w, BlobStrings& s, const Vec<BlobMention>& mentions) {
    int n = mentions.len;
    w.UInt(n);
    i64 previous = 0;
    for (int i = 0; i < n; i++) {
        w.SInt((i64)mentions[i].start - previous);
        previous = mentions[i].start;
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt((i64)mentions[i].end - mentions[i].start);
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(mentions[i].coref);
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(s.Put(mentions[i].prop));
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(s.Put(mentions[i].cat));
    }
}

static void ReadEntities(BlobReader& r, const Vec<const char*>& t, Vec<BlobMention>& out) {
    Vec<int> starts;
    Vec<int> lengths;
    Vec<int> corefs;
    Vec<const char*> props;
    Vec<const char*> cats;
    ReadDeltas(r, starts);
    ReadUInts(r, lengths);
    ReadUInts(r, corefs);
    ReadStrings(r, t, props);
    ReadStrings(r, t, cats);
    int n = starts.len;
    if (r.bad || lengths.len != n || corefs.len != n || props.len != n || cats.len != n) {
        r.bad = true;
        return;
    }
    for (int i = 0; i < n; i++) {
        BlobMention m;
        m.start = starts[i];
        m.end = starts[i] + lengths[i];
        m.coref = corefs[i];
        m.prop = props[i];
        m.cat = cats[i];
        out.Append(m);
    }
}

static void WriteLore(BlobWriter& w, BlobStrings& s, const Vec<BlobFact>& facts, const Vec<BlobEvidence>& evidence) {
    int n = facts.len;
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(s.Put(facts[i].subject));
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(s.Put(facts[i].predicate));
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(s.Put(facts[i].object));
    }

    Vec<u64> keys;
    Vec<int> ids;
    for (int i = 0; i < n; i++) {
        u64 key = 0;
        double v = facts[i].confidence;
        memcpy(&key, &v, sizeof(key));
        int found = -1;
        for (int k = 0; k < keys.len; k++) {
            if (keys[k] == key) {
                found = k;
                break;
            }
        }
        if (found < 0) {
            found = keys.len;
            keys.Append(key);
        }
        ids.Append(found);
    }
    w.UInt(keys.len);
    for (int i = 0; i < keys.len; i++) {
        double v = 0;
        u64 key = keys[i];
        memcpy(&v, &key, sizeof(v));
        w.Real(v);
    }
    w.UInt(ids.len);
    for (int i = 0; i < ids.len; i++) {
        w.UInt(ids[i]);
    }

    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(facts[i].count);
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(facts[i].inferred ? 1 : 0);
    }

    Vec<int> located;
    Vec<int> derived;
    Vec<int> kinds;
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(facts[i].evidenceCount);
    }
    for (int i = 0; i < n; i++) {
        const BlobFact& f = facts[i];
        for (int k = 0; k < f.evidenceCount; k++) {
            int at = f.evidenceAt + k;
            if (evidence[at].offset < 0) {
                derived.Append(at);
                kinds.Append(1);
            } else {
                located.Append(at);
                kinds.Append(0);
            }
        }
    }
    w.UInt(kinds.len);
    for (int i = 0; i < kinds.len; i++) {
        w.UInt(kinds[i]);
    }

    w.UInt(located.len);
    i64 previous = 0;
    for (int i = 0; i < located.len; i++) {
        w.SInt((i64)evidence[located[i]].offset - previous);
        previous = evidence[located[i]].offset;
    }
    w.UInt(located.len);
    for (int i = 0; i < located.len; i++) {
        w.UInt(evidence[located[i]].page);
    }
    w.UInt(located.len);
    for (int i = 0; i < located.len; i++) {
        w.UInt(evidence[located[i]].para);
    }
    w.UInt(derived.len);
    for (int i = 0; i < derived.len; i++) {
        w.UInt(s.Put(evidence[derived[i]].note));
    }
}

static void ReadLore(BlobReader& r, const Vec<const char*>& t, Vec<BlobFact>& facts, Vec<BlobEvidence>& evidence) {
    Vec<const char*> subjects;
    Vec<const char*> predicates;
    Vec<const char*> objects;
    Vec<double> confidences;
    Vec<int> counts;
    Vec<int> inferred;
    Vec<int> sizes;
    Vec<int> kinds;
    Vec<int> offsets;
    Vec<int> pages;
    Vec<int> paras;
    Vec<const char*> notes;

    ReadStrings(r, t, subjects);
    ReadStrings(r, t, predicates);
    ReadStrings(r, t, objects);
    ReadReals(r, confidences);
    ReadUInts(r, counts);
    ReadUInts(r, inferred);
    ReadUInts(r, sizes);
    ReadUInts(r, kinds);
    ReadDeltas(r, offsets);
    ReadUInts(r, pages);
    ReadUInts(r, paras);
    ReadStrings(r, t, notes);

    int n = subjects.len;
    if (r.bad || predicates.len != n || objects.len != n || confidences.len != n || counts.len != n ||
        inferred.len != n || sizes.len != n) {
        r.bad = true;
        return;
    }
    if (pages.len != offsets.len || paras.len != offsets.len) {
        r.bad = true;
        return;
    }

    int here = 0;
    int there = 0;
    int flat = 0;
    for (int i = 0; i < n; i++) {
        BlobFact f;
        f.subject = subjects[i];
        f.predicate = predicates[i];
        f.object = objects[i];
        f.confidence = confidences[i];
        f.count = counts[i];
        f.inferred = inferred[i] != 0;
        f.evidenceAt = evidence.len;
        f.evidenceCount = sizes[i];
        for (int k = 0; k < sizes[i]; k++) {
            if (flat >= kinds.len) {
                r.bad = true;
                return;
            }
            BlobEvidence e;
            e.offset = -1;
            e.page = 0;
            e.para = 0;
            e.note = nullptr;
            if (kinds[flat]) {
                if (there >= notes.len) {
                    r.bad = true;
                    return;
                }
                e.note = notes[there++];
            } else {
                if (here >= offsets.len) {
                    r.bad = true;
                    return;
                }
                e.offset = offsets[here];
                e.page = pages[here];
                e.para = paras[here];
                here++;
            }
            flat++;
            evidence.Append(e);
        }
        facts.Append(f);
    }
}

static void WriteChapters(BlobWriter& w, BlobStrings& s, const Vec<BlobChapter>& chapters) {
    int n = chapters.len;
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(s.Put(chapters[i].title));
    }
    w.UInt(n);
    i64 previous = 0;
    for (int i = 0; i < n; i++) {
        w.SInt((i64)chapters[i].offset - previous);
        previous = chapters[i].offset;
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(chapters[i].page);
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(chapters[i].depth);
    }
}

static void ReadChapters(BlobReader& r, const Vec<const char*>& t, Vec<BlobChapter>& out) {
    Vec<const char*> titles;
    Vec<int> offsets;
    Vec<int> pages;
    Vec<int> depths;
    ReadStrings(r, t, titles);
    ReadDeltas(r, offsets);
    ReadUInts(r, pages);
    ReadUInts(r, depths);
    int n = titles.len;
    if (r.bad || offsets.len != n || pages.len != n || depths.len != n) {
        r.bad = true;
        return;
    }
    for (int i = 0; i < n; i++) {
        BlobChapter c;
        c.title = titles[i];
        c.offset = offsets[i];
        c.page = pages[i];
        c.depth = depths[i];
        out.Append(c);
    }
}

static void WriteShows(BlobWriter& w, BlobStrings& s, const Vec<BlobShow>& shows) {
    int n = shows.len;
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(s.Put(shows[i].title));
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(s.Put(shows[i].kind));
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(shows[i].year);
    }
    w.UInt(n);
    for (int i = 0; i < n; i++) {
        w.UInt(s.Put(shows[i].ref));
    }
}

static void ReadShows(BlobReader& r, const Vec<const char*>& t, Vec<BlobShow>& out) {
    Vec<const char*> titles;
    Vec<const char*> kinds;
    Vec<int> years;
    Vec<const char*> refs;
    ReadStrings(r, t, titles);
    ReadStrings(r, t, kinds);
    ReadUInts(r, years);
    ReadStrings(r, t, refs);
    int n = titles.len;
    if (r.bad || kinds.len != n || years.len != n || refs.len != n) {
        r.bad = true;
        return;
    }
    for (int i = 0; i < n; i++) {
        BlobShow s;
        s.title = titles[i];
        s.kind = kinds[i];
        s.year = years[i];
        s.ref = refs[i];
        out.Append(s);
    }
}

static void WriteStats(BlobWriter& w, const BlobStats& it) {
    w.SInt(it.lastReadAt);
    w.SInt(it.timeSpentMs);
    w.SInt(it.openCount);
    w.SInt(it.pageNo);
    w.SInt(it.scrollX);
    w.SInt(it.scrollY);
    w.SInt(it.percentRead);
    w.SInt(it.unit);
}

static void ReadStats(BlobReader& r, BlobStats& it) {
    it.lastReadAt = r.S();
    it.timeSpentMs = r.S();
    it.openCount = r.S();
    it.pageNo = r.S();
    it.scrollX = r.S();
    it.scrollY = r.S();
    it.percentRead = r.S();
    it.unit = r.S();
}

bool BookBlobPayload(const BookBlobRecord& rec, Vec<u8>& out) {
    BlobStrings strings;
    Vec<int> kinds;
    Vec<BlobWriter*> bodies;

    auto add = [&kinds, &bodies](int kind) {
        kinds.Append(kind);
        BlobWriter* w = new BlobWriter();
        bodies.Append(w);
        return w;
    };

    if (rec.hasIdentity) {
        WriteIdentity(*add(kBlobSectionIdentity), strings, rec.identity);
    }
    if (rec.hasShelf) {
        WriteShelf(*add(kBlobSectionShelf), strings, rec.shelf);
    }
    if (rec.hasCover) {
        WriteCover(*add(kBlobSectionCover), strings, rec.cover);
    }
    if (rec.hasCast) {
        WriteCast(*add(kBlobSectionCharacters), strings, rec.cast);
    }
    if (rec.speakers.len > 0) {
        WriteSpeakers(*add(kBlobSectionSpeakers), rec.speakers);
    }
    if (rec.entities.len > 0) {
        WriteEntities(*add(kBlobSectionEntities), strings, rec.entities);
    }
    if (rec.lore.len > 0) {
        WriteLore(*add(kBlobSectionLore), strings, rec.lore, rec.evidence);
    }
    if (rec.chapters.len > 0) {
        WriteChapters(*add(kBlobSectionChapters), strings, rec.chapters);
    }
    if (rec.adaptations.len > 0) {
        WriteShows(*add(kBlobSectionAdaptations), strings, rec.adaptations);
    }
    if (rec.hasStats) {
        WriteStats(*add(kBlobSectionStats), rec.stats);
    }

    BlobWriter head;
    head.Raw(kBlobMagic, sizeof(kBlobMagic));
    head.UInt(kBookBlobVersion);
    strings.Write(head);
    head.UInt(kinds.len);
    for (int i = 0; i < kinds.len; i++) {
        head.UInt(kinds[i]);
        head.BlobOf(bodies[i]->out.LendData(), bodies[i]->out.len);
    }
    for (int i = 0; i < bodies.len; i++) {
        delete bodies[i];
    }
    out.Append(head.out.LendData(), head.out.len);
    return true;
}

bool BookBlobUnpayload(const u8* data, int size, BookBlobRecord& out) {
    if (!data || size < (int)sizeof(kBlobMagic) || memcmp(data, kBlobMagic, sizeof(kBlobMagic)) != 0) {
        return false;
    }
    BlobReader r(data, size);
    r.at = (int)sizeof(kBlobMagic);
    int version = r.Int();
    if (r.bad || version != kBookBlobVersion) {
        return false;
    }
    int nStrings = r.Count();
    for (int i = 0; i < nStrings && !r.bad; i++) {
        int n = r.Count();
        const u8* d = r.Raw(n);
        if (!d) {
            return false;
        }
        out.strings.Append(Str((const char*)d, n));
    }
    if (r.bad) {
        return false;
    }
    Vec<const char*> table;
    table.Append(nullptr);
    for (int i = 0; i < out.strings.size; i++) {
        table.Append(out.strings.At(i).s);
    }

    int nSections = r.Count();
    for (int i = 0; i < nSections && !r.bad; i++) {
        int kind = r.Int();
        int n = r.Count();
        const u8* d = r.Raw(n);
        if (!d) {
            return false;
        }
        BlobReader body(d, n);
        switch (kind) {
            case kBlobSectionIdentity:
                out.hasIdentity = true;
                ReadIdentity(body, table, out.identity);
                break;
            case kBlobSectionShelf:
                out.hasShelf = true;
                ReadShelf(body, table, out.shelf);
                break;
            case kBlobSectionCover:
                out.hasCover = true;
                ReadCover(body, table, out.cover);
                break;
            case kBlobSectionCharacters:
                out.hasCast = true;
                ReadCast(body, table, out.cast);
                break;
            case kBlobSectionSpeakers:
                ReadSpeakers(body, out.speakers);
                break;
            case kBlobSectionEntities:
                ReadEntities(body, table, out.entities);
                break;
            case kBlobSectionLore:
                ReadLore(body, table, out.lore, out.evidence);
                break;
            case kBlobSectionChapters:
                ReadChapters(body, table, out.chapters);
                break;
            case kBlobSectionAdaptations:
                ReadShows(body, table, out.adaptations);
                break;
            case kBlobSectionStats:
                out.hasStats = true;
                ReadStats(body, out.stats);
                break;
            default:
                break;
        }
        if (body.bad) {
            return false;
        }
    }
    return !r.bad;
}

struct BlobAlloc : ISzAlloc {
    static void* AllocCb(void*, size_t size) { return malloc(size); }
    static void FreeCb(void*, void* address) { free(address); }
    BlobAlloc() {
        this->Alloc = AllocCb;
        this->Free = FreeCb;
    }
};

struct BlobInStream : ISeqInStream {
    const u8* data;
    size_t size;
    size_t at;

    static SRes ReadCb(void* pp, void* buf, size_t* size) {
        BlobInStream* s = (BlobInStream*)pp;
        size_t left = s->size - s->at;
        size_t want = *size;
        if (want > left) {
            want = left;
        }
        if (want > 0) {
            memcpy(buf, s->data + s->at, want);
            s->at += want;
        }
        *size = want;
        return SZ_OK;
    }

    BlobInStream(const u8* d, int n) : data(d), size((size_t)n), at(0) { this->Read = ReadCb; }
};

struct BlobOutStream : ISeqOutStream {
    Vec<u8>* out;

    static size_t WriteCb(void* pp, const void* buf, size_t size) {
        BlobOutStream* s = (BlobOutStream*)pp;
        if (size > 0) {
            s->out->Append((const u8*)buf, (int)size);
        }
        return size;
    }

    explicit BlobOutStream(Vec<u8>* v) : out(v) { this->Write = WriteCb; }
};

bool BookBlobCompress(const u8* data, int size, Vec<u8>& out) {
    BlobAlloc alloc;
    CLzma2EncHandle enc = Lzma2Enc_Create(&alloc, &alloc);
    if (!enc) {
        return false;
    }
    CLzma2EncProps props;
    Lzma2EncProps_Init(&props);
    props.lzmaProps.level = 9;
    props.lzmaProps.dictSize = kBookBlobDictSize;
    props.lzmaProps.numThreads = 1;
    props.numBlockThreads = 1;
    props.numTotalThreads = 1;
    SRes res = Lzma2Enc_SetProps(enc, &props);
    if (res == SZ_OK) {
        BlobInStream in(data, size);
        BlobOutStream sink(&out);
        res = Lzma2Enc_Encode(enc, &sink, &in, nullptr);
    }
    Lzma2Enc_Destroy(enc);
    return res == SZ_OK;
}

static Byte DictSizeProp(u32 dictSize) {
    for (Byte i = 0; i < 40; i++) {
        u32 have = ((u32)2 | (i & 1)) << (i / 2 + 11);
        if (dictSize <= have) {
            return i;
        }
    }
    return 40;
}

bool BookBlobDecompress(const u8* data, int size, int rawSize, Vec<u8>& out) {
    if (!data || size < 0 || rawSize < 0) {
        return false;
    }
    BlobAlloc alloc;
    CLzma2Dec dec;
    Lzma2Dec_Construct(&dec);
    SRes res = Lzma2Dec_Allocate(&dec, DictSizeProp(kBookBlobDictSize), &alloc);
    if (res != SZ_OK) {
        return false;
    }
    Lzma2Dec_Init(&dec);

    u8* dest = out.AppendBlanks(rawSize);
    bool ok = dest != nullptr;
    if (ok) {
        SizeT destLen = (SizeT)rawSize;
        SizeT srcLen = (SizeT)size;
        ELzmaStatus status = LZMA_STATUS_NOT_SPECIFIED;
        res = Lzma2Dec_DecodeToBuf(&dec, dest, &destLen, data, &srcLen, LZMA_FINISH_ANY, &status);
        ok = res == SZ_OK && destLen == (SizeT)rawSize;
    }
    Lzma2Dec_Free(&dec, &alloc);
    return ok;
}

static u32 BlobAdler32(const u8* data, int size) {
    u32 a = 1;
    u32 b = 0;
    int left = size;
    while (left > 0) {
        int chunk = left < 5552 ? left : 5552;
        left -= chunk;
        while (chunk-- > 0) {
            a += *data++;
            b += a;
        }
        a %= 65521;
        b %= 65521;
    }
    return (b << 16) | a;
}

bool BookBlobEncode(const BookBlobRecord& rec, Vec<u8>& out) {
    Vec<u8> raw;
    if (!BookBlobPayload(rec, raw)) {
        return false;
    }
    u32 checksum = BlobAdler32(raw.LendData(), raw.len);
    u8 head[12];
    memcpy(head, kBlobMagic, 4);
    u32 rawLen = (u32)raw.len;
    for (int i = 0; i < 4; i++) {
        head[4 + i] = (u8)((rawLen >> (8 * i)) & 0xff);
        head[8 + i] = (u8)((checksum >> (8 * i)) & 0xff);
    }
    out.Append(head, 12);
    return BookBlobCompress(raw.LendData(), raw.len, out);
}

bool BookBlobDecode(const u8* data, int size, BookBlobRecord& out) {
    if (!data || size < 12 || memcmp(data, kBlobMagic, 4) != 0) {
        return false;
    }
    u32 rawLen = 0;
    u32 checksum = 0;
    for (int i = 3; i >= 0; i--) {
        rawLen = (rawLen << 8) | data[4 + i];
        checksum = (checksum << 8) | data[8 + i];
    }
    if (rawLen > (u32)INT_MAX) {
        return false;
    }
    Vec<u8> raw;
    if (!BookBlobDecompress(data + 12, size - 12, (int)rawLen, raw)) {
        return false;
    }
    if (BlobAdler32(raw.LendData(), raw.len) != checksum) {
        return false;
    }
    return BookBlobUnpayload(raw.LendData(), raw.len, out);
}

void BookBlobTextGuard(Str text, u8 digest[16]) {
    CalcMD5Digest(text, digest);
}

bool BookBlobGuardMatches(const BookBlobRecord& rec, Str text) {
    if (!rec.hasIdentity || rec.identity.textMd5.len != 16) {
        return false;
    }
    u8 digest[16];
    BookBlobTextGuard(text, digest);
    return memcmp(digest, rec.identity.textMd5.LendData(), 16) == 0;
}
