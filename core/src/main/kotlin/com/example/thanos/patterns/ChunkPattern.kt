package com.example.thanos.patterns

import com.example.thanos.mca.McaEntry

interface ChunkPattern {
    fun matches(entry: McaEntry): Boolean
}
