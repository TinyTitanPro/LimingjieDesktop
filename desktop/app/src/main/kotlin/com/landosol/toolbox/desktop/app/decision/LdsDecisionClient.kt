package com.landosol.toolbox.desktop.app.decision

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/** LDS 本机 HTTP 客户端：阻塞式实现，调用方在后台线程使用；失败返回 null/false，类内不做重试。 */
class LdsDecisionClient(endpoint: String) {
    @Volatile private var base = endpoint.trimEnd('/')
    @Volatile private var address = resolveAddress(base)
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        // 执行层在识别线程内同步咨询，服务挂起也必须尽快降级回内置逻辑。
        .callTimeout(DECISION_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).build()

    /** 设置页热更新端点；后续请求立即生效。 */
    fun updateEndpoint(endpoint: String) {
        base = endpoint.trimEnd('/')
        address = resolveAddress(base)
    }

    /** POST /labyrinth/decision：HTTP 200 返回报文，其他状态或异常返回 null（协议错误也按失败降级）。 */
    fun postDecision(json: String, uid: Long): String? = try {
        http.newCall(Request.Builder().url("$base/labyrinth/decision").header("uid", uid.toString())
            .post(json.toRequestBody("application/json".toMediaType())).build()
        ).execute().use { response -> if (response.code == 200) response.body?.string() else null }
    } catch (ignored: Exception) { null }

    /** TCP 连接探测，不发起决策请求。 */
    fun ping(): Boolean = try {
        Socket().use { it.connect(address, CONNECT_TIMEOUT_MILLIS.toInt()) }
        true
    } catch (ignored: Exception) { false }

    /** POST /labyrinth/result-screenshot：HTTP 201 视为成功。 */
    fun uploadResultScreenshot(png: ByteArray, guildId: Long, unitCount: Int, relicCount: Int, score: Int, uid: Long): Boolean = try {
        val url = "$base/labyrinth/result-screenshot?guild_id=$guildId&unit_count=$unitCount&relic_count=$relicCount&score=$score"
        http.newCall(Request.Builder().url(url).header("uid", uid.toString())
            .post(png.toRequestBody("image/png".toMediaType())).build()
        ).execute().use { it.code == 201 }
    } catch (ignored: Exception) { false }

    private fun resolveAddress(base: String): InetSocketAddress =
        URI(base).run { InetSocketAddress(host, if (port > 0) port else if (scheme == "https") 443 else 80) }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 2_000L
        const val DECISION_CALL_TIMEOUT_MILLIS = 4_000L
    }
}
