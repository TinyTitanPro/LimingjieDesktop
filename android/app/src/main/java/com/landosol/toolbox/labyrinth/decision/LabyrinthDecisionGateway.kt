package com.landosol.toolbox.labyrinth.decision

/**
 * 外部决策网关 SPI。
 *
 * 共享业务的各决策点在采用内置结论前，先咨询网关；网关返回 null 表示不接管，
 * 调用方回退内置逻辑。默认构造不传网关，Android 与既有行为完全一致；桌面端
 * 可通过该接口接入外部决策服务（如黎明界决策系统的本机 HTTP JSON 协议）。
 *
 * 所有方法为同步调用：执行层帧循环内决策必须即时返回，外部服务应在适配器
 * 内部处理超时并降级为 null，不得阻塞识别线程。
 */
interface LabyrinthDecisionGateway {

    /**
     * 本局运行上下文（uid、enter_id、难度、区域、当前格、金币、已获角色/遗物列表）由
     * 网关实现自行维护：执行层调用方只提供决策局部数据，适配器负责把外部服务所需的
     * 公共字段补齐；上下文不可得时适配器应返回 null 回退内置逻辑。
     */

    /** 事件选项决策：返回选项序号（choice_num）或 null 回退内置。 */
    fun eventChoice(request: EventChoiceRequest): SelectOutcome? = null

    /** 遗物三选一：返回选项序号或 null 回退内置。 */
    fun relicChoice(request: RelicChoiceRequest): SelectOutcome? = null

    /** 印记（属性券）三选一：返回选项序号或 null 回退内置。 */
    fun ticketChoice(request: RelicChoiceRequest): SelectOutcome? = null

    /** 角色三选一：返回选项序号或 null 回退内置。 */
    fun roleChoice(request: RoleChoiceRequest): SelectOutcome? = null

    /** 开局选人：返回应选角色 ID 列表（按选择顺序）或 null 回退内置。 */
    fun openingRoster(request: OpeningRosterRequest): OpeningRosterOutcome? = null

    /** 开局筛选：accept 接受本局，retry 重试（可带首个不合格区域），stop 停止刷开局；null 回退内置。 */
    fun openingFilter(request: OpeningFilterRequest): OpeningFilterOutcome? = null

    /** 寻路：返回下一格 block_id 或 null 回退内置。 */
    fun nodeRoute(request: NodeRouteRequest): NodeRouteOutcome? = null

    /** 最终首领前分数判断：fight 继续打，retryRun 撤退重开；null 回退内置。 */
    fun scoreCheck(request: ScoreCheckRequest): ScoreCheckOutcome? = null

    /** 结算后清票：continueRun 继续下一局（含下一局公会），noop 结束；null 回退内置。 */
    fun ticketClear(request: TicketClearRequest): TicketClearOutcome? = null

    /** 编队：返回一至三队角色 ID 或 null 回退内置；retryRun 表示按配置撤退重开。 */
    fun teamPlan(request: TeamPlanRequest): TeamPlanOutcome? = null

    /** 结算截图上传（可选能力）。返回 false 表示未接入或失败。 */
    fun uploadResultScreenshot(png: ByteArray, guildId: Long, unitCount: Int, relicCount: Int, score: Int): Boolean = false

    /** 选项序号类决策的统一出参。 */
    data class SelectOutcome(val choiceNum: Int)

    data class OpeningRosterOutcome(val unitIds: List<Long>)

    sealed interface OpeningFilterOutcome {
        data class Accept(val maxAttempts: Int?) : OpeningFilterOutcome
        data class Retry(val rejectedRegion: Int?, val maxAttempts: Int?) : OpeningFilterOutcome
        data object Stop : OpeningFilterOutcome
    }

    data class NodeRouteOutcome(val nextBlockId: Long)

    sealed interface ScoreCheckOutcome {
        data class Fight(val predictedScore: Int?, val minimumScore: Int?) : ScoreCheckOutcome
        data class RetryRun(val predictedScore: Int?, val minimumScore: Int?) : ScoreCheckOutcome
    }

    sealed interface TicketClearOutcome {
        data class ContinueRun(val nextGuildId: Long?) : TicketClearOutcome
        data object Stop : TicketClearOutcome
    }

    sealed interface TeamPlanOutcome {
        data class Parties(val parties: List<List<Long>>) : TeamPlanOutcome
        data object RetryRun : TeamPlanOutcome
    }

    /** 事件选项请求；options 为按画面序号排列的候选。 */
    data class EventChoiceRequest(
        val eventId: Long,
        val options: List<EventOption>,
    ) {
        data class EventOption(
            val choiceNum: Int,
            val choiceId: Long,
            val conditionType: Int,
            val conditionValue: Long,
            val unitId: Long,
        )
    }

    /** 遗物/印记三选一请求。 */
    data class RelicChoiceRequest(
        val kind: Kind,
        val choices: List<Candidate>,
    ) {
        enum class Kind { RELIC, TICKET }

        data class Candidate(val choiceNum: Int, val id: Long, val count: Int)
    }

    /** 角色三选一请求。 */
    data class RoleChoiceRequest(
        val choices: List<Candidate>,
        val bossQuestIds: List<Long>,
        val futureExRegions: List<Int>,
    ) {
        data class Candidate(val choiceNum: Int, val unitId: Long)
    }

    /** 开局选人请求；candidates 为当前券允许选择的全部角色。 */
    data class OpeningRosterRequest(
        val selectCount: Int,
        val ticketId: Long,
        val candidateUnitIds: List<Long>,
    )

    /** 开局筛选请求；blocks 覆盖待检查区域。 */
    data class OpeningFilterRequest(
        val attempt: Int,
        val blocks: List<MapBlock>,
    )

    /** 寻路请求；blocks 为当前区域完整地图。 */
    data class NodeRouteRequest(
        val currentBlockId: Long,
        val blocks: List<MapBlock>,
    )

    /** 地图图块；blockType 取协议 -1..8，bossUnitIds 仅首领格提供。 */
    data class MapBlock(
        val blockId: Long,
        val region: Int,
        val column: Int,
        val blockType: Int,
        val nextBlockIds: List<Long>,
        val bossUnitIds: List<Long>,
        val cleared: Boolean,
    )

    /** 分数判断请求。 */
    data class ScoreCheckRequest(
        val blockId: Long,
        val guildId: Long,
        val questId: Long,
        val unitCount: Int,
        val relicCount: Int,
        val normalClearCount: Int,
        val exClearCount: Int,
        val bossClearCount: Int,
        val areas: List<AreaProgress>,
    ) {
        data class AreaProgress(val region: Int, val blockCount: Int, val cleared: Boolean)
    }

    /** 清票请求。 */
    data class TicketClearRequest(
        val guildId: Long,
        val ticketCount: Int,
    )

    /** 编队请求。 */
    data class TeamPlanRequest(
        val questId: Long,
        val partyCount: Int,
        val pastAttempts: List<Attempt>,
        val unitNpcFlags: List<NpcFlag>,
    ) {
        data class Attempt(val parties: List<List<Long>>, val outcome: String)

        data class NpcFlag(val unitId: Long, val isNpc: Boolean)
    }

    companion object {
        /** 空网关：全部回退内置逻辑。 */
        val NONE: LabyrinthDecisionGateway = object : LabyrinthDecisionGateway {}

        /**
         * 游戏完整单位 ID：角色基础 ID×100+1（初始立绘，如 1025→102501）。
         * 外部决策服务按该主键识别角色；无法解析时返回 0，调用方不得发送。
         */
        fun gameUnitId(characterId: String): Long =
            characterId.toLongOrNull()?.takeIf { it in 1..999999 }?.let { it * 100 + 1 } ?: 0L
    }
}
