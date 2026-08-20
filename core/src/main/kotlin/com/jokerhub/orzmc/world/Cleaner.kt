package com.jokerhub.orzmc.world

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView

/** Utilities for cleaning up files and directories, including Windows-specific attributes. */
object Cleaner {
    /** Clear Windows DOS read-only, hidden and system attributes on [p]. No-op on non-Windows. */
    fun clearDosAttributes(p: Path) {
        try {
            val v = Files.getFileAttributeView(p, DosFileAttributeView::class.java)
            if (v != null) {
                try {
                    v.setReadOnly(false)
                } catch (_: Exception) {
                }
                try {
                    v.setHidden(false)
                } catch (_: Exception) {
                }
                try {
                    v.setSystem(false)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    fun deleteTreeWithRetry(
        root: Path,
        attempts: Int,
        sleepMs: Long,
    ): Boolean {
        var i = 0
        while (i < attempts) {
            try {
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach { p ->
                    clearDosAttributes(p)
                    Files.deleteIfExists(p)
                }
                return true
            } catch (_: Exception) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: Exception) {
                }
                i++
            }
        }
        return false
    }
}
