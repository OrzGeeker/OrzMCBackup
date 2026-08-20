package com.jokerhub.orzmc

import com.jokerhub.orzmc.world.FileSystem
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A decorator/proxy for [FileSystem] that allows injecting failures into specified operations.
 * Useful for testing error-handling paths (error recording, graceful degradation, etc.).
 *
 * Example usage:
 * ```
 * val failing = FailingFileSystem(MemoryFS(), failOnOps = setOf("copy"))
 * // Any call to failing.copy(...) will throw IOException
 * ```
 */
class FailingFileSystem(
    val delegate: FileSystem,
    private val failOnOps: Set<String> = emptySet(),
    private val failCounter: MutableMap<String, AtomicBoolean>? = null,
) : FileSystem {
    constructor(delegate: FileSystem, vararg failOnOps: String) : this(delegate, failOnOps.toSet())

    private fun maybeFail(op: String) {
        if (op in failOnOps) throw IOException("injected failure for operation: $op")
        failCounter
            ?.get(
                op,
            )?.let { if (it.getAndSet(false)) throw IOException("injected single-shot failure for: $op") }
    }

    override fun isDirectory(path: Path): Boolean = delegate.isDirectory(path)

    override fun isRegularFile(path: Path): Boolean = delegate.isRegularFile(path)

    override fun createTempDirectory(prefix: String): Path {
        maybeFail("createTempDirectory")
        return delegate.createTempDirectory(prefix)
    }

    override fun exists(path: Path): Boolean = delegate.exists(path)

    override fun list(path: Path): List<Path> {
        maybeFail("list")
        return delegate.list(path)
    }

    override fun walk(
        path: Path,
        followLinks: Boolean,
    ): List<Path> {
        maybeFail("walk")
        return delegate.walk(path, followLinks)
    }

    override fun createDirectories(path: Path) {
        maybeFail("createDirectories")
        delegate.createDirectories(path)
    }

    override fun deleteIfExists(path: Path) {
        maybeFail("deleteIfExists")
        delegate.deleteIfExists(path)
    }

    override fun copy(
        src: Path,
        dst: Path,
        replaceExisting: Boolean,
    ) {
        maybeFail("copy")
        delegate.copy(src, dst, replaceExisting)
    }

    override fun write(
        path: Path,
        bytes: ByteArray,
    ) {
        maybeFail("write")
        delegate.write(path, bytes)
    }

    override fun read(path: Path): ByteArray? {
        maybeFail("read")
        return delegate.read(path)
    }

    override fun size(path: Path): Long {
        maybeFail("size")
        return delegate.size(path)
    }

    override fun deleteTreeWithRetry(
        root: Path,
        attempts: Int,
        sleepMs: Long,
    ): Boolean {
        maybeFail("deleteTreeWithRetry")
        return delegate.deleteTreeWithRetry(root, attempts, sleepMs)
    }

    override fun toRealPath(path: Path): Path {
        maybeFail("toRealPath")
        return delegate.toRealPath(path)
    }
}
