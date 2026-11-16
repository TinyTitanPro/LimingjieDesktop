package com.landosol.toolbox.desktop.app.probe

import android.graphics.Bitmap
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.desktop.device.ProbePage
import com.landosol.toolbox.desktop.device.ProbePageSource
import com.landosol.toolbox.desktop.device.ProbeStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthFastFrameObservation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthProbeFastProcessorTest {
    private fun frame(sequence: Long = 1L) = CapturedFrame(
        bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888),
        timestampMillis = 1234L,
        frameSequence = sequence,
    )

    private fun visionObservation(frame: CapturedFrame) = LabyrinthFastFrameObservation(
        observation = com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation(
            state = LabyrinthEntryPageState.UNKNOWN,
            confidence = 0.0,
            stateScores = emptyMap(),
            anchorScores = com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores(emptyMap()),
            reason = "vision",
        ),
        frameSequence = frame.frameSequence,
        timestampMillis = frame.timestampMillis,
        width = frame.bitmap.width,
        height = frame.bitmap.height,
        elapsedMillis = 0L,
    )

    private class FakeProbe : ProbePageSource {
        val statusFlow = MutableStateFlow<ProbeStatus>(ProbeStatus.Idle)
        override val status: MutableStateFlow<ProbeStatus> = statusFlow
        var result: ProbePage? = ProbePage(ok = true, view = "ViewBattle", viewId = 265, dialogCount = 0)
        var pageCalls = 0
        var throws = false
        override fun page(budgetMillis: Long): ProbePage? {
            pageCalls++
            if (throws) throw IllegalStateException("probe blew up")
            return result
        }
        fun ready() { statusFlow.value = ProbeStatus.Ready(123, "mb1", "x86_64") }
    }

    @Test
    fun `mapping covers view classes and defers dialogs and unknowns`() {
        assertEquals(LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
            labyrinthProbePageState(ProbePage(ok = true, view = "ViewBattle", dialogCount = 0)))
        assertEquals(LabyrinthEntryPageState.NODE_MAP_VIEW,
            labyrinthProbePageState(ProbePage(ok = true, view = "ViewLabyrinthMap", dialogCount = 0)))
        assertEquals(LabyrinthEntryPageState.HOME,
            labyrinthProbePageState(ProbePage(ok = true, view = "ViewHome", dialogCount = 0)))
        assertNull(labyrinthProbePageState(ProbePage(ok = true, view = "ViewSomeUnknownPage", dialogCount = 0)))
        assertNull(labyrinthProbePageState(ProbePage(ok = false, view = "ViewBattle", err = "no-instance")))
        // 弹窗在视图之上或弹窗探测未知 → 细判定必须交给视觉，不允许视图短路
        assertNull(labyrinthProbePageState(ProbePage(ok = true, view = "ViewBattle", dialogCount = 1)))
        assertNull(labyrinthProbePageState(ProbePage(ok = true, view = "ViewBattle", dialogCount = -1)))
        assertNull(labyrinthProbePageState(ProbePage(ok = true, view = "ViewBattle")))
    }

    @Test
    fun `probe hit short-circuits vision`() {
        val probe = FakeProbe().apply { ready() }
        var visionCalls = 0
        val processor = LabyrinthProbeFastProcessor(probe) { visionCalls++; visionObservation(it) }
        val frame = frame()
        val result = processor(frame)
        assertEquals(LabyrinthEntryPageState.BATTLE_IN_PROGRESS, result.observation.state)
        assertEquals(1.0, result.observation.confidence, 0.0)
        assertEquals(frame.frameSequence, result.frameSequence)
        assertEquals(frame.bitmap.width, result.width)
        assertTrue(result.observation.reason.orEmpty().startsWith("memory-probe:"))
        assertEquals(0, visionCalls)
    }

    @Test
    fun `probe miss and script errors fall back to vision`() {
        val probe = FakeProbe().apply { ready(); result = ProbePage(ok = true, view = "ViewUnknownThing") }
        var visionCalls = 0
        val processor = LabyrinthProbeFastProcessor(probe) { visionCalls++; visionObservation(it) }
        assertEquals(LabyrinthEntryPageState.UNKNOWN, processor(frame()).observation.state)
        assertEquals(1, visionCalls)
        probe.result = ProbePage(ok = false, err = "no-instance")
        assertEquals(LabyrinthEntryPageState.UNKNOWN, processor(frame()).observation.state)
        assertEquals(2, visionCalls)
        probe.throws = true
        assertEquals(LabyrinthEntryPageState.UNKNOWN, processor(frame()).observation.state)
        assertEquals(3, visionCalls)
    }

    @Test
    fun `transport failures trip the breaker and skip the probe`() {
        val probe = FakeProbe().apply { ready(); result = null }
        var visionCalls = 0
        val processor = LabyrinthProbeFastProcessor(probe) { visionCalls++; visionObservation(it) }
        repeat(8) { assertEquals(LabyrinthEntryPageState.UNKNOWN, processor(frame()).observation.state) }
        assertEquals(8, probe.pageCalls)
        // 熔断窗口内不再询问探针（调用计数不再增长），完全走视觉。
        repeat(3) { assertEquals(LabyrinthEntryPageState.UNKNOWN, processor(frame()).observation.state) }
        assertEquals(8, probe.pageCalls)
        assertEquals(11, visionCalls)
    }

    @Test
    fun `transport failures while not ready do not trip the breaker`() {
        val probe = FakeProbe().apply { result = null } // status 仍为 Idle：探针未就绪属常态
        val processor = LabyrinthProbeFastProcessor(probe) { visionObservation(it) }
        repeat(20) { processor(frame()) }
        assertEquals(20, probe.pageCalls)
    }
}
