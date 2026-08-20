package com.jokerhub.orzmc.world

import java.nio.file.Path

/**
 * Guards against aliased input/output directories.
 *
 * Shared by [DefaultOptimizer] and [DefaultMerger]: an output directory that is the
 * same as (or an ancestor/descendant of) a source directory would be wiped before
 * processing under `--force`, irreversibly destroying the source world.
 *
 * On the real filesystem, symlinks and junctions are resolved via [Path.toRealPath]
 * so an output that aliases a source through a link is caught; in-memory filesystems
 * materialize unrelated temp paths on [MemoryFS.toRealPath], so the lexical comparison
 * is kept there.
 */
internal object OverlapGuard {
    fun overlaps(
        fs: FileSystem,
        a: Path,
        b: Path,
    ): Boolean {
        fun resolve(p: Path): Path {
            if (fs is RealFileSystem && fs.exists(p)) {
                try {
                    return p.toRealPath()
                } catch (_: Exception) {
                    // fall through to lexical normalization
                }
            }
            return p.toAbsolutePath().normalize()
        }
        val na = resolve(a)
        val nb = resolve(b)
        return na == nb || na.startsWith(nb) || nb.startsWith(na)
    }
}
