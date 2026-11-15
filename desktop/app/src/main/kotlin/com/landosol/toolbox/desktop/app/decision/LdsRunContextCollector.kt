package com.landosol.toolbox.desktop.app.decision

import com.landosol.toolbox.account.AccountListItem
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.LabyrinthRouteJson
import com.landosol.toolbox.labyrinth.canonicalLabyrinthRoleId
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 把桌面执行层状态映射为 LDS 运行上下文并定期喂给网关。
 *
 * - 识别会话运行中：uid 取当前选中账号的 ViewerId，enter_id/难度取该账号最新路线文档，
 *   区域取路线进度，已获角色/遗物取会话观测；路线文档按（会话，批次 run）缓存。
 * - 刷开局进行中（识别会话未运行）：enter_id/难度取刷开局检查点（每次尝试都会更新），
 *   供开局筛选（opening）决策使用。
 *
 * 金币与当前格在本执行层没有可靠观测，按协议下限发送 0（事件金币条件因此保守跳过付费
 * 选项）。uid/enter_id/难度任一不可得时清空上下文，全部决策回退内置。
 */
class LdsRunContextCollector(
    private val sessionState: StateFlow<LabyrinthEntryRecognitionSessionState>,
    private val selectedAccount: () -> AccountListItem?,
    private val routeLoader: suspend (accountId: Long) -> LabyrinthRouteJson?,
    private val rerollCheckpointLoader: suspend (accountId: Long) -> RerollCheckpointSnapshot?,
    private val rerollBusy: () -> Boolean,
    private val gateway: () -> LdsDecisionGateway?,
    private val scope: CoroutineScope,
) {
    /** 刷开局检查点里采集器需要的最小字段；由桌面侧从 Room 检查点装配。 */
    data class RerollCheckpointSnapshot(val enterId: Long, val difficulty: Int)

    private var cachedRoute: Pair<Long, LabyrinthRouteJson>? = null
    private var cachedRun: Pair<AutomationSessionId?, String?>? = null

    fun start() {
        scope.launch {
            while (true) {
                runCatching { feed() }
                delay(POLL_MILLIS)
            }
        }
    }

    private suspend fun feed() {
        val gateway = gateway() ?: return
        val state = sessionState.value
        val account = selectedAccount()
        if (!state.running) {
            cachedRun = null
            if (!rerollBusy()) {
                gateway.updateRunContext(null)
                return
            }
            // 刷开局阶段：检查点每次尝试都会写入最新 enterId/难度。
            val checkpoint = account?.id?.let { rerollCheckpointLoader(it) }
            val uid = account?.gameUid?.trim()?.toLongOrNull() ?: 0L
            gateway.updateRunContext(
                if (checkpoint != null && uid > 0 && checkpoint.enterId > 0 && checkpoint.difficulty > 0) {
                    LdsRunContext(
                        uid = uid, enterId = checkpoint.enterId, difficulty = checkpoint.difficulty,
                        region = 0, blockId = 0, gold = 0, ownedUnitIds = emptyList(), ownedRelicIds = emptyList(),
                    )
                } else null,
            )
            return
        }
        val runKey = state.sessionId to state.runId
        if (runKey != cachedRun || cachedRoute?.first != account?.id) {
            cachedRun = runKey
            val accountId = account?.id
            val loaded = accountId?.let { routeLoader(it) }
            cachedRoute = if (accountId != null && loaded != null) accountId to loaded else null
        }
        val route = cachedRoute?.takeIf { it.first == account?.id }?.second
        val uid = account?.gameUid?.trim()?.toLongOrNull() ?: 0L
        gateway.updateRunContext(
            if (route != null && uid > 0 && route.enterId > 0 && route.difficulty > 0) {
                LdsRunContext(
                    uid = uid,
                    enterId = route.enterId,
                    difficulty = route.difficulty,
                    region = state.routeProgress?.currentArea ?: 0,
                    blockId = 0L,
                    gold = 0L,
                    ownedUnitIds = state.joinedCharacters.mapNotNull { joined ->
                        LabyrinthDecisionGateway.gameUnitId(canonicalLabyrinthRoleId(joined.characterId))
                            .takeIf { it > 0 }
                    }.distinct(),
                    ownedRelicIds = state.observedRelics
                        .mapNotNull { it.relicId.trim().toLongOrNull() }
                        .filter { it > 0 }
                        .distinct(),
                )
            } else null,
        )
    }

    private companion object {
        /** 轮询周期；识别会话每帧都会发状态，2 秒足够新，且空载时开销可忽略。 */
        const val POLL_MILLIS = 2_000L
    }
}
