package com.landosol.toolbox.desktop.app.probe

import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.desktop.device.ProbePage
import com.landosol.toolbox.desktop.device.ProbePageSource
import com.landosol.toolbox.desktop.device.ProbeStatus
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthFastFrameObservation

/**
 * 内存探针 → 快帧页状态的保守映射。
 *
 * 只映射"当前视图类名即页面全部语义"的视图；弹窗页（编队/商店/事件/结算弹窗等）与
 * 依赖细判定的状态一律返回 null → 交给视觉。未知类名同样返回 null，最坏情况等于
 * 现状（视觉识别）。弹窗在视图之上时探针回 dialogCount>0，也交视觉。
 */
fun labyrinthProbePageState(page: ProbePage): LabyrinthEntryPageState? {
    if (!page.ok) return null
    // 仅当"活动弹窗数被确认是 0"才允许视图短路；未知（缺省/-1）同样保守交视觉，
    // 保证编队/商店/事件等弹窗页永远不会被底层视图误判。
    if (page.dialogCount == null || page.dialogCount != 0) return null
    return when (page.view) {
        "ViewBattle" -> LabyrinthEntryPageState.BATTLE_IN_PROGRESS
        "ViewLabyrinthMap" -> LabyrinthEntryPageState.NODE_MAP_VIEW
        "ViewHome" -> LabyrinthEntryPageState.HOME
        else -> null
    }
}

/**
 * 探针优先的快帧处理器：探针命中 → 直接给出页状态（跳过整帧模板匹配）；
 * 未命中/未就绪/超时 → 原视觉路径。任何探针异常都不会打断识别链。
 *
 * 传输层连续失败进入熔断（30 秒内不再询问探针），期间完全走视觉。
 */
class LabyrinthProbeFastProcessor(
    private val probe: ProbePageSource,
    private val vision: (CapturedFrame) -> LabyrinthFastFrameObservation,
) : (CapturedFrame) -> LabyrinthFastFrameObservation {
    private var consecutiveFailures = 0
    private var disabledUntilMillis = 0L

    override fun invoke(frame: CapturedFrame): LabyrinthFastFrameObservation {
        val now = System.nanoTime() / 1_000_000
        if (now < disabledUntilMillis) return vision(frame)
        val page = try { probe.page() } catch (_: Throwable) { null }
        if (page == null) {
            // 仅传输级失败（未就绪/断线/超时）计入熔断；探针健康的"无映射"不算。
            if (probe.status.value is ProbeStatus.Ready) {
                consecutiveFailures++
                if (consecutiveFailures >= FAILURE_STREAK_LIMIT) {
                    disabledUntilMillis = now + COOLDOWN_MILLIS
                    consecutiveFailures = 0
                }
            }
            return vision(frame)
        }
        consecutiveFailures = 0
        val mapped = labyrinthProbePageState(page) ?: return vision(frame)
        return probeObservation(frame, page, mapped)
    }

    private fun probeObservation(
        frame: CapturedFrame,
        page: ProbePage,
        state: LabyrinthEntryPageState,
    ) = LabyrinthFastFrameObservation(
        observation = LabyrinthEntryPageObservation(
            state = state,
            confidence = 1.0,
            stateScores = emptyMap(),
            anchorScores = LabyrinthAnchorScores(emptyMap()),
            reason = "memory-probe:view=${page.view.orEmpty()}" +
                (page.viewId?.let { ",viewId=$it" } ?: ""),
        ),
        frameSequence = frame.frameSequence,
        timestampMillis = frame.timestampMillis,
        width = frame.bitmap.width,
        height = frame.bitmap.height,
        elapsedMillis = 0L,
    )

    private companion object {
        const val FAILURE_STREAK_LIMIT = 8
        const val COOLDOWN_MILLIS = 30_000L
    }
}
