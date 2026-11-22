package com.projectcit.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryClassifierTest {
    private var n = 0
    private fun file(name: String, size: Long = 1024, path: String = "") =
        DocumentEntry("id${n++}", "content://t/$name", name, size, 0L, false, path)

    @Test fun mapsExtensionsToSystemsAndCores() {
        val idx = LibraryClassifier.classify(sequenceOf(
            file("Zelda.GBA"), file("Pokemon Crystal.gbc"), file("Tetris.gb"), file("Mario Kart DS.nds"),
            file("notes.txt"), file("Zelda.sav"),
        ), 0L)
        assertEquals(4, idx.roms.size)
        assertEquals(listOf(ConsoleSystem.GB, ConsoleSystem.GBC, ConsoleSystem.GBA, ConsoleSystem.NDS), idx.roms.map { it.system })
        assertEquals(CoreId.MELONDS_DS, idx.roms.last().system.core)
        assertEquals(CoreId.MGBA, idx.roms.first().system.core)
    }

    @Test fun ignoresHiddenAndEmptyFiles() {
        val idx = LibraryClassifier.classify(sequenceOf(file("._Zelda.gba"), file("Empty.nds", size = 0)), 0L)
        assertTrue(idx.roms.isEmpty())
    }

    @Test fun detectsBiosAndValidatesSize() {
        val idx = LibraryClassifier.classify(sequenceOf(
            file("bios7.bin", 16_384, "system"), file("BIOS9.BIN", 4_096, "system"),
            file("firmware.bin", 1_000, "system"),
        ), 0L)
        assertTrue(idx.bios.getValue(BiosKind.NDS_ARM7).sizeValid)
        assertTrue(idx.bios.getValue(BiosKind.NDS_ARM9).sizeValid)
        assertFalse(idx.bios.getValue(BiosKind.NDS_FIRMWARE).sizeValid)
        assertNull(idx.bios[BiosKind.GBA_BIOS])
    }

    @Test fun prefersValidDumpInSystemDir() {
        val idx = LibraryClassifier.classify(sequenceOf(
            file("bios7.bin", 9_999, "system"),
            file("bios7.bin", 16_384, "random/stuff"),
            file("bios7.bin", 16_384, "roms/system"),
        ), 0L)
        assertEquals("roms/system", idx.bios.getValue(BiosKind.NDS_ARM7).parentPath)
    }
}
