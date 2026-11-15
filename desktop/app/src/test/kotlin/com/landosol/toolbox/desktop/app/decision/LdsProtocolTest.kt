package com.landosol.toolbox.desktop.app.decision

import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.EventChoiceRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.NodeRouteRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.OpeningRosterRequest
import com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.TeamPlanRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LDS 协议编解码与响应映射的纯函数测试，不联网。 */
class LdsProtocolTest {
    private fun encode(request: LdsRequest): JsonObject = Json.parseToJsonElement(LdsProtocol.encode(request)).jsonObject
    private fun decode(body: String) = LdsProtocol.decode(body)
    private fun context(enterId: Long = 9, difficulty: Int = 5) = LdsRunContext(
        uid = 123456789L, enterId = enterId, difficulty = difficulty, region = 3, blockId = 30101,
        gold = 4200, ownedUnitIds = listOf(102501L), ownedRelicIds = listOf(40001L))

    private fun assertEnvelope(document: JsonObject, requestId: String, kind: String) {
        assertEquals(1, document.getValue("protocol_version").jsonPrimitive.int)
        assertEquals(requestId, document.getValue("request_id").jsonPrimitive.content)
        assertEquals(kind, document.getValue("kind").jsonPrimitive.content)
    }

    @Test fun selectTicketRequestEncodesCommonAndTicketFields() {
        val document = encode(LdsRequest(requestId = "req-1", kind = "select_ticket", enterId = 7, region = 1,
            blockId = 101, difficulty = 2, gold = 500, ownedUnitIds = listOf(100101), ownedRelicIds = listOf(40001),
            ticketId = 31100001, selectCount = 2, candidateUnitIds = listOf(102501, 130801)))
        assertEnvelope(document, "req-1", "select_ticket")
        assertEquals(7L, document.getValue("enter_id").jsonPrimitive.long)
        assertEquals(1, document.getValue("region").jsonPrimitive.int)
        assertEquals(101L, document.getValue("block_id").jsonPrimitive.long)
        assertEquals(2, document.getValue("difficulty").jsonPrimitive.int)
        assertEquals(500L, document.getValue("gold").jsonPrimitive.long)
        assertEquals(listOf(100101L), document.getValue("owned_unit_ids").jsonArray.map { it.jsonPrimitive.long })
        assertEquals(listOf(40001L), document.getValue("owned_relic_ids").jsonArray.map { it.jsonPrimitive.long })
        assertEquals(31100001L, document.getValue("ticket_id").jsonPrimitive.long)
        assertEquals(2, document.getValue("select_count").jsonPrimitive.int)
        assertEquals(listOf(102501L, 130801L), document.getValue("candidate_unit_ids").jsonArray.map { it.jsonPrimitive.long })
        assertFalse(document.containsKey("choices"))
        assertFalse(document.containsKey("map"))
        assertFalse(document.containsKey("event_id"))
    }

    @Test fun commonFieldsStayPresentAndKindFieldsStayOmitted() {
        val document = encode(LdsRequest(requestId = "req-2", kind = "ticket_clear"))
        assertEnvelope(document, "req-2", "ticket_clear")
        assertEquals(0L, document.getValue("enter_id").jsonPrimitive.long)
        assertEquals(0, document.getValue("region").jsonPrimitive.int)
        assertEquals(0L, document.getValue("block_id").jsonPrimitive.long)
        assertEquals(0, document.getValue("difficulty").jsonPrimitive.int)
        assertEquals(0L, document.getValue("gold").jsonPrimitive.long)
        assertTrue(document.getValue("owned_unit_ids").jsonArray.isEmpty())
        assertTrue(document.getValue("owned_relic_ids").jsonArray.isEmpty())
        assertFalse(document.containsKey("ticket_id"))
        assertFalse(document.containsKey("select_count"))
        assertFalse(document.containsKey("quest_id"))
        assertFalse(document.containsKey("guild_id"))
    }

    @Test fun unitChoiceRequestEncodesCandidatesAndHints() {
        val document = encode(LdsRequest(requestId = "req-3", kind = "unit_choice",
            choices = listOf(LdsChoice(choiceNum = 1, unitId = 102501), LdsChoice(choiceNum = 2, unitId = 130801)),
            bossQuestIds = listOf(770331601), futureExRegions = listOf(3, 4, 5)))
        assertEnvelope(document, "req-3", "unit_choice")
        val first = document.getValue("choices").jsonArray[0].jsonObject
        assertEquals(1, first.getValue("choice_num").jsonPrimitive.int)
        assertEquals(102501L, first.getValue("unit_id").jsonPrimitive.long)
        assertFalse(first.containsKey("count"))
        assertFalse(first.containsKey("ticket_id"))
        assertEquals(listOf(770331601L), document.getValue("boss_quest_ids").jsonArray.map { it.jsonPrimitive.long })
        assertEquals(listOf(3, 4, 5), document.getValue("future_ex_regions").jsonArray.map { it.jsonPrimitive.int })
    }

    @Test fun ticketAndRelicChoiceRequestsUseTheirOwnIdFields() {
        val ticket = encode(LdsRequest(requestId = "req-4", kind = "ticket_choice",
            choices = listOf(LdsChoice(choiceNum = 1, ticketId = 31100000, count = 3))))
        assertEnvelope(ticket, "req-4", "ticket_choice")
        val ticketItem = ticket.getValue("choices").jsonArray[0].jsonObject
        assertEquals(31100000L, ticketItem.getValue("ticket_id").jsonPrimitive.long)
        assertEquals(3, ticketItem.getValue("count").jsonPrimitive.int)
        assertFalse(ticketItem.containsKey("unit_id"))
        val relic = encode(LdsRequest(requestId = "req-5", kind = "relic_choice",
            choices = listOf(LdsChoice(choiceNum = 1, relicId = 40001, count = 1))))
        assertEnvelope(relic, "req-5", "relic_choice")
        val relicItem = relic.getValue("choices").jsonArray[0].jsonObject
        assertEquals(40001L, relicItem.getValue("relic_id").jsonPrimitive.long)
        assertEquals(1, relicItem.getValue("count").jsonPrimitive.int)
        assertFalse(relicItem.containsKey("ticket_id"))
    }

    @Test fun eventRequestEncodesOptions() {
        val document = encode(LdsRequest(requestId = "req-6", kind = "event", eventId = 90001,
            options = listOf(LdsEventOption(choiceNum = 1, choiceId = 900011, conditionType = 1, conditionValue = 3000, unitId = 102501))))
        assertEnvelope(document, "req-6", "event")
        assertEquals(90001L, document.getValue("event_id").jsonPrimitive.long)
        val option = document.getValue("options").jsonArray[0].jsonObject
        assertEquals(1, option.getValue("choice_num").jsonPrimitive.int)
        assertEquals(900011L, option.getValue("choice_id").jsonPrimitive.long)
        assertEquals(1, option.getValue("condition_type").jsonPrimitive.int)
        assertEquals(3000L, option.getValue("condition_value").jsonPrimitive.long)
        assertEquals(102501L, option.getValue("unit_id").jsonPrimitive.long)
    }

    @Test fun teamRequestEncodesPartyFields() {
        val document = encode(LdsRequest(requestId = "req-7", kind = "team", questId = 770312201, partyCount = 3,
            pastAttempts = listOf(LdsPastAttempt(parties = listOf(listOf(102501, 130801)), outcome = "defeat")),
            unitNpcFlags = listOf(LdsNpcFlag(unitId = 130801, isNpc = true))))
        assertEnvelope(document, "req-7", "team")
        assertEquals(770312201L, document.getValue("quest_id").jsonPrimitive.long)
        assertEquals(3, document.getValue("party_count").jsonPrimitive.int)
        val attempt = document.getValue("past_attempts").jsonArray[0].jsonObject
        assertEquals("defeat", attempt.getValue("outcome").jsonPrimitive.content)
        assertEquals(102501L, attempt.getValue("parties").jsonArray[0].jsonArray[0].jsonPrimitive.long)
        val flag = document.getValue("unit_npc_flags").jsonArray[0].jsonObject
        assertEquals(130801L, flag.getValue("unit_id").jsonPrimitive.long)
        assertTrue(flag.getValue("is_npc").jsonPrimitive.boolean)
    }

    @Test fun openingAndPathRequestsEncodeMapBlocks() {
        val opening = encode(LdsRequest(requestId = "req-8", kind = "opening", attempt = 2, map = listOf(
            LdsMapBlock(blockId = 30101, region = 3, column = 1, blockType = 1, nextBlockIds = listOf(30201)),
            LdsMapBlock(blockId = 30201, region = 3, column = 2, blockType = 8, nextBlockIds = emptyList(), bossUnitIds = listOf(301206)))))
        assertEnvelope(opening, "req-8", "opening")
        assertEquals(2, opening.getValue("attempt").jsonPrimitive.int)
        val first = opening.getValue("map").jsonArray[0].jsonObject
        assertEquals(30101L, first.getValue("block_id").jsonPrimitive.long)
        assertEquals(3, first.getValue("region").jsonPrimitive.int)
        assertEquals(1, first.getValue("column").jsonPrimitive.int)
        assertEquals(1, first.getValue("block_type").jsonPrimitive.int)
        assertEquals(listOf(30201L), first.getValue("next_block_ids").jsonArray.map { it.jsonPrimitive.long })
        assertFalse(first.containsKey("cleared"))
        assertTrue(opening.getValue("map").jsonArray[1].jsonObject.containsKey("boss_unit_ids"))
        val path = encode(LdsRequest(requestId = "req-9", kind = "path", region = 3, blockId = 30101, map = listOf(
            LdsMapBlock(blockId = 30101, region = 3, column = 1, blockType = 1, nextBlockIds = listOf(30201), cleared = false),
            LdsMapBlock(blockId = 30201, region = 3, column = 2, blockType = 8, nextBlockIds = emptyList(), bossUnitIds = listOf(301206), cleared = true))))
        assertEnvelope(path, "req-9", "path")
        assertEquals(30101L, path.getValue("block_id").jsonPrimitive.long)
        assertFalse(path.getValue("map").jsonArray[0].jsonObject.getValue("cleared").jsonPrimitive.boolean)
        assertTrue(path.getValue("map").jsonArray[1].jsonObject.getValue("cleared").jsonPrimitive.boolean)
    }

    @Test fun scoreCheckRequestEncodesProgress() {
        val document = encode(LdsRequest(requestId = "req-10", kind = "score_check", region = 5, blockId = 50801,
            difficulty = 5, guildId = 3, questId = 770532001, unitCount = 50, relicCount = 26,
            normalClearCount = 6, exClearCount = 6, bossClearCount = 1,
            areas = listOf(LdsArea(region = 1, blockCount = 5, cleared = true))))
        assertEnvelope(document, "req-10", "score_check")
        assertEquals(50801L, document.getValue("block_id").jsonPrimitive.long)
        assertEquals(3L, document.getValue("guild_id").jsonPrimitive.long)
        assertEquals(770532001L, document.getValue("quest_id").jsonPrimitive.long)
        assertEquals(50, document.getValue("unit_count").jsonPrimitive.int)
        assertEquals(26, document.getValue("relic_count").jsonPrimitive.int)
        assertEquals(6, document.getValue("normal_clear_count").jsonPrimitive.int)
        assertEquals(6, document.getValue("ex_clear_count").jsonPrimitive.int)
        assertEquals(1, document.getValue("boss_clear_count").jsonPrimitive.int)
        val area = document.getValue("areas").jsonArray[0].jsonObject
        assertEquals(1, area.getValue("region").jsonPrimitive.int)
        assertEquals(5, area.getValue("block_count").jsonPrimitive.int)
        assertTrue(area.getValue("cleared").jsonPrimitive.boolean)
    }

    @Test fun ticketClearRequestEncodesGuildAndTickets() {
        val document = encode(LdsRequest(requestId = "req-11", kind = "ticket_clear", difficulty = 5, guildId = 3, ticketCount = 1))
        assertEnvelope(document, "req-11", "ticket_clear")
        assertEquals(3L, document.getValue("guild_id").jsonPrimitive.long)
        assertEquals(1, document.getValue("ticket_count").jsonPrimitive.int)
        assertFalse(document.containsKey("quest_id"))
        assertFalse(document.containsKey("map"))
        assertFalse(document.containsKey("choices"))
    }

    @Test fun selectResponsesDecodeChoiceNumAndUnitIds() {
        val choice = decode("""{"request_id":"req-1","action":"select","choice_num":2}""")
        assertEquals("req-1", choice?.requestId)
        assertEquals("select", choice?.action)
        assertEquals(2, choice?.choiceNum)
        assertEquals(listOf(102501L, 130801L), decode("""{"action":"select","unit_ids":[102501,130801]}""")?.unitIds)
    }

    @Test fun openingResponsesDecodeMaxAttemptsAndRejectedRegion() {
        assertEquals(100, decode("""{"action":"opening_accept","max_attempts":100}""")?.maxAttempts)
        assertNull(decode("""{"action":"opening_accept","max_attempts":100}""")?.rejectedRegion)
        val retry = decode("""{"action":"opening_retry","max_attempts":100,"rejected_region":3}""")
        assertEquals(100, retry?.maxAttempts)
        assertEquals(3, retry?.rejectedRegion)
        assertEquals("opening_stop", decode("""{"action":"opening_stop"}""")?.action)
    }

    @Test fun scorePathAndClearResponsesDecodeTheirFields() {
        assertEquals(30201L, decode("""{"action":"move","next_block_id":30201}""")?.nextBlockId)
        val retryRun = decode("""{"action":"retry_run","score":50800,"minimum_score":52000}""")
        assertEquals("retry_run", retryRun?.action)
        assertEquals(50800, retryRun?.score)
        assertEquals(52000, retryRun?.minimumScore)
        val fight = decode("""{"action":"fight","score":50800,"minimum_score":52000}""")
        assertEquals(50800, fight?.score)
        assertEquals(52000, fight?.minimumScore)
        assertEquals(3L, decode("""{"action":"continue_run","guild_id":3}""")?.guildId)
    }

    @Test fun teamAndNoopResponsesDecode() {
        assertEquals(listOf(listOf(102501L, 130801L), listOf(110301L)),
            decode("""{"action":"team","parties":[[102501,130801],[110301]]}""")?.parties)
        val manual = decode("""{"action":"manual","defeat_count":5,"max_retries":4}""")
        assertEquals(5, manual?.defeatCount)
        assertEquals(4, manual?.maxRetries)
        assertEquals("noop", decode("""{"action":"noop"}""")?.action)
    }

    @Test fun errorAndMalformedResponsesHaveNoAction() {
        val error = decode("""{"error":"unknown kind"}""")
        assertEquals("unknown kind", error?.error)
        assertNull(error?.action)
        assertNull(decode("""{"request_id":"req-1","error":"Internal decision error"}""")?.action)
        assertNull(decode("<html>body too large</html>"))
        assertNull(decode("""{"action":12}"""))
    }

    @Test fun unknownResponseFieldsAreIgnored() {
        assertEquals(1, decode("""{"request_id":"req","action":"select","choice_num":1,"diagnosis":"x"}""")?.choiceNum)
    }

    @Test fun choiceAndRosterOutcomesMapSelectOnly() {
        assertEquals(2, ldsChoiceOutcome(LdsResponse(action = "select", choiceNum = 2))?.choiceNum)
        assertNull(ldsChoiceOutcome(LdsResponse(action = "select")))
        assertNull(ldsChoiceOutcome(LdsResponse(action = "noop", choiceNum = 2)))
        assertNull(ldsChoiceOutcome(LdsResponse(error = "unknown kind")))
        assertEquals(listOf(102501L), ldsOpeningRosterOutcome(LdsResponse(action = "select", unitIds = listOf(102501)))?.unitIds)
        assertNull(ldsOpeningRosterOutcome(LdsResponse(action = "select")))
        assertNull(ldsOpeningRosterOutcome(LdsResponse(action = "noop", unitIds = listOf(102501))))
    }

    @Test fun openingFilterOutcomesMapAllThreeActions() {
        assertEquals(LabyrinthDecisionGateway.OpeningFilterOutcome.Accept(100),
            ldsOpeningFilterOutcome(LdsResponse(action = "opening_accept", maxAttempts = 100)))
        assertEquals(LabyrinthDecisionGateway.OpeningFilterOutcome.Retry(3, 100),
            ldsOpeningFilterOutcome(LdsResponse(action = "opening_retry", maxAttempts = 100, rejectedRegion = 3)))
        assertEquals(LabyrinthDecisionGateway.OpeningFilterOutcome.Stop,
            ldsOpeningFilterOutcome(LdsResponse(action = "opening_stop")))
        assertNull(ldsOpeningFilterOutcome(LdsResponse(action = "noop")))
    }

    @Test fun scoreCheckOutcomesMapFightAndRetryRun() {
        assertEquals(LabyrinthDecisionGateway.ScoreCheckOutcome.Fight(50800, 52000),
            ldsScoreCheckOutcome(LdsResponse(action = "fight", score = 50800, minimumScore = 52000)))
        assertEquals(LabyrinthDecisionGateway.ScoreCheckOutcome.RetryRun(50800, 52000),
            ldsScoreCheckOutcome(LdsResponse(action = "retry_run", score = 50800, minimumScore = 52000)))
        assertNull(ldsScoreCheckOutcome(LdsResponse(action = "noop")))
    }

    @Test fun ticketClearOutcomesContinueOnContinueRunAndStopOnNoop() {
        assertEquals(LabyrinthDecisionGateway.TicketClearOutcome.ContinueRun(3L),
            ldsTicketClearOutcome(LdsResponse(action = "continue_run", guildId = 3)))
        assertEquals(LabyrinthDecisionGateway.TicketClearOutcome.Stop, ldsTicketClearOutcome(LdsResponse(action = "noop")))
        assertNull(ldsTicketClearOutcome(LdsResponse(error = "x")))
    }

    @Test fun teamPlanAndNodeRouteOutcomesRequireMatchingActionAndPayload() {
        assertEquals(LabyrinthDecisionGateway.TeamPlanOutcome.Parties(listOf(listOf(102501L))),
            ldsTeamPlanOutcome(LdsResponse(action = "team", parties = listOf(listOf(102501)))))
        assertEquals(LabyrinthDecisionGateway.TeamPlanOutcome.RetryRun, ldsTeamPlanOutcome(LdsResponse(action = "retry_run")))
        assertNull(ldsTeamPlanOutcome(LdsResponse(action = "team")))
        assertNull(ldsTeamPlanOutcome(LdsResponse(action = "manual", defeatCount = 5, maxRetries = 4)))
        assertEquals(30201L, ldsNodeRouteOutcome(LdsResponse(action = "move", nextBlockId = 30201))?.nextBlockId)
        assertNull(ldsNodeRouteOutcome(LdsResponse(action = "move")))
        assertNull(ldsNodeRouteOutcome(LdsResponse(action = "noop", nextBlockId = 30201)))
    }

    @Test fun unavailableContextAndDisabledTogglesFallBackWithoutNetwork() {
        val gateway = LdsDecisionGateway("http://127.0.0.1:9", LdsDecisionToggles())
        assertNull(gateway.eventChoice(EventChoiceRequest(90001, emptyList())))
        assertFalse(gateway.uploadResultScreenshot(ByteArray(1), 3, 1, 1, 100))
        gateway.updateRunContext(context(enterId = 0))
        assertNull(gateway.eventChoice(EventChoiceRequest(90001, emptyList())))
        gateway.updateRunContext(context(difficulty = 0))
        assertNull(gateway.openingRoster(OpeningRosterRequest(1, 31100001, listOf(102501))))
        gateway.updateRunContext(LdsRunContext(1, 1, 5, region = 6, blockId = 0, gold = 0, ownedUnitIds = emptyList(), ownedRelicIds = emptyList()))
        assertNull(gateway.nodeRoute(NodeRouteRequest(30101, emptyList())))
        gateway.updateRunContext(context())
        assertNull(LdsDecisionGateway("http://127.0.0.1:9", LdsDecisionToggles(enabled = false))
            .teamPlan(TeamPlanRequest(770312201, 1, emptyList(), emptyList())))
        gateway.updateRunContext(null)
        assertNull(gateway.relicChoice(com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.RelicChoiceRequest(
            com.landosol.toolbox.labyrinth.decision.LabyrinthDecisionGateway.RelicChoiceRequest.Kind.RELIC, emptyList())))
    }
}
