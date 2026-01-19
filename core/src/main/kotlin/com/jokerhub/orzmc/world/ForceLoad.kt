package com.jokerhub.orzmc.world

import java.nio.file.Path

object ForceLoad {
    fun parse(dimension: Path, strict: Boolean): List<Pair<Int, Int>> {
        val f = dimension.resolve("data").resolve("chunks.dat").toFile()
        if (!f.isFile) return emptyList()
        return try { NbtForceLoader.parse(f) } catch (e: Exception) {
            if (strict) throw ForceLoadedParseException("解析强制加载列表失败：${f}", e) else emptyList()
        }
    }
}
