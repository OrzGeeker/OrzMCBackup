package com.jokerhub.orzmc.util

import java.nio.file.Files
import java.nio.file.Paths

fun main() {
    val world = TestPaths.world()
    println("TestPaths.world(): $world")
    println("Files.exists(world): ${Files.exists(world)}")
    val cwd = Paths.get(System.getProperty("user.dir"))
    val p1 = cwd.resolve("src/test/resources/Fixtures/world")
    val p2 = cwd.resolve("core/src/test/resources/Fixtures/world")
    println("CWD: $cwd")
    println("Candidate src/test/resources/Fixtures/world exists: ${Files.exists(p1)} -> $p1")
    println("Candidate core/src/test/resources/Fixtures/world exists: ${Files.exists(p2)} -> $p2")
    val region = world.resolve("region")
    println("Region path: $region exists=${Files.exists(region)}")
    if (Files.exists(region)) {
        Files.list(region).use { stream ->
            stream.filter { it.toString().endsWith(".mca") }.forEach { p ->
                val size = try { Files.size(p) } catch (_: Exception) { -1L }
                println("MCA: $p size=$size")
            }
        }
    }
}
