package com.jokerhub.orzmc.world

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Abstract filesystem abstraction.
 *
 * Enables transparent usage of the real filesystem (via [RealFileSystem]) or
 * in-memory storage (via [MemoryFS]) for testing without disk I/O.
 */
interface FileSystem {
    /** Returns true if [path] exists and is a directory. */
    fun isDirectory(path: Path): Boolean

    /** Returns true if [path] exists and is a regular file. */
    fun isRegularFile(path: Path): Boolean

    /** Create a temporary directory with the given prefix. */
    fun createTempDirectory(prefix: String): Path

    /** Returns true if [path] exists (file or directory). */
    fun exists(path: Path): Boolean

    /** List direct children of [path]. Order is unspecified. */
    fun list(path: Path): List<Path>

    /** Recursively walk all descendants of [path]. */
    fun walk(path: Path): List<Path>

    /** Create directory and any missing parents. */
    fun createDirectories(path: Path)

    /** Delete [path] if it exists. No-op if absent. */
    fun deleteIfExists(path: Path)

    /** Copy [src] to [dst]. Optionally replace existing. */
    fun copy(
        src: Path,
        dst: Path,
        replaceExisting: Boolean = true,
    )

    /** Write [bytes] to [path], overwriting if exists. */
    fun write(
        path: Path,
        bytes: ByteArray,
    )

    /** Read all bytes from [path], or null if absent/error. */
    fun read(path: Path): ByteArray?

    /** File size in bytes, or 0 if absent. */
    fun size(path: Path): Long

    /** Delete [root] and all its contents, retrying up to [attempts] times. */
    fun deleteTreeWithRetry(
        root: Path,
        attempts: Int,
        sleepMs: Long,
    ): Boolean

    /** Resolve to a real filesystem path (writes MemoryFS contents to disk if needed). */
    fun toRealPath(path: Path): Path
}

/**
 * Real filesystem implementation backed by [java.nio.file.Files].
 * Used in production; all paths are real filesystem paths.
 */
object RealFileSystem : FileSystem {
    override fun isDirectory(path: Path): Boolean = Files.isDirectory(path)

    override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)

    override fun createTempDirectory(prefix: String): Path = Files.createTempDirectory(prefix)

    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun list(path: Path): List<Path> {
        val s = Files.list(path)
        return try {
            s.collect(
                java.util.stream.Collectors
                    .toList(),
            )
        } finally {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun walk(path: Path): List<Path> {
        val s = Files.walk(path)
        return try {
            s.collect(
                java.util.stream.Collectors
                    .toList(),
            )
        } finally {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun createDirectories(path: Path) {
        Files.createDirectories(path)
    }

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }

    override fun copy(
        src: Path,
        dst: Path,
        replaceExisting: Boolean,
    ) {
        val opt = if (replaceExisting) StandardCopyOption.REPLACE_EXISTING else null
        if (opt != null) Files.copy(src, dst, opt) else Files.copy(src, dst)
    }

    override fun write(
        path: Path,
        bytes: ByteArray,
    ) {
        Files.write(path, bytes)
    }

    override fun read(path: Path): ByteArray? =
        try {
            Files.readAllBytes(path)
        } catch (_: Exception) {
            null
        }

    override fun size(path: Path): Long = Files.size(path)

    override fun deleteTreeWithRetry(
        root: Path,
        attempts: Int,
        sleepMs: Long,
    ): Boolean {
        var i = 0
        while (i < attempts) {
            try {
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach { p ->
                    Cleaner.clearDosAttributes(p)
                    Files.deleteIfExists(p)
                }
                return true
            } catch (_: Exception) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: Exception) {
                }
                i++
            }
        }
        return false
    }

    override fun toRealPath(path: Path): Path = path
}

/**
 * In-memory filesystem implementation backed by [ConcurrentHashMap].
 * All operations are thread-safe. Used for testing the optimizer pipeline
 * without actual disk I/O.
 *
 * Note: [toRealPath] materializes data to a real temp directory for
 * interoperability with APIs that require real filesystem paths.
 */
class MemoryFS : FileSystem {
    private enum class NodeType { DIRECTORY, FILE }

    private val nodes = java.util.concurrent.ConcurrentHashMap<Path, NodeType>()
    private val contents = java.util.concurrent.ConcurrentHashMap<Path, ByteArray>()

    @Volatile
    private var stagingRoot: Path? = null

    override fun isDirectory(path: Path): Boolean = nodes[path] == NodeType.DIRECTORY

    override fun isRegularFile(path: Path): Boolean = nodes[path] == NodeType.FILE

    override fun createTempDirectory(prefix: String): Path {
        val p =
            java.nio.file.Paths
                .get("/mem-$prefix-${System.currentTimeMillis()}")
        nodes[p] = NodeType.DIRECTORY
        return p
    }

    override fun exists(path: Path): Boolean = nodes.containsKey(path)

    override fun list(path: Path): List<Path> {
        val base = path.toString().trimEnd('/')
        return nodes.keys.filter { key ->
            val s = key.toString()
            s.startsWith(base) && s != base && !s.removePrefix(base + "/").contains("/")
        }
    }

    override fun walk(path: Path): List<Path> {
        val base = path.toString().trimEnd('/')
        return nodes.keys.filter { it.toString().startsWith(base) }
    }

    override fun createDirectories(path: Path) {
        nodes[path] = NodeType.DIRECTORY
    }

    override fun deleteIfExists(path: Path) {
        nodes.remove(path)
        contents.remove(path)
    }

    override fun copy(
        src: Path,
        dst: Path,
        replaceExisting: Boolean,
    ) {
        val nodeType = nodes[src] ?: throw IOException("source not found: $src")
        if (!replaceExisting && nodes.containsKey(dst)) throw IOException("dest exists: $dst")
        when (nodeType) {
            NodeType.DIRECTORY -> {
                nodes[dst] = NodeType.DIRECTORY
            }
            NodeType.FILE -> {
                val data = contents[src] ?: throw IOException("source data not found: $src")
                nodes[dst] = NodeType.FILE
                contents[dst] = data.copyOf()
            }
        }
    }

    override fun write(
        path: Path,
        bytes: ByteArray,
    ) {
        nodes[path] = NodeType.FILE
        contents[path] = bytes
    }

    override fun read(path: Path): ByteArray? = contents[path]

    override fun size(path: Path): Long {
        val data = contents[path] ?: return 0L
        return data.size.toLong()
    }

    override fun deleteTreeWithRetry(
        root: Path,
        attempts: Int,
        sleepMs: Long,
    ): Boolean {
        val targets = walk(root)
        targets.sortedByDescending { it.toString().length }.forEach {
            deleteIfExists(it)
        }
        stagingRoot?.let {
            synchronized(it) {
                try {
                    RealFileSystem.deleteTreeWithRetry(it, attempts, sleepMs)
                } catch (_: Exception) {
                }
            }
        }
        return true
    }

    override fun toRealPath(path: Path): Path {
        if (stagingRoot == null) {
            synchronized(this) {
                if (stagingRoot == null) stagingRoot = RealFileSystem.createTempDirectory("memfs-")
            }
        }
        val base = stagingRoot!!
        synchronized(base) {
            val real = base.resolve(path.toString().removePrefix("/"))
            val parent = real.parent
            if (parent != null && !RealFileSystem.exists(parent)) RealFileSystem.createDirectories(parent)
            val data = contents[path]
            if (data != null) {
                RealFileSystem.write(real, data)
            } else {
                if (!RealFileSystem.exists(real)) {
                    if (nodes[path] == NodeType.DIRECTORY) {
                        RealFileSystem.createDirectories(real)
                    }
                }
            }
            return real
        }
    }
}
