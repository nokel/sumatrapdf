# Library Notes

Read before changing the library subsystem.

## Architecture

Primary native library files:

- `src/LibraryPage.cpp`
- `src/LibraryScan.cpp`
- `src/LibraryStore.cpp`
- `src/LibrarySidecar.cpp`

The Windows filesystem/document scan is intentionally native C++.

Before adding library behaviour, inspect existing C++ code and partial implementations first.

Do not move Windows library behaviour into Python unless it genuinely belongs at the existing service boundary.

## Data flow

C++:

- scans files;
- reads documents through existing engines;
- maintains the Windows library UI/model;
- persists `SumatraLibrary.txt`;
- reads/writes portable metadata.

Python service:

- receives scan/index data;
- supplies catalogue/detail responses;
- performs enrichment/online grouping where already designed to do so.

Do not duplicate the same responsibility in both layers.

## User metadata

Manual user metadata fields:

- Title
- Author
- Series
- Year

User values use source:

user

User values must survive:

- restart;
- rescan;
- clean-machine recovery from portable metadata.

Generated/online metadata must not overwrite `source=user`.

Revert/Clear must remove user authority only for the requested field and preserve unrelated user fields.

## Regression rule

When changing metadata/library code, preserve already-working:

- normal edits;
- restart persistence;
- rescan persistence;
- portable recovery;
- source tracking;
- stale-sidecar protection;
- Revert/Clear behaviour;
- protection from generated/online overwrite.