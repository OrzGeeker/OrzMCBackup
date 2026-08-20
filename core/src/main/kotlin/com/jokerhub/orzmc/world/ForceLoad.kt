package com.jokerhub.orzmc.world

import java.nio.file.Path

/**
 * Parser for Minecraft force-loaded chunk lists.
 *
 * Supports two file locations (checked in order of priority):
 *  1. `data/minecraft/chunk_tickets.dat` — Minecraft 26.1+ world format
 *  2. `data/chunks.dat` — legacy format
 *
 * Within each file, two NBT structures are supported:
 *  - `data.Forced` (LongArray) — legacy packed 64-bit x/z pairs
 *  - `data.tickets[].chunk_pos` (IntArray[2]) — modern format with `minecraft:forced` type
 */
object ForceLoad {
    private val FILE_PATHS =
        listOf(
            "data/minecraft/chunk_tickets.dat",
            "data/chunks.dat",
        )

    /** Parse force-loaded chunk data in [dimension] via [fs], returning global (x, z) pairs. */
    fun parse(
        fs: FileSystem,
        dimension: Path,
        strict: Boolean,
    ): List<Pair<Int, Int>> {
        for (relPath in FILE_PATHS) {
            val p = dimension.resolve(relPath)
            if (fs.isRegularFile(p)) {
                return try {
                    val bytes = fs.read(p) ?: throw IllegalStateException("force-load file unreadable: $p")
                    NbtForceLoader.parse(bytes)
                } catch (e: Exception) {
                    if (strict) {
                        throw ForceLoadedParseException(
                            "Failed to parse force-loaded chunk list: $p",
                            e,
                        )
                    } else {
                        emptyList()
                    }
                }
            }
        }
        return emptyList()
    }

    /** Backward-compatible overload reading from the real filesystem. */
    fun parse(
        dimension: Path,
        strict: Boolean,
    ): List<Pair<Int, Int>> = parse(RealFileSystem, dimension, strict)
}
