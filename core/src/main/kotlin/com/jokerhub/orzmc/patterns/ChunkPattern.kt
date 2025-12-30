package com.jokerhub.orzmc.patterns

import com.jokerhub.orzmc.mca.McaEntry

interface ChunkPattern {
    fun matches(entry: McaEntry): Boolean
}
