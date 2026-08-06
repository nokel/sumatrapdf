#### What the library start page is

Upstream's start page is **Frequently Read**: a short list of files you opened
recently, and nothing else. It only knows about books you have already opened in
SumatraPDF.

This branch replaces it with a **library**: the app's own catalogue of every book
on the machine, whether or not it has ever been opened here. The page is a wall
of cover posters with a rail of shelves down the left, and every book has a
detail page behind it — metadata, table of contents, the characters/family/places
found in it, and the films and TV series based on it.

The point is that a big folder of ebooks is unusable as a folder. The library
turns it into something you can look at: covers instead of filenames, series
grouped together instead of scattered across directories, and a way to say "this
shelf belongs with that one" that sticks.

#### How the page is put together

It is **the home page**, not a panel or a window. `src/CanvasAboutUI.cpp` — the
about/home canvas — asks `LibraryHomeEnabled()` first at every entry point and
hands off to this file: `DrawLibraryPage`, `LibraryOnLeftButtonDown`,
`LibraryOnMouseMove`, `LibraryOnLeftButtonUp`, `LibraryOnRightClick`,
`LibraryOnMouseWheel`, `LibraryOnVScroll`, `LibraryOnLinkClicked`.

**The consequence matters when testing**: the page can only be on screen when no
document is open. `CmdToggleLibraryHome` while a document is open flips the
preference and redraws, and nothing visible happens. Launch with no file to see
it.

Everything is drawn with GDI onto the existing canvas — there are no child
windows, so an unused library costs nothing. Every clickable thing is a synthetic
link URL starting with `<Library,` (`kLinkLibraryPrefix`), reusing the canvas's
existing link hit-testing and hand cursor; `LibraryOnLinkClicked` dispatches on
the prefix (`<Library,Series>`, `<Library,Book>`, `<Library,Person>`,
`<Library,Sort>`, `<Library,Page>`, …).

Layout:

- **Left rail** — the shelf tree: partitions, then series/folder shelves, then
  standalones, indented by depth, with a sort row at the top (A-Z, by genre, most
  books, fewest books). Clicking a shelf filters the wall; right-clicking one
  opens the partition menu. The rail scrolls independently of the wall.
- **Main area** — the poster wall: one tile per book, cover image with title and
  a subtitle (author / volume / year), laid out to fit the width.
- **Book detail page** — replaces the wall when a tile is clicked. Tabs are
  `LibTab::Overview / People / Family / Places / Knows / Screen`, plus a chapter
  tree, a description, subjects, "Read" and page links, and a back link.
- **Right-click a tile** — *Open book from last page read* (uses
  `gFileHistory`), *Open book from beginning*, *Play as Audio Book*, then the
  partition commands.

Both the rail and the main area have their own drawn scrollbars (`LibScrollBar`,
`LayoutBar`/`DrawBar`/`PosFromBar`) because neither is a real window with a
native one.

#### The service behind it

The catalogue is not built in-process. A small local HTTP service does the
scanning, metadata, covers and online lookups, and the page is a client:

```
python -m audiobook.library --port 7863 --parent-pid <pid> [--root <dir> ...]
```

`LibraryEnsureService()` (in `src/SumatraPDF.cpp`) does the whole dance: if
something already answers on the port, use it; otherwise resolve the install
folder, launch `pythonw.exe` with `CREATE_NO_WINDOW`, and poll for up to 10 s
(40 × 250 ms) for it to come up. `--parent-pid` makes the service exit when this
app does, so a crash cannot leave an orphan holding the port.

Endpoints the page uses:

| method | path | what it returns |
|---|---|---|
| GET | `/library?limit=&sort=` | the whole catalogue: shelf rows + books |
| GET | `/partitions` | user-made partitions and their nesting |
| GET | `/status` | `scanning`, `scan_done`, `scan_total` (progress) |
| GET | `/book?id=` | one book's detail: description, subjects, people, places, topics |
| GET | `/chapters?id=` | the table of contents as a depth tree |
| GET | `/cover?id=` | JPEG cover bytes |
| GET | `/poster?url=&key=` | JPEG poster bytes for an adaptation |
| GET | `/screen?id=` | film/TV adaptations of this book |
| GET | `/wiki?q=character\|family\|knows&series=&name=\|topic=` | the lore wiki |
| POST | `/refresh` | rescan the disks |
| POST | `/partition/new\|assign\|rename\|delete\|nest` | edit partitions |

Nothing HTTP happens on the UI thread. Every fetch is a `RunAsync` job
(`libModel`, `libRescan`, `libCover`, `libDetail`, …) that parses JSON with
`base/JsonParser.h` into a global model under one critical section
(`EnterLib`/`LeaveLib`), then invalidates the canvas. The model is fixed-size
arrays, not allocations: `kMaxBooks` 4096, `kMaxSeries` 256, `kMaxCovers` 400,
`kMaxChapters` 512, and `kCoverWorkers` 3 threads pulling cover images so the
wall fills in progressively rather than blocking on the first tile.

Stale-response guards are deliberate: a detail/person/chapter thread checks that
`gDetail.id` (or `gDetail.person`) is *still* the thing it was fetching before
storing anything, so clicking through books quickly cannot land an old answer on
a new page.

#### Where the data comes from

**Finding books** (`audiobook/library/shelf.py`): `discover_roots()` prefers
folders already catalogued, then Documents/Downloads/Desktop, then a bounded
walk of **every fixed drive** (`SCAN_DEPTH` 5, `SCAN_BUDGET` 40000 directories),
skipping `windows`, `program files*`, `programdata`, `appdata`, `node_modules`,
`$recycle.bin`, `site-packages`, `venv`, `steamapps` and friends, and favouring
directories named like libraries (`ebooks`, `books`, `calibre library`,
`audiobooks`, `manga`, …). `scan()` then walks those roots for
`.pdf .epub .mobi .azw3 .fb2 .cbz .xps`.

**Naming a book**: embedded document metadata when it is any good
(`_meta_ok`/`_meta_author` reject the junk that PDF producers leave in Title),
otherwise the filename is parsed (`_parse_name`) into author / title / volume,
including roman numerals. Pages are sampled (`PAGE_SAMPLES` 6) to measure how
much of each page is image, which is what separates a comic from a novel.
`content_hash` dedupes the same book found twice in different folders.

The result is written to `library.json` in the cache root, so the next start
paints immediately and only a rescan pays for the walk.

**Grouping into series** happens two ways and they cooperate: the folder tree
(`_folder_shelves` — a folder with at least `MIN_SERIES_GROUP` book-bearing
subfolders becomes a shelf, and folders with generic names like `books`, `misc`,
`unsorted` are skipped as shelf *names*), and filename analysis in `learn.py`
(shared title phrases, then a character-level clustering pass at
`CLUSTER_DISTANCE` 0.62 for the ones phrases miss). An author holding
`SERIES_AUTHOR_SHARE` (0.75) of a shelf becomes the shelf's author. Books that
join nothing become `<loose>` rows.

**Genre** (`genre.py`) votes a shelf and a sub-shelf out of title, subject and
path rules, and detects comics from extension plus measured ink/art share. That
is what the "genre" sort groups by.

**Partitions** are the user's own grouping, on top of all of the above, stored in
`partitions.json`. Right-click a shelf to create one, move a shelf in or out,
rename or delete it. Once at least `PART_MIN_TAUGHT` (2) shelves have been put
into a partition by hand, `learn.route_partitions()` routes the remaining shelves
by the rare words in their names and their measured features (page counts, how
they are shelved), and the page says *why* it guessed — the `guessed` field is
rendered as the terms it matched on. Taught assignments always beat guesses, and
`kept_out` remembers anything you explicitly pulled back out so it is not
re-guessed into the same place.

**Covers** (`covers.py`): render the book's own first page and accept it only if
it looks like cover art rather than a text page (`MIN_IMAGE_SHARE`,
`TEXT_PAGE_WHITE`), else fetch art online. Encoded to JPEG at
`COVER_HEIGHT` 520 and cached under the cache root, so `/cover?id=` is a file
read after the first time.

**Adaptations** (`screen.py`): IMDb's suggestion endpoint plus a Wikidata SPARQL
query on `P144` (*based on*) find films and series made from the book; results
are filtered by title similarity (`MIN_MATCH` 0.6) and video games, episodes and
podcasts are dropped. Each entry carries kind, year, stars, poster and IMDb id —
the Screen tab lists them, and the title links out to IMDb.

**The wiki tabs** (People / Family / Places / Knows) come from the lore
`FactStore` that `audiobook/lore` builds from BookNLP output for a book or a
whole series: `/wiki?q=character` gives traits, speech, voice, kin, places, a
representative quote and its page; `q=family` gives the kin tree; `q=knows`
answers "who knows about X, and from what page". Books with no analysis simply do
not show those tabs — the `booknlp` flag on the row says whether there is
anything to show.

#### Still to do on Windows: three things the Android app has first

These were built for Android and have no Windows equivalent yet. Each note says
what the Android side does and what the Win32 side would have to touch.

**1. Choosing the cover by measurement, and a cover editor.**

`covers.py` picks the biggest embedded image on page one and calls it the cover.
That rule is wrong often enough to see: it picks interior artwork on magazine
PDFs, it picks a stencil mask instead of the picture it masks, and on a scanned
book it picks the whole front-and-back spread, so the tile is half back cover.

Android replaces the rule with `CoverVision.kt`. Every image block on page one
becomes a candidate, plus the whole page, plus the left and right halves of any
candidate wider than `SPREAD_RATIO` (1.15) so a scanned spread can be split.
Stencils are rejected via `Image.getImageMask()`, sources under 120x160 are
dropped, and the survivors are scored by a 13-feature logistic model — page
share, aspect fit against a cover's 0.5-0.85 ratio, how far off centre it sits,
white and dark share, saturation, contrast, edge density, distinct colours,
whether it is an embedded image, and its source resolution. The weights start at
hand-set priors (`COVER_PRIOR_WEIGHTS`) and are trained by batch gradient descent
with an L2 pull back toward those priors, so a handful of corrections moves the
model without wrecking it. Nothing is downloaded and there is no dependency — the
same "write the algorithm" style as `learn.py`.

The corrections come from the cover editor. On the book detail page the cover
itself is the button: tap it and you can pick an image file, or pick a page out
of the book and drag a crop box over it, adjust the box by its corners, and
Cancel / Accept from the bottom of the screen. Accepting writes the cover, marks
that book as chosen by hand so no sweep overwrites it, feeds the crop to the
model as a positive example and every candidate it did not overlap as negatives,
then re-derives every other automatic cover with the new weights. A version stamp
(`COVER_PICKER_VERSION`) next to the cache does the same thing when the picking
rules themselves change: the next sweep rebuilds instead of trusting the file.

For Windows this is a `covers.py` rewrite plus new UI in `LibraryPage.cpp`: the
detail page's cover rect becomes clickable, the crop UI needs mouse capture and a
rubber-band rect over a rendered page (the drawing is already all GDI), and the
service needs endpoints to set a cover from a file or from a page rect and to
re-sweep. The model and its JSON can live beside `partitions.json`.

Note the display bug that came with it: Android was drawing covers with
`ContentScale.Crop` in a fixed tile, which cuts the edges off any cover whose
aspect does not match. `DrawCoverTile` in `LibraryPage.cpp` already letterboxes
(bottom-aligned when the cover is wide, centred when tall) and is the behaviour
to keep.

**2. Back goes back one step, everywhere.**

Android's hardware back key used to close the app from anywhere in the library.
It now walks the UI outwards one level per press: an open submenu closes back to
its parent menu, then the menu closes; in the library, the shelf rail, then the
book detail page, then the search box, then the shelf filter, then the library
itself; in a document, find, selection, context menu, presentation, fullscreen,
the ToC sidebar, then the navigation history, then the tab. Only when there is
nowhere left to go does a press arm exit, and a second press leaves. Holding back
for three seconds buzzes the phone for 500ms and drops straight to the
frequently-read page from wherever you are, and one more press exits.

The Windows equivalent is Escape and the mouse's back button, and the same
outwards-one-level chain applies: today `CmdClose`-ish handling and Escape are
scattered per-feature. Worth one ordered chain in `Canvas.cpp` /
`SumatraPDF.cpp` so that Escape in the library closes the detail page rather than
doing nothing, and so the shelf filter and search box unwind in order. There is
no three-second-hold analogue to build — that exists because a phone has no
window chrome to close.

**3. Pinch to resize the wall, and a title-size setting.**

Two fingers on the poster wall zoom it between one book per row and five, the
same gesture the page view uses, and the title and subtitle scale with the tile
(`sqrt` of the tile width, clamped 0.6-2.5) so the text stays proportionate
rather than jumping in steps. The chosen column count persists. Separately,
Settings has a slider for the relative title size on the library and
frequently-read pages, with a live preview, and that multiplies the scale the
tile size implies.

On Windows the gesture is Ctrl+wheel over the wall, which `LibraryOnMouseWheel`
already receives — it needs to change the tile size instead of scrolling, clamped
to the same one-to-five books per row, with the count stored in the settings
struct next to the other library preferences. The title-size setting is a new
advanced-settings field read by `DrawCoverTile`.
