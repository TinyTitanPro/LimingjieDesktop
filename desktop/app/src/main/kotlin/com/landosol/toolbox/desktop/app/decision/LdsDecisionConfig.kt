package com.landosol.toolbox.desktop.app.decision

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 决策服务配置：持久化于 `labyrinth-decision` 偏好的 `config.v1`；默认关闭。 */
@Serializable
data class LdsDecisionConfig(
    val enabled: Boolean = false,
    val endpoint: String = "http://127.0.0.1:8081",
    val toggles: LdsDecisionToggles = LdsDecisionToggles(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String?): LdsDecisionConfig =
            raw?.let { runCatching { json.decodeFromString<LdsDecisionConfig>(it) }.getOrNull() } ?: LdsDecisionConfig()

        fun decode(element: JsonObject?): LdsDecisionConfig =
            element?.let { runCatching { json.decodeFromJsonElement(LdsDecisionConfig.serializer(), it) }.getOrNull() }
                ?: LdsDecisionConfig()

        fun encode(config: LdsDecisionConfig): String = json.encodeToString(LdsDecisionConfig.serializer(), config)
    }
}
