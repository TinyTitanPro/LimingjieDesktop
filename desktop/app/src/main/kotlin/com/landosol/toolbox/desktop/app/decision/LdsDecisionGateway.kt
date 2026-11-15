package com.landosol.toolbox.desktop.app.decision

import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway
import kotlinx.serialization.Serializable
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.EventChoiceRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.NodeRouteOutcome
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.NodeRouteRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.OpeningFilterOutcome
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.OpeningFilterRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.OpeningRosterOutcome
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.OpeningRosterRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.RelicChoiceRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.RoleChoiceRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.ScoreCheckOutcome
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.ScoreCheckRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.SelectOutcome
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.TicketClearOutcome
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.TicketClearRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.TeamPlanOutcome
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.TeamPlanRequest
import java.util.UUID

/**
 * 本局运行上下文：LDS 协议公共字段（uid、enter_id、难度、区域、当前格、金币、已获角色/遗物）的来源。
 * SPI 请求只携带决策局部数据，公共字段由桌面侧按阶段通过 [LdsDecisionGateway.updateRunContext] 喂入。
 */
data class LdsRunContext(
    val uid: Long,
    val enterId: Long,
    val difficulty: Int,
    val region: Int,
    val blockId: Long,
    val gold: Long,
    val ownedUnitIds: List<Long>,
    val ownedRelicIds: List<Long>,
)

/** 各决策类开关；截图上传不受影响。 */
@Serializable
data class LdsDecisionToggles(
    val enabled: Boolean = true,
    val openingFilter: Boolean = true,
    val openingRoster: Boolean = true,
    val roleChoice: Boolean = true,
    val ticketChoice: Boolean = true,
    val relicChoice: Boolean = true,
    val event: Boolean = true,
    val nodeRoute: Boolean = true,
    val scoreCheck: Boolean = true,
    val ticketClear: Boolean = true,
    val teamPlan: Boolean = true,
) {
    /** 按设置页开关键名读取；未知键返回 false。 */
    fun toggleValue(key: String): Boolean = when (key) {
        "openingFilter" -> openingFilter
        "openingRoster" -> openingRoster
        "roleChoice" -> roleChoice
        "ticketChoice" -> ticketChoice
        "relicChoice" -> relicChoice
        "event" -> event
        "nodeRoute" -> nodeRoute
        "scoreCheck" -> scoreCheck
        "ticketClear" -> ticketClear
        "teamPlan" -> teamPlan
        else -> false
    }

    /** 按设置页开关键名翻写单个开关；未知键返回原值。 */
    fun withToggle(key: String, value: Boolean): LdsDecisionToggles = when (key) {
        "openingFilter" -> copy(openingFilter = value)
        "openingRoster" -> copy(openingRoster = value)
        "roleChoice" -> copy(roleChoice = value)
        "ticketChoice" -> copy(ticketChoice = value)
        "relicChoice" -> copy(relicChoice = value)
        "event" -> copy(event = value)
        "nodeRoute" -> copy(nodeRoute = value)
        "scoreCheck" -> copy(scoreCheck = value)
        "ticketClear" -> copy(ticketClear = value)
        "teamPlan" -> copy(teamPlan = value)
        else -> this
    }

    /** 设置页展示用的开关清单（键名 → 中文名）。 */
    val toggleKeys: List<Pair<String, String>> get() = listOf(
        "event" to "事件选择",
        "relicChoice" to "遗物三选一",
        "ticketChoice" to "印记三选一",
        "roleChoice" to "角色三选一",
        "openingRoster" to "开局选人",
        "openingFilter" to "开局筛选",
        "nodeRoute" to "寻路",
        "scoreCheck" to "分数判断",
        "ticketClear" to "清票",
        "teamPlan" to "编队",
    )
}

/** 黎明界决策系统网关：把共享 SPI 决策点映射到本机 LDS HTTP 协议；noop/失败/动作不符一律返回 null 回退内置。 */
class LdsDecisionGateway(
    endpoint: String,
    toggles: LdsDecisionToggles,
    private val client: LdsDecisionClient = LdsDecisionClient(endpoint),
) : LabyrinthDecisionGateway {

    @Volatile var runContext: LdsRunContext? = null
        private set

    @Volatile private var toggles = toggles

    /** 喂入/更新本局上下文；传 null 清空，全部决策回退内置。 */
    fun updateRunContext(context: LdsRunContext?) { runContext = context }

    /** 设置页热更新：端点与各类开关立即生效，不重建连接池。 */
    fun updateConfig(endpoint: String, toggles: LdsDecisionToggles) {
        client.updateEndpoint(endpoint)
        this.toggles = toggles
    }

    override fun eventChoice(request: EventChoiceRequest): SelectOutcome? {
        if (!toggles.enabled || !toggles.event) return null
        val context = ready() ?: return null
        val response = exchange(context.base("event").copy(
            eventId = request.eventId,
            options = request.options.map { LdsEventOption(it.choiceNum, it.choiceId, it.conditionType, it.conditionValue, it.unitId) },
        ), context.uid) ?: return null
        return ldsChoiceOutcome(response)
    }

    override fun relicChoice(request: RelicChoiceRequest): SelectOutcome? {
        if (!toggles.enabled || !toggles.relicChoice) return null
        val context = ready() ?: return null
        val response = exchange(context.base("relic_choice").copy(
            choices = request.choices.map { LdsChoice(choiceNum = it.choiceNum, relicId = it.id, count = it.count) },
        ), context.uid) ?: return null
        return ldsChoiceOutcome(response)
    }

    override fun ticketChoice(request: RelicChoiceRequest): SelectOutcome? {
        if (!toggles.enabled || !toggles.ticketChoice) return null
        val context = ready() ?: return null
        val response = exchange(context.base("ticket_choice").copy(
            choices = request.choices.map { LdsChoice(choiceNum = it.choiceNum, ticketId = it.id, count = it.count) },
        ), context.uid) ?: return null
        return ldsChoiceOutcome(response)
    }

    override fun roleChoice(request: RoleChoiceRequest): SelectOutcome? {
        if (!toggles.enabled || !toggles.roleChoice) return null
        val context = ready() ?: return null
        val response = exchange(context.base("unit_choice").copy(
            choices = request.choices.map { LdsChoice(choiceNum = it.choiceNum, unitId = it.unitId) },
            bossQuestIds = request.bossQuestIds.ifEmpty { null },
            futureExRegions = request.futureExRegions.ifEmpty { null },
        ), context.uid) ?: return null
        return ldsChoiceOutcome(response)
    }

    override fun openingRoster(request: OpeningRosterRequest): OpeningRosterOutcome? {
        if (!toggles.enabled || !toggles.openingRoster) return null
        val context = ready() ?: return null
        val response = exchange(context.base("select_ticket").copy(
            ticketId = request.ticketId, selectCount = request.selectCount, candidateUnitIds = request.candidateUnitIds,
        ), context.uid) ?: return null
        return ldsOpeningRosterOutcome(response)
    }

    override fun openingFilter(request: OpeningFilterRequest): OpeningFilterOutcome? {
        if (!toggles.enabled || !toggles.openingFilter) return null
        val context = ready() ?: return null
        val response = exchange(context.base("opening").copy(
            attempt = request.attempt,
            map = request.blocks.map { it.toLds(cleared = null) },
        ), context.uid) ?: return null
        return ldsOpeningFilterOutcome(response)
    }

    override fun nodeRoute(request: NodeRouteRequest): NodeRouteOutcome? {
        if (!toggles.enabled || !toggles.nodeRoute) return null
        // path 协议要求 region 为 1～5
        val context = ready()?.takeIf { it.region in 1..5 } ?: return null
        val response = exchange(context.base("path").copy(
            blockId = request.currentBlockId,
            map = request.blocks.map { it.toLds(cleared = it.cleared) },
        ), context.uid) ?: return null
        return ldsNodeRouteOutcome(response)
    }

    override fun scoreCheck(request: ScoreCheckRequest): ScoreCheckOutcome? {
        if (!toggles.enabled || !toggles.scoreCheck) return null
        val context = ready() ?: return null
        val response = exchange(context.base("score_check").copy(
            blockId = request.blockId, guildId = request.guildId, questId = request.questId,
            unitCount = request.unitCount, relicCount = request.relicCount,
            normalClearCount = request.normalClearCount, exClearCount = request.exClearCount, bossClearCount = request.bossClearCount,
            areas = request.areas.map { LdsArea(it.region, it.blockCount, it.cleared) },
        ), context.uid) ?: return null
        return ldsScoreCheckOutcome(response)
    }

    override fun ticketClear(request: TicketClearRequest): TicketClearOutcome? {
        if (!toggles.enabled || !toggles.ticketClear) return null
        val context = ready() ?: return null
        val response = exchange(context.base("ticket_clear").copy(
            guildId = request.guildId, ticketCount = request.ticketCount,
        ), context.uid) ?: return null
        return ldsTicketClearOutcome(response)
    }

    override fun teamPlan(request: TeamPlanRequest): TeamPlanOutcome? {
        if (!toggles.enabled || !toggles.teamPlan) return null
        val context = ready() ?: return null
        val response = exchange(context.base("team").copy(
            questId = request.questId, partyCount = request.partyCount,
            pastAttempts = request.pastAttempts.map { LdsPastAttempt(it.parties, it.outcome) },
            unitNpcFlags = request.unitNpcFlags.map { LdsNpcFlag(it.unitId, it.isNpc) },
        ), context.uid) ?: return null
        return ldsTeamPlanOutcome(response)
    }

    override fun uploadResultScreenshot(png: ByteArray, guildId: Long, unitCount: Int, relicCount: Int, score: Int): Boolean {
        val uid = runContext?.uid?.takeIf { it > 0 } ?: return false
        return client.uploadResultScreenshot(png, guildId, unitCount, relicCount, score, uid)
    }

    /** 公共字段不可得（uid、enter_id、difficulty 非正）时回退内置。 */
    private fun ready(): LdsRunContext? = runContext?.takeIf { it.uid > 0 && it.enterId > 0 && it.difficulty > 0 }

    private fun LdsRunContext.base(kind: String) = LdsRequest(
        requestId = UUID.randomUUID().toString(), kind = kind,
        enterId = enterId, region = region, blockId = blockId,
        difficulty = difficulty, gold = gold,
        ownedUnitIds = ownedUnitIds, ownedRelicIds = ownedRelicIds,
    )

    private fun LabyrinthDecisionGateway.MapBlock.toLds(cleared: Boolean?) =
        LdsMapBlock(blockId, region, column, blockType, nextBlockIds, bossUnitIds.ifEmpty { null }, cleared)

    private fun exchange(request: LdsRequest, uid: Long): LdsResponse? {
        val body = client.postDecision(LdsProtocol.encode(request), uid) ?: return null
        val response = LdsProtocol.decode(body) ?: return null
        return response.takeIf { it.error == null && it.action != null }
    }
}

/** 以下为各 kind 响应动作到共享出参的纯映射，供网关与协议测试共用。 */

internal fun ldsChoiceOutcome(response: LdsResponse): SelectOutcome? =
    if (response.action == "select") response.choiceNum?.let(::SelectOutcome) else null

internal fun ldsOpeningRosterOutcome(response: LdsResponse): OpeningRosterOutcome? =
    response.unitIds?.takeIf { response.action == "select" && it.isNotEmpty() }?.let(::OpeningRosterOutcome)

internal fun ldsOpeningFilterOutcome(response: LdsResponse): OpeningFilterOutcome? = when (response.action) {
    "opening_accept" -> OpeningFilterOutcome.Accept(response.maxAttempts)
    "opening_retry" -> OpeningFilterOutcome.Retry(response.rejectedRegion, response.maxAttempts)
    "opening_stop" -> OpeningFilterOutcome.Stop
    else -> null
}

internal fun ldsNodeRouteOutcome(response: LdsResponse): NodeRouteOutcome? =
    response.nextBlockId?.takeIf { response.action == "move" }?.let(::NodeRouteOutcome)

internal fun ldsScoreCheckOutcome(response: LdsResponse): ScoreCheckOutcome? = when (response.action) {
    "fight" -> ScoreCheckOutcome.Fight(response.score, response.minimumScore)
    "retry_run" -> ScoreCheckOutcome.RetryRun(response.score, response.minimumScore)
    else -> null
}

internal fun ldsTicketClearOutcome(response: LdsResponse): TicketClearOutcome? = when (response.action) {
    "continue_run" -> TicketClearOutcome.ContinueRun(response.guildId)
    "noop" -> TicketClearOutcome.Stop
    else -> null
}

internal fun ldsTeamPlanOutcome(response: LdsResponse): TeamPlanOutcome? = when (response.action) {
    "team" -> response.parties?.let(TeamPlanOutcome::Parties)
    "retry_run" -> TeamPlanOutcome.RetryRun
    else -> null
}
