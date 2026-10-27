# NovelVision review

**Nothing was compiled.** Google Maven (androidx, Room, Compose) is blocked in this sandbox. I only ran the new SQL against real SQLite and the anchor-key logic in Python. Compile it yourself before trusting it. Biggest compile risks: `@Transaction` methods with bodies in Kotlin interface DAOs (Room needs a recent version), `Crossfade(contentKey = ...)` needs Compose animation 1.7+, `data object` needs Kotlin 1.9+.

## Bugs fixed

### Database (NovelVisionDb.kt)
- **Repeated paragraphs break the cache key.** "He nodded." has the same hash every time, so the unique indexes on scenes and cached_images fail, and LazyColumn crashes on duplicate keys.
  - Fix: new `ParagraphEntity.anchorKey` = text hash, plus `#1`, `#2` for repeats (`ParagraphHasher.anchorKeys`). Unique per book. Does not depend on parser indexes.
  - The column is still called `anchorHash` in other tables, to keep the diff small.
- **Shared image files were unsafe.** `findReadyByPromptHash` reuses files across books, but nothing protected them.
  - Fix: `exclusiveImageUris` returns only files no other book uses, and `BookDeleter` collects them before the cascade delete, inside one transaction. `resetRowsForMissingFile` resets every row that points at a lost file.
- **Reuse could overwrite a running job.** Step 2 of the claim flow marked a row READY even if another worker had it in GENERATING, so you pay and then get overwritten.
  - Fix: `markReused` only touches PENDING/FAILED rows. `markReady` and `markFailed` only work on GENERATING rows.
- **Claim flow was not atomic.** It was a top-level extension function.
  - Fix: moved into `CachedImageDao` as one `@Transaction`.
- **`observeSpend` returned a row with null bookId** (aggregate without GROUP BY always returns one row; confirmed in SQLite). That would crash mapping into a non-null Long.
  - Fix: dropped `bookId` from the query and from `BookSpend`; return type is non-null.
- **Avatars had no claim.** The doc promised one avatar per character but nothing stopped double generation.
  - Fix: `claimAvatar`, `resetStuckAvatars`.
- Added `spendSince` (daily cap was in the doc but had no query), `avatarUris`, migration policy comment (no destructive fallback), precompiled regexes, `Locale.ROOT` for lowercase.

### Reader (ReadingCanvas.kt)
- **Scroll restore failed with real data.** `startIndex` was computed once, but a Room-backed list starts empty, so the saved position was lost. Worse, the progress saver could then overwrite the saved position with item 0.
  - Fix: nothing is composed until the list is non-empty.
- **Restore landed on the wrong row.** An illustration shares the paragraph index of the text before it. A not-found index also jumped to the top.
  - Fix: match text rows only; save the next paragraph when an image is the top item; past-the-end goes to the last row.
- **Fling burned budget.** The visible range triggered look-ahead on every frame, so every slot a fast scroller passed became a paid image.
  - Fix: 300 ms debounce on the range.
- Smaller: `MutableStateFlow.update` instead of read-modify-write, `remember` on the Coil request, and the "only one slot redraws" comment was overstated and is now accurate.

## Checked and found fine
- **`Text` name clash:** `ReaderItem.Text` is nested, and the file always uses it qualified, so `Text(...)` still means Material3. Not a bug.
- **Crossfade `contentKey = { it::class }`:** correct for the intent (no re-fade when only the URI changes).
- **Item keys:** stable and index-free. Only unique if anchorHash is unique, which is fixed above.
- **Enums and List<String> converter:** valid for Room/KSP. A list holding one empty string comes back as an empty list, which is harmless here.
- **Room v1 with exportSchema:** fine pre-release. You must set the KSP arg `room.schemaLocation`.

## Design gaps added to ARCHITECTURE.md
- **Requirement 2 needs a verbatim-copy guard.** A small on-device model can copy book sentences into its output. Cap field lengths and block any prompt that shares a long word run with the source.
- **Auto Backup** can upload the DB and books to Google Drive, which breaks "never uploaded".
- **Giant paragraphs** from TXT files with single newlines will stutter in LazyColumn. Split them in the parser.
- EPUB limits (DRM, zip safety) and the duplicate `IllustrationState` name across domain and reader.

## Not fixed (your call)
- Saved scroll offset is in pixels, so it is wrong after a font-size change or rotation. A character offset inside the paragraph would survive both.
- Scene detection rules and the on-device extractor quality are untested product risk, not code risk.
