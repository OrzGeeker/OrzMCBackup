package com.jokerhub.orzmc.world

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

interface FileSystem {
    fun isDirectory(path: Path): Boolean
    fun createTempDirectory(prefix: String): Path
    fun exists(path: Path): Boolean
    fun list(path: Path): List<Path>
    fun walk(path: Path): List<Path>
    fun createDirectories(path: Path)
    fun deleteIfExists(path: Path)
    fun copy(src: Path, dst: Path, replaceExisting: Boolean = true)
    fun write(path: Path, bytes: ByteArray)
    fun read(path: Path): ByteArray?
    fun deleteTreeWithRetry(root: Path, attempts: Int, sleepMs: Long): Boolean
    fun toRealPath(path: Path): Path
}

object RealFileSystem : FileSystem {
    override fun isDirectory(path: Path): Boolean = Files.isDirectory(path)
    override fun createTempDirectory(prefix: String): Path = Files.createTempDirectory(prefix)
    override fun exists(path: Path): Boolean = Files.exists(path)
    override fun list(path: Path): List<Path> {
        val s = Files.list(path)
        return try {
            s.collect(java.util.stream.Collectors.toList())
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
            s.collect(java.util.stream.Collectors.toList())
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

    override fun copy(src: Path, dst: Path, replaceExisting: Boolean) {
        val opt = if (replaceExisting) StandardCopyOption.REPLACE_EXISTING else null
        if (opt != null) Files.copy(src, dst, opt) else Files.copy(src, dst)
    }

    override fun write(path: Path, bytes: ByteArray) {
        Files.write(path, bytes)
    }

    override fun read(path: Path): ByteArray? = try {
        Files.readAllBytes(path)
    } catch (_: Exception) {
        null
    }

    override fun deleteTreeWithRetry(root: Path, attempts: Int, sleepMs: Long): Boolean {
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

class MemoryFS : FileSystem {
    private val dirs: MutableSet<Path> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Path, Boolean>())
    private val files = java.util.concurrent.ConcurrentHashMap<Path, ByteArray>()
    @Volatile
    private var stagingRoot: Path? = null
    override fun isDirectory(path: Path): Boolean = dirs.contains(path)
    override fun createTempDirectory(prefix: String): Path {
        val p = java.nio.file.Paths.get("/mem-${prefix}-${System.currentTimeMillis()}")
        dirs.add(p)
        return p
    }

    override fun exists(path: Path): Boolean = dirs.contains(path) || files.containsKey(path)
    override fun list(path: Path): List<Path> {
        val base = path.toString().trimEnd('/')
        val children = mutableListOf<Path>()
        // snapshot to avoid concurrent modification during iteration
        val dirSnapshot = java.util.ArrayList<Path>(dirs)
        val fileSnapshot = java.util.ArrayList<Path>(files.keys)
        dirSnapshot.forEach {
            val rel = it.toString()
            if (rel.startsWith(base) && rel != base) {
                val child = rel.removePrefix(base + "/")
                if (!child.contains("/")) children.add(it)
            }
        }
        fileSnapshot.forEach {
            val rel = it.toString()
            if (rel.startsWith(base) && rel != base) {
                val child = rel.removePrefix(base + "/")
                if (!child.contains("/")) children.add(it)
            }
        }
        return children
    }

    override fun walk(path: Path): List<Path> {
        val base = path.toString().trimEnd('/')
        val all = mutableListOf<Path>()
        val dirSnapshot = java.util.ArrayList<Path>(dirs)
        val fileSnapshot = java.util.ArrayList<Path>(files.keys)
        dirSnapshot.forEach { if (it.toString().startsWith(base)) all.add(it) }
        fileSnapshot.forEach { if (it.toString().startsWith(base)) all.add(it) }
        return all
    }

    override fun createDirectories(path: Path) {
        dirs.add(path)
    }

    override fun deleteIfExists(path: Path) {
        files.remove(path)
        dirs.remove(path)
    }

    override fun copy(src: Path, dst: Path, replaceExisting: Boolean) {
        val data = files[src] ?: throw IOException("source not found: $src")
        if (!replaceExisting && files.containsKey(dst)) throw IOException("dest exists: $dst")
        files[dst] = data.copyOf()
    }

    override fun write(path: Path, bytes: ByteArray) {
        files[path] = bytes
    }

    override fun read(path: Path): ByteArray? = files[path]
    override fun deleteTreeWithRetry(root: Path, attempts: Int, sleepMs: Long): Boolean {
        root.toString().trimEnd('/')
        val targets = walk(root)
        targets.sortedByDescending { it.toString().length }.forEach {
            deleteIfExists(it)
        }
        stagingRoot?.let {
            try {
                RealFileSystem.deleteTreeWithRetry(it, attempts, sleepMs)
            } catch (_: Exception) {
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
        val real = base.resolve(path.toString().removePrefix("/"))
        val parent = real.parent
        if (parent != null && !RealFileSystem.exists(parent)) RealFileSystem.createDirectories(parent)
        val data = files[path]
        if (data != null) {
            RealFileSystem.write(real, data)
        } else {
            if (!RealFileSystem.exists(real)) {
                if (dirs.contains(path)) {
                    RealFileSystem.createDirectories(real)
                }
            }
        }
        return real
    }
}
