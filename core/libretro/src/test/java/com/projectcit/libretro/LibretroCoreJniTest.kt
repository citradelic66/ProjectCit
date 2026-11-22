package com.projectcit.libretro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises Kotlin -> JNI -> RetroHost -> core on the build machine, using the
 * Linux build of libretrohost.so (task :core:libretro:buildHostNative).
 * Skipped automatically where that build isn't available (macOS/Windows).
 */
class LibretroCoreJniTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
        private val testCore = System.getProperty("retrohost.testcore")?.let(::File)
        private val mgbaCore = System.getProperty("retrohost.mgba")?.takeIf { it.isNotBlank() }?.let(::File)

        @BeforeClass @JvmStatic fun requireHost() {
            // Skip only where Gradle didn't wire a host build (non-Linux). If it did,
            // a missing/unloadable library is a real failure, never a silent skip.
            assumeTrue("no host native build on this OS", testCore != null)
            check(testCore!!.exists()) { "test core missing: $testCore" }
            NativeBridge.loadError?.let { throw AssertionError("libretrohost failed to load", it) }
        }
    }

    /** Fake core reports observations in its first pixels (see testcore.cpp). */
    private fun LibretroCore.px(i: Int): Int = copyFrameArgb()!!.pixels[i] and 0xFFFFFF

    private fun openFake(overrides: Map<String, String> = emptyMap()) =
        LibretroCore.open(testCore!!.path, tmp.newFolder(), tmp.newFolder(), overrides)

    @Test fun hostInfoComesFromNative() {
        assertTrue(NativeBridge.hostInfo().startsWith("retrohost 0.2.0"))
    }

    @Test fun badCorePathThrowsReadableError() {
        val e = assertThrows(LibretroException::class.java) {
            LibretroCore.open("/nope/core.so", tmp.newFolder(), tmp.newFolder())
        }
        assertTrue(e.message!!.contains("dlopen"))
    }

    @Test fun fullSessionThroughJni() {
        val rom = tmp.newFile("Mario.tst").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        openFake(mapOf("test_layout" to "top-bottom")).use { core ->
            assertEquals("TestCore", core.systemInfo.libraryName)
            assertEquals(listOf("tst", "bin"), core.systemInfo.validExtensions)
            assertFalse(core.systemInfo.needFullPath)
            assertThrows(LibretroException::class.java) { openFake() } // single instance

            core.loadGame(rom.path)
            assertEquals("hello from testcore", core.pollMessage())
            val av = core.avInfo
            assertEquals(256, av.baseWidth); assertEquals(384, av.baseHeight)
            assertEquals(59.8261, av.fps, 1e-4)

            core.setPointer(-0x7fff, 0x7fff, true)
            core.setButtons(0, RetroButton.mask(RetroButton.A, RetroButton.START))
            core.runFrames(2)
            assertEquals(2L, core.framesRun)
            assertEquals(0x8001, core.px(1)); assertEquals(0x7fff, core.px(2)); assertEquals(1, core.px(3))
            assertEquals(RetroButton.mask(RetroButton.A, RetroButton.START), core.px(4))
            assertEquals(1, core.px(5))
            val info = core.frameInfo()!!
            assertEquals(FrameFormat.XRGB8888, info.format); assertEquals(256, info.width)

            val opt = core.options().first { it.key == "test_layout" }
            assertEquals("top-bottom", opt.value); assertEquals(listOf("left-right", "top-bottom"), opt.values)
            assertTrue(core.setOption("test_layout", "left-right"))
            assertFalse(core.setOption("test_layout", "nope"))
            core.runFrame()
            assertEquals(0, core.px(5))

            val pcm = ShortArray(4096)
            assertEquals(2048, core.readAudio(pcm))
            assertEquals(2400L - 2048L, core.audioStats().bufferedFrames)

            // battery save + state
            core.setButtons(0, RetroButton.B.mask)
            core.runFrame()
            assertTrue(core.flushSaveRam())
            assertFalse(core.flushSaveRam())
            val state = File(tmp.root, "states/Mario.s1.state")
            core.saveState(state)
            assertTrue(state.length() > 128)
            val frameAtSave = core.px(0)
            core.runFrames(5)
            core.loadState(state)
            core.runFrame()
            assertEquals(frameAtSave + 1, core.px(0))
            state.writeBytes(state.readBytes().copyOf(64))
            val bad = assertThrows(LibretroException::class.java) { core.loadState(state) }
            assertTrue(bad.message!!.contains("ProjectCit save state") || bad.message!!.contains("truncated"))
        }
    }

    @Test fun closedCoreRejectsCalls() {
        val core = openFake()
        core.close()
        core.close() // idempotent
        assertThrows(IllegalStateException::class.java) { core.runFrame() }
    }

    @Test fun realMgbaBootsAndPersistsSram() {
        assumeTrue("mGBA core not provided (-PmgbaCore=...)", mgbaCore?.exists() == true)
        val rom = tmp.newFile("sram.gba").apply { writeBytes(GbaTestRom.build()) }
        val saves = tmp.newFolder()
        LibretroCore.open(mgbaCore!!.path, tmp.newFolder(), saves).use { core ->
            assertEquals("mGBA", core.systemInfo.libraryName)
            core.loadGame(rom.path)
            assertEquals(240, core.avInfo.baseWidth)
            assertEquals(59.7275, core.avInfo.fps, 1e-3)
            core.runFrames(30)
            val frame = core.copyFrameArgb()
            assertNotNull(frame); assertEquals(240 * 160, frame!!.pixels.size)
            val state = File(tmp.root, "mgba.state")
            core.saveState(state)
            core.loadState(state)
        } // close() flushes SRAM
        assertEquals(0x42, File(saves, "sram.srm").readBytes()[0].toInt())
    }
}

/** Same hand-assembled ROM as host_tests.cpp: writes 0x42 to cartridge SRAM. */
object GbaTestRom {
    fun build(): ByteArray {
        val rom = ByteArray(1024)
        fun put32(off: Int, v: Long) { for (i in 0 until 4) rom[off + i] = (v shr (8 * i)).toByte() }
        put32(0x00, 0xEA00002E)
        rom[0xB2] = 0x96.toByte()
        put32(0xC0, 0xE3A0040E); put32(0xC4, 0xE3A01042); put32(0xC8, 0xE5C01000)
        put32(0xCC, 0xE3A02404); put32(0xD0, 0xE3A03080); put32(0xD4, 0xE5C23084); put32(0xD8, 0xEAFFFFFE)
        "SRAM_V113".toByteArray().copyInto(rom, 0x100)
        return rom
    }
}
