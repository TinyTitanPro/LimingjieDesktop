package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.RelicResource
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOpeningRelicState
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

internal fun openingRelicResources(): List<RelicResource> {
    val file = listOf(File("src/main/assets/resource-packs/cn-bilibili/relics.laby-workbook.json"),
        File("app/src/main/assets/resource-packs/cn-bilibili/relics.laby-workbook.json"))
        .first { it.isFile }
    val json = Json { ignoreUnknownKeys = true }
    return json.parseToJsonElement(file.readText()).jsonObject.getValue("relics").jsonArray
        .map { json.decodeFromJsonElement<RelicResource>(it) }
}

class LabyrinthOpeningRelicCatalogTest {
    private val resources = openingRelicResources()
    private val catalog = LabyrinthOpeningRelicCatalog(resources)
    private val rect = EntryPixelRect(0, 0, 1, 1)

    @Test fun `every guild grants its base relic and node 31 alone unlocks the second`() {
        val expected = mapOf(1 to listOf("40001", "50001"), 2 to listOf("40002", "50002"),
            3 to listOf("40003", "50003"), 4 to listOf("40004", "50004"), 5 to listOf("40005", "50005"))
        assertEquals(LabyrinthRerollOptions.guilds.map { it.guildId }.toSet(), expected.keys)
        for ((guild, ids) in expected) {
            val base = catalog.grants(LabyrinthOpeningRelicState(guild, setOf(28, 29, 30, 32)), rect)
            assertEquals(ids.take(1), base.relics.map { it.relicId })
            assertTrue(base.complete)
            val both = catalog.grants(LabyrinthOpeningRelicState(guild, setOf(31)), rect)
            assertEquals(ids, both.relics.map { it.relicId })
            assertTrue(both.complete)
            assertTrue(both.relics.all { it.attributeBonus == "1" && it.displayName == resources.first { r -> r.id == it.relicId }.name })
        }
    }

    @Test fun `missing account nodes do not fabricate a second relic or a complete baseline`() {
        val result = catalog.grants(LabyrinthOpeningRelicState(3), rect)
        assertEquals(listOf("40003"), result.relics.map { it.relicId })
        assertFalse(result.complete)
        assertFalse(catalog.grants(LabyrinthOpeningRelicState(3, setOf(0, 31)), rect).complete)
        assertEquals(listOf("40003"), catalog.grants(LabyrinthOpeningRelicState(3, emptySet()), rect).relics.map { it.relicId })
        assertTrue(catalog.grants(LabyrinthOpeningRelicState(3, emptySet()), rect).complete)
    }

    @Test fun `unknown guild and missing catalog entry never claim a complete grant`() {
        assertTrue(catalog.grants(null, rect).relics.isEmpty())
        assertTrue(catalog.grants(LabyrinthOpeningRelicState(99, setOf(31)), rect).relics.isEmpty())
        val incomplete = LabyrinthOpeningRelicCatalog(resources.filterNot { it.id == "50003" })
            .grants(LabyrinthOpeningRelicState(3, setOf(31)), rect)
        assertFalse(incomplete.complete)
        assertEquals(listOf("40003"), incomplete.relics.map { it.relicId })
    }

    @Test fun `fixed series totals follow the catalog without imprint or random effect guesses`() {
        val marks = listOf(listOf("守备", "强化"), listOf("强化", "守备"), listOf("加速", "会心"),
            listOf("会心", "弱体"), listOf("弱体", "加速"))
        for (guild in 1..5) assertEquals(marks[guild - 1],
            catalog.grants(LabyrinthOpeningRelicState(guild, setOf(31)), rect).relics.map { it.attribute })
        assertEquals(2, catalog.grants(LabyrinthOpeningRelicState(5, setOf(31)), rect).relics.size)
    }
}
