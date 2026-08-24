# The book record

A book carries its own library entry. Shelf, cover, chapters, characters, lore and
reading stats live in the book file, so they travel with it between machines and
between the Windows and Android builds.

Both builds read and write the same bytes. A record written by one is read by the
other without conversion, and neither build writes anything the other would not.
When the two implementations disagree the file format wins: fix the code, not the
file.

## What a record is

A record is one `BookBlobRecord`, encoded by `BookBlobEncode` into the `SBB1`
container: magic, version, then LZMA2-compressed sections. That byte string is
called the blob. It is the only thing ever stored. There is no second copy of any
field anywhere, in any container.

Sections, in the order they are written:

| id | section     | holds                                                      |
|----|-------------|------------------------------------------------------------|
| 1  | identity    | fingerprint, text md5, text length, title, author, source, pages |
| 2  | shelf       | genre, subgenre, series, series parent, series index, collection, partitions, tags |
| 3  | cover       | see *Covers* below                                          |
| 4  | characters  | narrator voice, cast, per-person line counts and aliases     |
| 5  | speakers    | quote spans attributed to a character                        |
| 6  | lore        | subject/predicate/object facts with evidence                 |
| 7  | chapters    | title, offset, page, depth                                   |
| 8  | adaptations | films and shows of the book                                  |
| 9  | stats       | last read at, time spent, open count, page, scroll, percent  |
| 10 | entities    | mention spans and coreference                                |

A section is written only when it has content. An absent section means unknown,
not empty: a reader merges what it finds and leaves the rest alone.

## Where the record goes

The record goes inside the book. Which slot depends on what the file is, and the
first slot that works wins.

### PDF

The catalog gets a piece-info entry, saved incrementally so the original bytes are
only appended to and never rewritten:

    /Root /PieceInfo /SumatraPDF <<
        /LastModified (D:20260819013423+10'00')
        /Private <<
            /Version 1
            /Blob <stream>
            /Fingerprint (fp2:0683cd1f...)
        >>
    >>

`/Blob` is the raw blob as an uncompressed stream — the blob carries its own LZMA2
compression, so a second pass through Flate would only make it bigger. `/Fingerprint`
repeats the identity fingerprint in plain text so a reader can match a file to a
record without decompressing.

Title, author and series are additionally mirrored into the PDF `/Info` dictionary
as `Title`, `Author` and `SumatraSeries`, because that is where every other PDF
reader looks. The blob remains authoritative; `/Info` is a courtesy copy and is
rewritten from the blob, never read back into it.

Nothing else is added. In particular there is no `/Cover` stream: a cover is part
of the blob like everything else.

### Zip containers — epub, cbz, fb2z, xps, zip

The record becomes one archive entry, stored without compression:

    META-INF/sumatra.book

A zip cannot be appended to safely, so the archive is rebuilt: every existing entry
is copied in its original order with its original compression method, our entry is
added or replaced, the result is written to a temporary file in the same directory,
read back and checked, and only then renamed over the original. If any step fails
the original is untouched.

For epub the `mimetype` entry stays first and stays stored, as OCF requires.

### Beside the book

If the book cannot take a record — the format has no slot we can write, the file is
read-only, encrypted, or the write failed — the blob is written to

    <book file name>.sumatra

in the same directory, as the raw blob with no wrapper. The reader matches it to the
book by the identity fingerprint and ignores it if they disagree.

When this happens the info tab says so, at the bottom, naming the reason.

### Zipped together

If the directory will not take a sidecar either, the book and its record are zipped
together into `<title>.zip` beside the original and read from there afterwards. This
is the last resort and is never done silently.

## Covers

A cover is either a place in the book or an image, never both.

**A place** is the normal case. When the picked cover is artwork the book already
contains, the record stores where to find it and the art is not copied:

    kind = 2 (page)
    page, x0, y0, x1, y1, rotation

Its text form, used in the sidecar file, the info tab and anywhere a spot is shown
or typed, is

    page,x0:x1,y0:y1,rotation

so page 1, x from 12 to 80, y from 120 to 320, unrotated, is

    1,12:80,120:320,0

Coordinates are in the page's own units at zero rotation, the same space the
renderer uses, so the spot survives a change of screen and of zoom. Rotation is
degrees clockwise about the page's z axis, one of 0, 90, 180, 270.

**An image** is stored only when the book contains no usable art: the cover came
from the web, or the reader chose a file. Then the record holds the bytes:

    kind = 1 (image)
    format, data

The bytes are WebP, quality 90, scaled so the long edge is at most 1200px, and at
most 4 MiB. A cover that fails to decode, is under 1200 bytes, or is not one of
webp, png or jpeg is not written at all.

Storing bytes for art the book already holds is a bug. It grows the file for nothing
and the two copies drift.

## Before anything is written

A record is validated first, on both platforms, with the same checks:

- the identity fingerprint is present and matches the file being written
- every string is valid UTF-8 and within its field's length limit
- a cover of kind page names a page that exists and a rectangle inside its bounds
- a cover of kind image decodes, and its declared format matches its magic bytes
- the encoded blob decodes back to a record equal to the one encoded

The last check is the important one: encode, decode, compare, and only then open the
book for writing. A blob that does not survive its own round trip never reaches a
file.

Writing is atomic from the reader's point of view. A PDF is saved incrementally so a
half-written file still opens at its previous revision; a zip and a sidecar are
written to a temporary file and renamed.
