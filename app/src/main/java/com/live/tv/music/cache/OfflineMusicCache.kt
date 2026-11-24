package com.live.tv.music.cache

import com.live.tv.music.catalog.CatalogLyrics
import com.live.tv.music.catalog.CatalogTrack
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CachedMusicTrack(
    val track: CatalogTrack,
    val lyrics: CatalogLyrics,
    val audio: File,
    val artwork: File?,
)

/**
 * Each private directory is one complete song package. The final directory appears only after a
 * same-filesystem atomic rename, so process death cannot publish a half-downloaded song. Unknown,
 * damaged and abandoned directories are preserved; removal only touches a validated owned package.
 */
class OfflineMusicCache(directory: File) {
    private val root = directory.absoluteFile
    private val mutation = mutations.getOrPut(root.path) { Mutex() }

    fun contains(id: String): Boolean = runCatching { find(id) != null }.getOrDefault(false)

    fun tracks(): List<CatalogTrack> {
        if (!root.exists()) return emptyList()
        validateRoot()
        return root.listFiles().orEmpty()
            .filter { ENTRY_NAME.matches(it.name) }
            .mapNotNull { runCatching { readEntry(it).track }.getOrNull() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun find(id: String): CachedMusicTrack? {
        validateId(id)
        if (!root.exists()) return null
        validateRoot()
        val directory = File(root, key(id))
        if (!Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
        val entry = readEntry(directory)
        if (entry.track.id != id) throw IOException("Music cache identity mismatch")
        val upgraded = File(root, key(id) + HD_SUFFIX)
        val artwork = runCatching { validateMedia(upgraded, artwork = true, maximum = ARTWORK_LIMIT); upgraded }.getOrNull()
        return if (artwork != null) entry.copy(artwork = artwork) else entry
    }

    suspend fun save(
        track: CatalogTrack,
        lyrics: CatalogLyrics,
        writeAssets: suspend (audio: File, artwork: File) -> Unit,
    ) = withContext(Dispatchers.IO) {
        mutation.withLock {
            validateTrack(track)
            require(lyrics.original.length <= MAX_LYRIC_CHARS && lyrics.translated.length <= MAX_LYRIC_CHARS)
            if (!root.exists() && !root.mkdirs()) throw IOException("Music cache directory is unavailable")
            validateRoot()
            if (find(track.id) != null) return@withLock
            val entries = tracks()
            if (entries.size >= MAX_TRACKS) throw IOException("Music cache is full")
            val used = root.listFiles().orEmpty().sumOf { if (it.isDirectory) packageSize(it) else it.length() }
            if (used >= TOTAL_LIMIT || root.usableSpace < RESERVE_BYTES) throw IOException("Music cache is full")
            val staging = File(root, "stage-${UUID.randomUUID()}")
            if (!staging.mkdir()) throw IOException("Cannot stage music cache")
            try {
                val audio = File(staging, AUDIO_FILE)
                val artwork = File(staging, ARTWORK_FILE)
                writeAssets(audio, artwork)
                currentCoroutineContext().ensureActive()
                val audioBytes = validateMedia(audio, artwork = false, maximum = TRACK_LIMIT)
                val artworkBytes = if (track.pictureId.isNotBlank()) {
                    validateMedia(artwork, artwork = true, maximum = ARTWORK_LIMIT)
                } else if (artwork.exists()) {
                    validateMedia(artwork, artwork = true, maximum = ARTWORK_LIMIT)
                } else 0L
                val properties = properties(track, lyrics, audioBytes, artworkBytes)
                FileOutputStream(File(staging, MANIFEST_FILE)).use { output ->
                    properties.store(output, "Smartisax Music private cache v1")
                    output.fd.sync()
                }
                val bytes = packageSize(staging)
                if (bytes > TRACK_LIMIT || bytes > TOTAL_LIMIT - used) throw IOException("Song exceeds music cache limit")
                readEntry(staging, validateName = false)
                currentCoroutineContext().ensureActive()
                val destination = File(root, key(track.id))
                if (Files.exists(destination.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Existing music cache is preserved")
                }
                // There is deliberately no copy-and-delete fallback for an unsupported atomic move.
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                if (staging.exists()) deleteOwnedFiles(staging)
            }
        }
    }

    /** Optional single-file overlay: the original complete package is never rewritten. */
    suspend fun upgradeArtwork(id: String, writeArtwork: suspend (File) -> Unit) = withContext(Dispatchers.IO) {
        mutation.withLock {
            if (find(id) == null) return@withLock
            if (root.usableSpace < RESERVE_BYTES) throw IOException("Music cache is full")
            val staging = File(root, "artwork-stage-${UUID.randomUUID()}")
            try {
                writeArtwork(staging)
                validateMedia(staging, artwork = true, maximum = ARTWORK_LIMIT)
                val destination = File(root, key(id) + HD_SUFFIX)
                val used = root.listFiles().orEmpty().sumOf { if (it.isDirectory) packageSize(it) else it.length() }
                if (used - destination.length() > TOTAL_LIMIT) throw IOException("Music cache is full")
                FileOutputStream(staging, true).use { it.fd.sync() }
                currentCoroutineContext().ensureActive()
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(staging.toPath())
            }
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutation.withLock {
            val entry = find(id) ?: return@withLock
            val source = entry.audio.parentFile!!
            val retired = File(root, "removed-${UUID.randomUUID()}")
            Files.move(source.toPath(), retired.toPath(), StandardCopyOption.ATOMIC_MOVE)
            deleteOwnedFiles(retired)
            val upgraded = File(root, key(id) + HD_SUFFIX)
            if (upgraded.isFile && !Files.isSymbolicLink(upgraded.toPath())) Files.delete(upgraded.toPath())
        }
    }

    private fun readEntry(directory: File, validateName: Boolean = true): CachedMusicTrack {
        validateDirectory(directory)
        val manifest = File(directory, MANIFEST_FILE)
        validateFile(manifest, MANIFEST_LIMIT)
        val data = Properties().apply { manifest.inputStream().use(::load) }
        if (data.getProperty("version") != "1" || data.getProperty("source") != "netease") {
            throw IOException("Unsupported music cache schema")
        }
        val artistCount = data.getProperty("artistCount")?.toIntOrNull()
            ?.takeIf { it in 0..8 } ?: throw IOException("Invalid music artists")
        val track = CatalogTrack(
            data.required("id"), data.required("title"),
            (0 until artistCount).map { data.required("artist.$it") },
            data.required("album"), data.required("pictureId"), data.required("lyricId"),
        )
        validateTrack(track)
        if (validateName && directory.name != key(track.id)) throw IOException("Music cache key mismatch")
        val lyrics = CatalogLyrics(data.required("lyrics"), data.required("translation"))
        if (lyrics.original.length > MAX_LYRIC_CHARS || lyrics.translated.length > MAX_LYRIC_CHARS) {
            throw IOException("Invalid cached lyrics")
        }
        val audio = File(directory, AUDIO_FILE)
        val audioBytes = validateMedia(audio, artwork = false, maximum = TRACK_LIMIT)
        if (audioBytes != data.required("audioBytes").toLongOrNull()) throw IOException("Incomplete cached audio")
        val artworkBytes = data.required("artworkBytes").toLongOrNull()
            ?.takeIf { it in 0..ARTWORK_LIMIT } ?: throw IOException("Invalid cached artwork")
        val artwork = if (artworkBytes > 0) File(directory, ARTWORK_FILE).also {
            if (validateMedia(it, artwork = true, maximum = ARTWORK_LIMIT) != artworkBytes) {
                throw IOException("Incomplete cached artwork")
            }
        } else null
        if (track.pictureId.isNotBlank() && artwork == null) throw IOException("Missing cached artwork")
        val expected = setOfNotNull(MANIFEST_FILE, AUDIO_FILE, artwork?.name)
        if (directory.listFiles()?.map { it.name }?.toSet() != expected || packageSize(directory) > TRACK_LIMIT) {
            throw IOException("Unrecognized music cache package")
        }
        return CachedMusicTrack(track, lyrics, audio, artwork)
    }

    private fun properties(track: CatalogTrack, lyrics: CatalogLyrics, audioBytes: Long, artworkBytes: Long) =
        Properties().apply {
            setProperty("version", "1")
            setProperty("source", "netease")
            setProperty("id", track.id)
            setProperty("title", track.name)
            setProperty("album", track.album)
            setProperty("pictureId", track.pictureId)
            setProperty("lyricId", track.lyricId)
            setProperty("artistCount", track.artists.size.toString())
            track.artists.forEachIndexed { index, artist -> setProperty("artist.$index", artist) }
            setProperty("lyrics", lyrics.original)
            setProperty("translation", lyrics.translated)
            setProperty("audioBytes", audioBytes.toString())
            setProperty("artworkBytes", artworkBytes.toString())
        }

    private fun validateRoot() {
        validateDirectory(root)
    }

    private fun validateDirectory(directory: File) {
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) throw IOException("Invalid cache directory")
        if (directory != root && directory.canonicalFile.parentFile != root.canonicalFile) {
            throw IOException("Music cache escaped its private directory")
        }
    }

    private fun validateFile(file: File, maximum: Long): Long {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() !in 1..maximum) {
            throw IOException("Invalid cached media")
        }
        return file.length()
    }

    private fun validateMedia(file: File, artwork: Boolean, maximum: Long): Long {
        val length = validateFile(file, maximum)
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(16)
            buffer.copyOf(input.read(buffer).coerceAtLeast(0))
        }
        if (!hasMediaSignature(head, artwork)) throw IOException("Cached media has an invalid format")
        return length
    }

    private fun packageSize(directory: File): Long = directory.listFiles().orEmpty().sumOf { it.length() }

    private fun deleteOwnedFiles(directory: File) {
        validateDirectory(directory)
        val children = directory.listFiles().orEmpty()
        if (children.any { it.name !in OWNED_FILES || it.isDirectory || Files.isSymbolicLink(it.toPath()) }) {
            throw IOException("Unrecognized cache content is preserved")
        }
        children.forEach { Files.delete(it.toPath()) }
        Files.delete(directory.toPath())
    }

    private fun Properties.required(key: String): String = getProperty(key) ?: throw IOException("Incomplete music manifest")

    companion object {
        const val TRACK_LIMIT = 64L * 1_024 * 1_024
        const val ARTWORK_LIMIT = 8L * 1_024 * 1_024
        const val TOTAL_LIMIT = 512L * 1_024 * 1_024
        private const val RESERVE_BYTES = 8L * 1_024 * 1_024
        private const val MANIFEST_LIMIT = 2L * 1_024 * 1_024
        private const val MAX_LYRIC_CHARS = 100_000
        private const val MAX_TRACKS = 512
        private const val HD_SUFFIX = ".artwork-hd"
        private const val AUDIO_FILE = "audio"
        private const val ARTWORK_FILE = "artwork"
        private const val MANIFEST_FILE = "manifest.properties"
        private val OWNED_FILES = setOf(AUDIO_FILE, ARTWORK_FILE, MANIFEST_FILE)
        private val ENTRY_NAME = Regex("[a-f0-9]{64}")
        private val mutations = ConcurrentHashMap<String, Mutex>()

        private fun validateId(id: String) {
            if (!Regex("[A-Za-z0-9_-]{1,40}").matches(id)) throw IOException("Invalid music identity")
        }

        private fun validateTrack(track: CatalogTrack) {
            validateId(track.id)
            if (track.name.isBlank() || track.name.length > 160 || track.album.length > 160 ||
                track.artists.size > 8 || track.artists.any { it.length > 120 } ||
                track.pictureId.length > 80 || track.lyricId.length > 80
            ) throw IOException("Invalid music metadata")
        }

        private fun key(id: String): String = MessageDigest.getInstance("SHA-256")
            .digest("netease:$id".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        internal fun hasMediaSignature(bytes: ByteArray, artwork: Boolean): Boolean {
            fun starts(vararg prefix: Int) = bytes.size >= prefix.size &&
                prefix.indices.all { bytes[it].toInt() and 0xff == prefix[it] }
            fun text(offset: Int, value: String) = bytes.size >= offset + value.length &&
                bytes.copyOfRange(offset, offset + value.length).toString(Charsets.US_ASCII) == value
            return if (artwork) {
                starts(0xff, 0xd8, 0xff) || starts(137, 80, 78, 71) || text(0, "GIF8") ||
                    (text(0, "RIFF") && text(8, "WEBP"))
            } else {
                text(0, "ID3") || text(0, "fLaC") || text(0, "OggS") || text(4, "ftyp") ||
                    (text(0, "RIFF") && text(8, "WAVE")) ||
                    (bytes.size >= 2 && bytes[0].toInt() and 0xff == 0xff && bytes[1].toInt() and 0xe0 == 0xe0)
            }
        }
    }
}
