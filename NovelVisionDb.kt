package com.novelvision.data.local.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

// ---------------------------------------------------------------------------
// Enums (Room stores enums by name)
// ---------------------------------------------------------------------------

enum class BookFormat { EPUB, TXT }

enum class ImageStatus { PENDING, GENERATING, READY, FAILED }

// ---------------------------------------------------------------------------
// Hashing helper. Lives here so the cache key rule is in one place.
// Normalizing first means small edits in whitespace or quote style
// do not create a new hash (and a new image bill).
// ---------------------------------------------------------------------------

object ParagraphHasher {
    private val curlySingle = Regex("[\\u2018\\u2019]")
    private val curlyDouble = Regex("[\\u201C\\u201D]")
    private val spaces = Regex("\\s+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(curlySingle, "'")
            .replace(curlyDouble, "\"")
            .replace(spaces, " ")
            .trim()

    fun hash(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(normalize(text).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Turns the per-paragraph text hashes of ONE book (in reading order) into keys that
     * are unique inside that book. Short paragraphs repeat ("He nodded.", "***"), so a
     * plain text hash is NOT a safe cache key. The first occurrence keeps the plain hash;
     * later copies get "#1", "#2", ... Still independent of the parser's paragraph indexes.
     * Run it once at import over ALL paragraphs, then store the result in ParagraphEntity.anchorKey.
     */
    fun anchorKeys(textHashes: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return textHashes.map { h ->
            val n = seen.merge(h, 1, Int::plus)!! - 1
            if (n == 0) h else "$h#$n"
        }
    }

    /** Hash of the final image prompt. Used to reuse an image across scenes or books. */
    fun promptHash(prompt: String, styleId: String): String = hash("$styleId|$prompt")
}

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(
    tableName = "books",
    indices = [Index(value = ["contentHash"], unique = true)]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val format: BookFormat,
    /** Original file (SAF URI) or a private copy. Never uploaded. */
    val sourceUri: String,
    /** Hash of the whole file. Stops the same book being imported twice. */
    val contentHash: String,
    val coverUri: String?,
    val paragraphCount: Int,
    /** Reading position: paragraph index + pixel offset inside that item. */
    val lastParagraphIndex: Int = 0,
    val lastScrollOffsetPx: Int = 0,
    val addedAt: Long,
    val lastOpenedAt: Long? = null
)

/**
 * One row per paragraph. Stores the HASH and position only, not the text.
 * Text is read back from the parsed chapter files. Keeps the DB small.
 */
@Entity(
    tableName = "paragraphs",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId", "paragraphIndex"], unique = true),
        Index(value = ["bookId", "anchorKey"], unique = true)
    ]
)
data class ParagraphEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    /** Hash of the normalized text. NOT unique: repeated paragraphs share it. */
    val textHash: String,
    /** textHash + occurrence suffix (see ParagraphHasher.anchorKeys). Unique per book. Cache key. */
    val anchorKey: String,
    val charCount: Int,
    /** True when an illustration slot should be reserved right after this paragraph. */
    val isSceneAnchor: Boolean = false
)

/**
 * Output of the on-device scene reading step.
 * This is the ONLY thing derived from the book that may leave the phone
 * (and only after it becomes an image prompt).
 */
@Entity(
    tableName = "scenes",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "anchorHash"], unique = true)]
)
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** Same value as ParagraphEntity.anchorKey of the anchor paragraph (unique per book). */
    val anchorHash: String,
    val startParagraph: Int,
    val endParagraph: Int,
    val environment: String,
    val mood: String,
    val timeOfDay: String?,
    val characterNames: List<String>,
    /** Which extractor produced it: "on_device" or "rule_based". Useful for quality checks. */
    val extractor: String,
    val createdAt: Long
)

/**
 * The cache. One row = one image for one scene in one style.
 * The unique index is what stops double payment.
 */
@Entity(
    tableName = "cached_images",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId", "anchorHash", "styleId"], unique = true),
        Index(value = ["promptHash", "status"]),
        Index(value = ["bookId", "status"])
    ]
)
data class CachedImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val anchorHash: String,
    val styleId: String,
    val promptHash: String?,
    val status: ImageStatus,
    /** file:// URI inside app-private storage. Null until READY. */
    val imageUri: String? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val attempts: Int = 0,
    val errorMessage: String? = null,
    /** Cost in millionths of a dollar. Lets you show and cap spend per book. */
    val costMicros: Long = 0,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "character_profiles",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "normalizedName"], unique = true)]
)
data class CharacterProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val displayName: String,
    /** Lowercase, trimmed. Used for matching names found in scenes. */
    val normalizedName: String,
    val aliases: List<String> = emptyList(),
    /** Short text like "tall, grey eyes, scar on left cheek". Reused in every prompt. */
    val appearance: String? = null,
    /** Same seed + same appearance text = more consistent faces across scenes. */
    val avatarSeed: Long? = null,
    val avatarStatus: ImageStatus = ImageStatus.PENDING,
    val avatarUri: String? = null,
    val styleId: String? = null,
    val firstSeenParagraph: Int? = null,
    val updatedAt: Long
)

// ---------------------------------------------------------------------------
// Type converters (lists are stored joined by an ASCII "unit separator")
// ---------------------------------------------------------------------------

class Converters {
    private val sep = "\u001F"

    @TypeConverter
    fun fromList(value: List<String>): String = value.joinToString(sep)

    @TypeConverter
    fun toList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(sep)
}

// ---------------------------------------------------------------------------
// Query result types
// ---------------------------------------------------------------------------

data class BookSummary(
    val id: Long,
    val title: String,
    val author: String?,
    val coverUri: String?,
    val paragraphCount: Int,
    val lastParagraphIndex: Int,
    val lastOpenedAt: Long?
)

data class BookSpend(
    val imageCount: Int,
    val totalCostMicros: Long
)

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(book: BookEntity): Long

    @Query("SELECT id FROM books WHERE contentHash = :hash LIMIT 1")
    suspend fun findIdByContentHash(hash: String): Long?

    @Query(
        "SELECT id, title, author, coverUri, paragraphCount, lastParagraphIndex, lastOpenedAt " +
            "FROM books ORDER BY COALESCE(lastOpenedAt, addedAt) DESC"
    )
    fun observeLibrary(): Flow<List<BookSummary>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query(
        "UPDATE books SET lastParagraphIndex = :paragraphIndex, " +
            "lastScrollOffsetPx = :offsetPx, lastOpenedAt = :now WHERE id = :id"
    )
    suspend fun updateProgress(id: Long, paragraphIndex: Int, offsetPx: Int, now: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ParagraphDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(paragraphs: List<ParagraphEntity>)

    @Query(
        "SELECT * FROM paragraphs WHERE bookId = :bookId " +
            "AND paragraphIndex BETWEEN :from AND :to ORDER BY paragraphIndex"
    )
    suspend fun getRange(bookId: Long, from: Int, to: Int): List<ParagraphEntity>

    /** Used by the reader to know where the image slots go. */
    @Query(
        "SELECT paragraphIndex, anchorKey FROM paragraphs " +
            "WHERE bookId = :bookId AND isSceneAnchor = 1 ORDER BY paragraphIndex"
    )
    fun observeAnchors(bookId: Long): Flow<List<AnchorRow>>

    @Query("SELECT * FROM paragraphs WHERE bookId = :bookId AND anchorKey = :anchorKey LIMIT 1")
    suspend fun findByAnchorKey(bookId: Long, anchorKey: String): ParagraphEntity?

    @Query("SELECT COUNT(*) FROM paragraphs WHERE bookId = :bookId")
    suspend fun count(bookId: Long): Int
}

data class AnchorRow(val paragraphIndex: Int, val anchorKey: String)

@Dao
interface SceneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(scene: SceneEntity): Long

    @Query("SELECT * FROM scenes WHERE bookId = :bookId AND anchorHash = :anchorHash LIMIT 1")
    suspend fun find(bookId: Long, anchorHash: String): SceneEntity?
}

sealed interface ClaimResult {
    data class AlreadyReady(val row: CachedImageEntity) : ClaimResult
    data class Reused(val row: CachedImageEntity) : ClaimResult
    data class Won(val rowId: Long) : ClaimResult
    data object LostToOtherWorker : ClaimResult
}

/**
 * FILE OWNERSHIP RULE: an image file may be referenced by rows of several books,
 * because findReadyByPromptHash reuses files across books. A file may only be deleted
 * when NO row in any book points at it. Use BookDeleter below, never delete by hand.
 */
@Dao
interface CachedImageDao {

    /** Returns -1 if a row for (book, anchor, style) already exists. Only ONE caller wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: CachedImageEntity): Long

    @Query(
        "SELECT * FROM cached_images WHERE bookId = :bookId " +
            "AND anchorHash = :anchorHash AND styleId = :styleId LIMIT 1"
    )
    suspend fun find(bookId: Long, anchorHash: String, styleId: String): CachedImageEntity?

    /** The reader watches this one stream and maps rows by anchorHash. */
    @Query("SELECT * FROM cached_images WHERE bookId = :bookId AND styleId = :styleId")
    fun observeForBook(bookId: Long, styleId: String): Flow<List<CachedImageEntity>>

    /** Reuse: same prompt already rendered anywhere (any book) means no new bill. */
    @Query(
        "SELECT * FROM cached_images WHERE promptHash = :promptHash " +
            "AND status = 'READY' AND imageUri IS NOT NULL LIMIT 1"
    )
    suspend fun findReadyByPromptHash(promptHash: String): CachedImageEntity?

    /**
     * Take the job. Only moves PENDING or FAILED to GENERATING.
     * Returns 1 if this caller got it, 0 if someone else already has it.
     */
    @Query(
        "UPDATE cached_images SET status = 'GENERATING', attempts = attempts + 1, " +
            "promptHash = :promptHash, updatedAt = :now " +
            "WHERE id = :id AND status IN ('PENDING', 'FAILED')"
    )
    suspend fun claim(id: Long, promptHash: String, now: Long): Int

    /**
     * Fill a row from an existing file. Same guard as claim(): it only touches rows nobody
     * is working on, so it can never overwrite a GENERATING row (which would pay twice).
     * Returns 1 if applied.
     */
    @Query(
        "UPDATE cached_images SET status = 'READY', imageUri = :uri, widthPx = :w, heightPx = :h, " +
            "promptHash = :promptHash, costMicros = 0, errorMessage = NULL, updatedAt = :now " +
            "WHERE id = :id AND status IN ('PENDING', 'FAILED')"
    )
    suspend fun markReused(id: Long, uri: String, w: Int, h: Int, promptHash: String, now: Long): Int

    /** Only the worker that holds the claim (GENERATING) may finish the row. */
    @Query(
        "UPDATE cached_images SET status = 'READY', imageUri = :uri, widthPx = :w, " +
            "heightPx = :h, costMicros = :cost, errorMessage = NULL, updatedAt = :now " +
            "WHERE id = :id AND status = 'GENERATING'"
    )
    suspend fun markReady(id: Long, uri: String, w: Int, h: Int, cost: Long, now: Long): Int

    @Query(
        "UPDATE cached_images SET status = 'FAILED', errorMessage = :error, updatedAt = :now " +
            "WHERE id = :id AND status = 'GENERATING'"
    )
    suspend fun markFailed(id: Long, error: String?, now: Long): Int

    /**
     * The file is gone (system cleanup, user cleared data): every row that points at it
     * goes back to PENDING, in every book. Fixing only one row would leave the others broken.
     */
    @Query(
        "UPDATE cached_images SET status = 'PENDING', imageUri = NULL, updatedAt = :now " +
            "WHERE imageUri = :uri"
    )
    suspend fun resetRowsForMissingFile(uri: String, now: Long)

    /** Call once on app start. A killed process leaves rows stuck in GENERATING. */
    @Query("UPDATE cached_images SET status = 'PENDING' WHERE status = 'GENERATING'")
    suspend fun resetStuckGenerating(): Int

    /**
     * Aggregate without GROUP BY always returns exactly one row (zeros when there are no
     * rows), so the result is never null and no bookId column is selected.
     */
    @Query(
        "SELECT COUNT(*) AS imageCount, COALESCE(SUM(costMicros), 0) AS totalCostMicros " +
            "FROM cached_images WHERE bookId = :bookId AND status = 'READY'"
    )
    fun observeSpend(bookId: Long): Flow<BookSpend>

    /** For the daily cap: money spent across ALL books since `since` (epoch ms). */
    @Query("SELECT COALESCE(SUM(costMicros), 0) FROM cached_images WHERE updatedAt >= :since AND status = 'READY'")
    suspend fun spendSince(since: Long): Long

    /** Files that only this book uses. Files shared with another book are NOT returned. */
    @Query(
        "SELECT DISTINCT imageUri FROM cached_images WHERE bookId = :bookId AND imageUri IS NOT NULL " +
            "AND imageUri NOT IN (SELECT imageUri FROM cached_images " +
            "WHERE bookId != :bookId AND imageUri IS NOT NULL)"
    )
    suspend fun exclusiveImageUris(bookId: Long): List<String>

    /**
     * The whole claim flow in ONE transaction, so the find/reuse/claim steps cannot
     * interleave with another caller. Both the reader and the worker must go through it.
     */
    @Transaction
    suspend fun claimForGeneration(
        bookId: Long,
        anchorHash: String,
        styleId: String,
        promptHash: String,
        now: Long
    ): ClaimResult {
        // 1. Make sure a row exists. Losing this insert is fine.
        insertIfAbsent(
            CachedImageEntity(
                bookId = bookId,
                anchorHash = anchorHash,
                styleId = styleId,
                promptHash = null,
                status = ImageStatus.PENDING,
                createdAt = now,
                updatedAt = now
            )
        )
        val row = requireNotNull(find(bookId, anchorHash, styleId))
        if (row.status == ImageStatus.READY) return ClaimResult.AlreadyReady(row)

        // 2. Same prompt rendered before, anywhere? Reuse the file, pay nothing.
        findReadyByPromptHash(promptHash)?.let { donor ->
            val applied = markReused(
                id = row.id,
                uri = requireNotNull(donor.imageUri),
                w = donor.widthPx ?: 0,
                h = donor.heightPx ?: 0,
                promptHash = promptHash,
                now = now
            )
            if (applied == 1) return ClaimResult.Reused(requireNotNull(find(bookId, anchorHash, styleId)))
        }

        // 3. Try to take the job. Only one caller gets 1 here.
        return if (claim(row.id, promptHash, now) == 1) ClaimResult.Won(row.id)
        else ClaimResult.LostToOtherWorker
    }
}

@Dao
interface CharacterProfileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(profile: CharacterProfileEntity): Long

    @Update
    suspend fun update(profile: CharacterProfileEntity)

    @Query("SELECT * FROM character_profiles WHERE bookId = :bookId ORDER BY firstSeenParagraph")
    fun observeForBook(bookId: Long): Flow<List<CharacterProfileEntity>>

    @Query(
        "SELECT * FROM character_profiles WHERE bookId = :bookId " +
            "AND normalizedName = :normalizedName LIMIT 1"
    )
    suspend fun findByName(bookId: Long, normalizedName: String): CharacterProfileEntity?

    @Query(
        "UPDATE character_profiles SET avatarStatus = 'READY', avatarUri = :uri, " +
            "styleId = :styleId, updatedAt = :now WHERE id = :id"
    )
    suspend fun markAvatarReady(id: Long, uri: String, styleId: String, now: Long)

    @Query("UPDATE character_profiles SET avatarStatus = 'FAILED', updatedAt = :now WHERE id = :id")
    suspend fun markAvatarFailed(id: Long, now: Long)

    /** Same idea as CachedImageDao.claim: one avatar generation per character, never two. 1 = you got it. */
    @Query(
        "UPDATE character_profiles SET avatarStatus = 'GENERATING', updatedAt = :now " +
            "WHERE id = :id AND avatarStatus IN ('PENDING', 'FAILED')"
    )
    suspend fun claimAvatar(id: Long, now: Long): Int

    @Query("UPDATE character_profiles SET avatarStatus = 'PENDING' WHERE avatarStatus = 'GENERATING'")
    suspend fun resetStuckAvatars(): Int

    @Query("SELECT avatarUri FROM character_profiles WHERE bookId = :bookId AND avatarUri IS NOT NULL")
    suspend fun avatarUris(bookId: Long): List<String>

    /** Insert then return the row, so callers always get a valid profile. */
    @Transaction
    suspend fun getOrCreate(
        bookId: Long,
        displayName: String,
        firstSeenParagraph: Int,
        now: Long
    ): CharacterProfileEntity {
        val key = displayName.trim().lowercase()
        findByName(bookId, key)?.let { return it }
        insertIfAbsent(
            CharacterProfileEntity(
                bookId = bookId,
                displayName = displayName.trim(),
                normalizedName = key,
                firstSeenParagraph = firstSeenParagraph,
                updatedAt = now
            )
        )
        return requireNotNull(findByName(bookId, key))
    }
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

/**
 * MIGRATIONS: version 1 is fine until the first release. After that, every schema change
 * needs a bumped version and a real Migration (or an AutoMigration, which needs exportSchema).
 * Do NOT use fallbackToDestructiveMigration: the DB is the only link between paid images
 * and scenes. Wiping it orphans every file on disk and bills the user again.
 * Set the KSP arg `room.schemaLocation` and commit the exported JSON files.
 */
@Database(
    entities = [
        BookEntity::class,
        ParagraphEntity::class,
        SceneEntity::class,
        CachedImageEntity::class,
        CharacterProfileEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class NovelVisionDb : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun paragraphDao(): ParagraphDao
    abstract fun sceneDao(): SceneDao
    abstract fun cachedImageDao(): CachedImageDao
    abstract fun characterProfileDao(): CharacterProfileDao
}

// ---------------------------------------------------------------------------
// Deleting a book. Order matters: collect file lists BEFORE the cascade removes the rows,
// delete the DB rows in one transaction, and delete the files only after it commits.
// ---------------------------------------------------------------------------

class BookDeleter(private val db: NovelVisionDb) {
    /** Returns the files that are now unused. The caller deletes them from disk. */
    suspend fun delete(bookId: Long): List<String> = db.withTransaction {
        val files = db.cachedImageDao().exclusiveImageUris(bookId) +
            db.characterProfileDao().avatarUris(bookId)
        db.bookDao().delete(bookId) // cascades to paragraphs, scenes, cached_images, profiles
        files
    }
}
