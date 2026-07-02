package com.jokerhub.orzmc.patterns

import com.jokerhub.orzmc.mca.McaEntry

/**
 * Pattern that keeps chunks at specific global (x, z) coordinates.
 *
 * Used for force-loaded chunks read from `chunks.dat`.
 * Chunks whose global position appears in [coords] are retained.
 */
class ListPattern(
    private val coords: List<Pair<Int, Int>>,
) : ChunkPattern {
    override fun matches(entry: McaEntry): Boolean {
        val gx = entry.globalX()
        val gz = entry.globalZ()
        return coords.any { it.first == gx && it.second == gz }
    }
}
