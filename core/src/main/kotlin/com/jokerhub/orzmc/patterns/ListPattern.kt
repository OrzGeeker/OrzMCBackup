package com.jokerhub.orzmc.patterns

import com.jokerhub.orzmc.mca.McaEntry

class ListPattern(private val coords: List<Pair<Int, Int>>) : ChunkPattern {
    override fun matches(entry: McaEntry): Boolean {
        val gx = entry.globalX()
        val gz = entry.globalZ()
        return coords.any { it.first == gx && it.second == gz }
    }
}
