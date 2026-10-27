# NovelVision: Architecture

## 1. Read this first (problems in the brief)

- **Requirement 2 has a contradiction.**
  - To pull "environment, characters, mood" out of text, something has to read the text.
  - If a cloud model reads it, you have sent the book text to the cloud.
  - Fix: run the reading step ON THE PHONE. Cloud only receives a short scene description (about 60 tokens), never book text.
- **On-device AI is not available everywhere.**
  - Gemini Nano (AICore / ML Kit GenAI) only runs on some newer phones.
  - You need a fallback: a simple rule-based extractor (name detection, keyword lists for places and moods). Lower quality, but works on every device.
  - Decide early what quality you accept on the fallback. This is a product risk, not a coding detail.
- **Cost control matters more than caching.**
  - Do not make an image per paragraph. Make one per SCENE (a group of paragraphs).
  - Add a per-book cap and a "generate ahead" limit. Without these, a fast scroller burns your budget.
- **Legal risk.** Pictures of characters from copyrighted novels, sold as a product, is a grey area. Get advice before launch.
- **"Only a short description leaves the phone" needs a technical guard, not a promise.**
  - A small on-device model will sometimes copy sentences from the book into its output.
  - Force structured output with hard length caps per field (environment, mood, characters).
  - Before any request: check the prompt for long word sequences (for example 6+ words in a row) that also appear in the source scene. If found, drop that field or fall back to the rule-based result.
  - Log only counts of blocked prompts, never the text.
- **Android Auto Backup can upload the book and the DB to the user's Google Drive.** That breaks "never uploaded". Exclude the DB, book copies and image folders in `backup_rules.xml` / `data_extraction_rules.xml`, or set `allowBackup=false`.
- **Parsers must cap paragraph size.** Many TXT files use single newlines or no blank lines. One giant "paragraph" in one LazyColumn row will stutter. Split on single newlines, then on sentence ends above about 1,500 characters.
- **EPUB limits to plan for:** DRM-encrypted files cannot be parsed (show a clear error), zip-bomb and path-traversal checks on entries, EPUB2 vs EPUB3 navigation, and ignoring embedded images and fixed-layout books in v1.
- **Start with ONE Gradle module.** Use packages to keep the layers apart. Split into modules only when build time hurts. Splitting on day one slows you down for no gain.

## 2. Layers

- **Domain** (pure Kotlin, no Android imports): models, repository interfaces, use cases.
- **Data**: Room, file parsers, AI clients, file storage, repository implementations.
- **Presentation**: Compose screens + ViewModels. Talks only to use cases.
- Dependency direction: Presentation -> Domain <- Data. Domain knows nothing about the others.

## 3. Project tree

```
NovelVision/
├── app/
│   ├── NovelVisionApp.kt              (Application, Hilt entry)
│   ├── MainActivity.kt
│   └── navigation/AppNavHost.kt
│
├── core/
│   ├── common/
│   │   ├── DispatcherProvider.kt
│   │   ├── AppResult.kt
│   │   └── ParagraphHasher.kt         (normalize text, SHA-256, per-book unique anchor keys)
│   └── designsystem/                  (theme, typography, reader fonts)
│
├── domain/
│   ├── model/
│   │   ├── Book.kt
│   │   ├── Paragraph.kt
│   │   ├── SceneDescriptor.kt         (environment, mood, characters, time of day)
│   │   ├── CharacterProfile.kt
│   │   └── IllustrationState.kt       (ReadingCanvas.kt has its own UI-level one: keep one name, map between them)
│   ├── repository/
│   │   ├── BookRepository.kt
│   │   ├── SceneRepository.kt
│   │   ├── IllustrationRepository.kt
│   │   └── CharacterRepository.kt
│   ├── ai/
│   │   ├── SceneExtractor.kt          (interface: text chunk -> SceneDescriptor)
│   │   ├── PromptBuilder.kt           (SceneDescriptor + style -> image prompt)
│   │   └── ImageGenerator.kt          (interface: prompt -> image bytes)
│   └── usecase/
│       ├── ImportBookUseCase.kt
│       ├── ObserveReaderItemsUseCase.kt
│       ├── RequestIllustrationsUseCase.kt   (look-ahead scheduling)
│       └── SaveReadingProgressUseCase.kt
│
├── data/
│   ├── local/
│   │   ├── db/                        (NovelVisionDb.kt: entities, DAOs)
│   │   └── files/ImageFileStore.kt    (writes PNG/WebP to app-private storage, returns URI)
│   ├── parser/
│   │   ├── BookParser.kt              (interface)
│   │   ├── EpubParser.kt              (zip + OPF spine + XHTML -> paragraphs)
│   │   ├── TxtParser.kt               (charset detect + blank-line split)
│   │   └── ChapterCache.kt            (parsed chapters saved as files, so scrolling never re-parses EPUB)
│   ├── ai/
│   │   ├── OnDeviceSceneExtractor.kt  (Gemini Nano / MediaPipe LLM)
│   │   ├── RuleBasedSceneExtractor.kt (fallback)
│   │   ├── RemoteImageGenerator.kt    (Retrofit/Ktor client, gets prompt ONLY)
│   │   └── dto/
│   ├── repository/                    (implementations of domain interfaces)
│   └── di/DataModule.kt
│
├── feature/
│   ├── library/   (LibraryScreen, LibraryViewModel)
│   ├── reader/
│   │   ├── ReaderScreen.kt
│   │   ├── ReaderViewModel.kt
│   │   ├── ReadingCanvasView.kt       (the lazy list)
│   │   ├── IllustrationSlot.kt
│   │   └── ReaderUiState.kt
│   ├── characters/ (avatar gallery)
│   └── settings/   (image style, daily cap, Wi-Fi only)
│
└── work/
    └── IllustrationWorker.kt          (WorkManager: generate, download, save, update DB)
```

## 4. Data flow

1. **Import**
   - User picks an EPUB/TXT (Storage Access Framework).
   - Parser splits into chapters and paragraphs.
   - Each paragraph gets a SHA-256 of its normalized text. Only hashes + position go into Room, not the text itself.
   - Text hashes repeat ("He nodded.", "***"). So a second step builds `anchorKey` = text hash, plus `#1`, `#2`... for repeats (`ParagraphHasher.anchorKeys`, run over the whole book). `anchorKey` is what every cache table and every LazyColumn key uses.
   - Scene boundaries are marked (paragraph flag `isSceneAnchor`) using simple rules: chapter start, "***" breaks, big jumps in place or time words.
2. **Reading**
   - `ReadingCanvasView` shows paragraphs and reserved image slots at each scene anchor.
   - Slot size is fixed from the start. This is what keeps scrolling smooth (see ReadingCanvas.kt).
3. **Look-ahead**
   - When the visible range changes, the ViewModel asks for slots up to N items ahead.
4. **Per scene**
   - Check `cached_images` by (bookId, anchorHash, style). If READY: show it. If not:
   - Run `SceneExtractor` ON DEVICE on the scene text -> `SceneDescriptor`.
   - Build prompt from descriptor. Hash the prompt. If another book/scene already has a READY image with the same prompt hash, reuse it.
   - Otherwise claim the row (atomic insert, so two workers can never pay twice), send ONLY the prompt to the image API, save file, mark READY.
5. **Characters**
   - Names found in descriptors create `character_profiles`.
   - First appearance triggers one avatar generation. Later scenes reuse the avatar seed and appearance text so faces stay consistent.

## 5. Rules that prevent bugs

- **Cache key is (bookId, anchorKey, styleId)**, not paragraph index and not the bare text hash. The index changes if the parser changes. The bare hash collides on repeated paragraphs, which breaks the unique indexes and crashes LazyColumn on duplicate keys. (In the code the column is still named `anchorHash`.)
- **Insert and claim in one step.** A unique index + `INSERT IGNORE` means only one caller wins. The whole find / reuse / claim flow runs in one Room transaction (`CachedImageDao.claimForGeneration`). Finishing a row (`markReady`, `markFailed`) only works on rows in GENERATING, so a late worker cannot overwrite someone else's result.
- **Image files can be shared between books** (prompt-hash reuse). Never delete a file by hand. Use `BookDeleter`: it returns only files no other book uses, and you delete them after the transaction commits. If a file goes missing, reset every row that points at it (`resetRowsForMissingFile`), not just one. Store images in `filesDir`, not `cacheDir`, or the system will delete paid images.
- **Avatars use the same claim pattern** (`claimAvatar`), otherwise two scenes that introduce the same character can pay for two avatars.
- **No destructive migrations.** The DB is the only link between paid images and scenes. Write real migrations and commit the exported schema JSON.
- **Spend caps are checked against the DB** (`observeSpend` per book, `spendSince` per day) before every claim, not after.
- **Do not trigger generation while the user is flinging.** The reader reports the visible range only after scrolling settles (300 ms), then look-ahead applies.
- **Reset stuck rows on app start** (GENERATING -> PENDING). Process death mid-generation is normal.
- **Store the READY file path in Room, the file itself in app-private storage.** If the file is missing at read time, reset every row pointing at it to PENDING and regenerate.
- **Save reading position as paragraph index + pixel offset**, never as list item index. Item indexes shift when slots appear.
- **Never log book text or send it in crash reports.**

## 6. Suggested stack

- Kotlin, Jetpack Compose (Material 3), Coroutines + Flow
- Hilt for injection
- Room (with KSP), WorkManager
- Coil 3 for image display
- Retrofit or Ktor for the image API
- Voice: out of scope here. Plan a `NarrationEngine` interface in domain so you can swap Android TTS for a cloud voice later.
