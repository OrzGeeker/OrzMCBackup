package com.jokerhub.orzmc.world

enum class ProgressStage {
    Init,
    Discover,
    DimensionStart,
    RegionStart,
    ChunkProgress,
    DimensionEnd,
    Finalize,
    CopyMisc,
    CopyMiscProgress,
    Compress,
    Cleanup,
    Done
}

data class ProgressEvent(
    val stage: ProgressStage,
    val current: Long? = null,
    val total: Long? = null,
    val path: String? = null,
    val message: String? = null
)
