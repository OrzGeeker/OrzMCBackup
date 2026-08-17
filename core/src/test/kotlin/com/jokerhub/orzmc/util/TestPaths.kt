package com.jokerhub.orzmc.util

import java.nio.file.Path
import java.nio.file.Paths

object TestPaths {
    private val fixturesRoot: Path =
        run {
            val url = Thread.currentThread().contextClassLoader.getResource("Fixtures/world")
            if (url != null && url.protocol == "file") {
                Paths.get(url.toURI()).parent // Fixtures/
            } else {
                val cwd = Paths.get(System.getProperty("user.dir"))
                val base =
                    if (cwd.fileName?.toString() == "core") {
                        cwd.resolve("src/test/resources/Fixtures")
                    } else {
                        cwd.resolve("core/src/test/resources/Fixtures")
                    }
                base
            }
        }

    private val worldPath: Path = fixturesRoot.resolve("world")
    private val world26_1Path: Path = fixturesRoot.resolve("world-26-1")

    fun world(): Path = worldPath

    fun worldDataChunks(): Path = world().resolve("data").resolve("chunks.dat")

    fun worldRegion(name: String): Path = world().resolve("region").resolve(name)

    private val mergePath: Path = fixturesRoot.resolve("merge")

    fun mergeBase(): Path = mergePath.resolve("base")

    fun mergePatch(): Path = mergePath.resolve("patch")

    /** Path to the Minecraft 26.1+ format fixture world root. */
    @Suppress("ktlint:standard:function-naming")
    fun world26_1(): Path = world26_1Path

    /**
     * Resolve a dimension-relative path under the 26.1+ fixture.
     * E.g. world26_1Dimension("overworld", "data/minecraft/chunk_tickets.dat")
     */
    @Suppress("ktlint:standard:function-naming")
    fun world26_1Dimension(
        dimension: String,
        subPath: String = "",
    ): Path {
        val base = world26_1Path.resolve("dimensions/minecraft/$dimension")
        return if (subPath.isEmpty()) base else base.resolve(subPath)
    }
}
