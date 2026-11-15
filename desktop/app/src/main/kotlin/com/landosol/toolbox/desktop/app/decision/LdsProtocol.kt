package com.landosol.toolbox.desktop.app.decision

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** 黎明界决策系统（LDS）HTTP JSON 协议 DTO；属性名经 SnakeCase 命名策略统一映射为 api.md 的 snake_case 字段。 */
@OptIn(ExperimentalSerializationApi::class)
object LdsProtocol {
    val json: Json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(request: LdsRequest): String = json.encodeToString(request)

    /** 解析失败（含非 JSON 错误报文）返回 null，交由上层降级内置。 */
    fun decode(body: String): LdsResponse? = try { json.decodeFromString(body) } catch (ignored: Exception) { null }
}

/** /labyrinth/decision 请求：公共字段 + 各 kind 附加字段平铺在同一对象；null 的附加字段不写入报文。 */
@Serializable
data class LdsRequest(
    val protocolVersion: Int = 1,
    val requestId: String,
    val kind: String,
    val enterId: Long = 0,
    val region: Int = 0,
    val blockId: Long = 0,
    val difficulty: Int = 0,
    val gold: Long = 0,
    val ownedUnitIds: List<Long> = emptyList(),
    val ownedRelicIds: List<Long> = emptyList(),
    // select_ticket
    val ticketId: Long? = null,
    val selectCount: Int? = null,
    val candidateUnitIds: List<Long>? = null,
    // unit_choice / ticket_choice / relic_choice
    val choices: List<LdsChoice>? = null,
    val bossQuestIds: List<Long>? = null,
    val futureExRegions: List<Int>? = null,
    // event
    val eventId: Long? = null,
    val options: List<LdsEventOption>? = null,
    // team
    val questId: Long? = null,
    val partyCount: Int? = null,
    val pastAttempts: List<LdsPastAttempt>? = null,
    val unitNpcFlags: List<LdsNpcFlag>? = null,
    // opening / path
    val attempt: Int? = null,
    val map: List<LdsMapBlock>? = null,
    // score_check / ticket_clear
    val guildId: Long? = null,
    val unitCount: Int? = null,
    val relicCount: Int? = null,
    val normalClearCount: Int? = null,
    val exClearCount: Int? = null,
    val bossClearCount: Int? = null,
    val areas: List<LdsArea>? = null,
    val ticketCount: Int? = null,
)

/** 三选一候选：unit_choice 用 unit_id，ticket_choice 用 ticket_id，relic_choice 用 relic_id。 */
@Serializable
data class LdsChoice(
    val choiceNum: Int,
    val unitId: Long? = null,
    val ticketId: Long? = null,
    val relicId: Long? = null,
    val count: Int? = null,
)

@Serializable
data class LdsEventOption(val choiceNum: Int, val choiceId: Long, val conditionType: Int, val conditionValue: Long, val unitId: Long)

@Serializable
data class LdsPastAttempt(val parties: List<List<Long>>, val outcome: String)

@Serializable
data class LdsNpcFlag(val unitId: Long, val isNpc: Boolean)

/** 地图块；cleared 为 path 必填、opening 不发送，bossUnitIds 仅首领格提供。 */
@Serializable
data class LdsMapBlock(
    val blockId: Long,
    val region: Int,
    val column: Int,
    val blockType: Int,
    val nextBlockIds: List<Long>,
    val bossUnitIds: List<Long>? = null,
    val cleared: Boolean? = null,
)

@Serializable
data class LdsArea(val region: Int, val blockCount: Int, val cleared: Boolean)

/** 决策响应；错误报文只有 error 无 action。 */
@Serializable
data class LdsResponse(
    val requestId: String? = null,
    val action: String? = null,
    val choiceNum: Int? = null,
    val unitIds: List<Long>? = null,
    val maxAttempts: Int? = null,
    val rejectedRegion: Int? = null,
    val nextBlockId: Long? = null,
    val score: Int? = null,
    val minimumScore: Int? = null,
    val guildId: Long? = null,
    val parties: List<List<Long>>? = null,
    val defeatCount: Int? = null,
    val maxRetries: Int? = null,
    val error: String? = null,
)
