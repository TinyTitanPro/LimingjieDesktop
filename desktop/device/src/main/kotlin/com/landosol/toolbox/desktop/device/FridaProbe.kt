package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 黎明界只读内存探针的设备侧编排与 JSON 行 RPC 通道。
 *
 * 只读不写：探针脚本仅读 ViewManager 当前视图（类名 + ViewId），无 hook、无内存写入。
 * 每设备独立实例：本地监听端口由 ServerSocket(0) 分配，`adb reverse` 一对一映射，
 * 多 JVM 设备会话互不冲突。注入器为 frida-inject（免 frida-server），二进制按
 * 上游 SHA 锁校验；需要模拟器 root（`su` 可用）。通道方向：脚本在模拟器内回连
 * 127.0.0.1:<port>（经 adb reverse 到宿主监听口），宿主侧无需 frida 客户端库。
 * 请求严格串行（识别流水线单工作线程调用）。
 */
sealed interface ProbeStatus {
    data object Idle : ProbeStatus
    data class Starting(val detail: String) : ProbeStatus
    data class Ready(val gamePid: Int, val build: String, val arch: String) : ProbeStatus
    data class Failed(val reason: String) : ProbeStatus
}

@Serializable
data class ProbePage(
    val ok: Boolean = false,
    val view: String? = null,
    val viewId: Int? = null,
    /** DialogArea 活动弹窗数；-1/缺省表示未知。>0 时上层不短路、交给视觉。 */
    val dialogCount: Int? = null,
    /** 视图字段未解析时的诊断串（fields RPC 可核对字段名）。 */
    val viewDiag: String? = null,
    val err: String? = null,
)

/** 页快照查询面（识别链只依赖此接口，测试可替换实现）。 */
interface ProbePageSource {
    val status: kotlinx.coroutines.flow.StateFlow<ProbeStatus>
    /** 阻塞读取；未就绪/超时/断线返回 null（上层降级视觉）。 */
    fun page(budgetMillis: Long = 250): ProbePage?
}

/** 可重试的探针内部失败；其余失败视为配置/环境问题，停止重试。 */
private class RetryableProbeException(message: String) : Exception(message)

object FridaProbeAssets {
    const val RESOURCE = "/device/labyrinth-probe.js"
    const val DEVICE_INJECT_PATH = "/data/local/tmp/.ljinject"
    const val DEVICE_SCRIPT_PATH = "/data/local/tmp/.ljab.js"
    const val DATA_SUBDIR = "tools/frida"
    const val INJECT_FILE_NAME = "frida-inject-android-x86_64"

    /** frida-inject 17.16.4 android-x86_64（frida 官方 release，未改动）。 */
    const val INJECT_VERSION = "17.16.4"
    const val INJECT_SHA256 = "0a5be149aa383969ce1bfed3b099845ccd85bda239723227392264575ba07c44"

    fun scriptResourceBytes(): ByteArray =
        FridaProbeAssets::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: throw DeviceOperationException("探针脚本资源缺失，请重新解压完整发行包")

    /** 端口在推送前写入脚本首行；其余内容逐字来自资源。 */
    fun scriptText(port: Int): String =
        scriptResourceBytes().toString(Charsets.UTF_8)
            .replaceFirst(Regex("var BRIDGE_PORT = \\d+;"), "var BRIDGE_PORT = $port;")

    /** 宿主侧注入器二进制定位 + SHA 锁校验；未提供或校验失败返回原因（探针保持停用）。 */
    fun locateInjectBinary(dataDirectory: Path): Pair<Path, String>? {
        val candidate = dataDirectory.resolve("$DATA_SUBDIR/$INJECT_FILE_NAME")
        if (!Files.isRegularFile(candidate)) return null
        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(candidate))
            .joinToString("") { "%02x".format(it) }
        if (hash != INJECT_SHA256) return null
        return candidate to INJECT_VERSION
    }

    /** 未提供注入器时给用户的放置指引（含来源与校验值）。 */
    fun missingBinaryHint(dataDirectory: Path): String =
        "内存探针未启用：请将 frida-inject $INJECT_VERSION (android-x86_64) 放到 " +
            "${dataDirectory.resolve("$DATA_SUBDIR/$INJECT_FILE_NAME")}，SHA-256 须为 $INJECT_SHA256"
}

class FridaProbeTransport private constructor(
    private val adb: ProcessAdbExecutor,
    private val serial: String,
    private val gamePackage: String,
    private val injectBinary: Path,
    private val binaryVersion: String,
) : AutoCloseable, ProbePageSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val statusFlow = MutableStateFlow<ProbeStatus>(ProbeStatus.Idle)
    override val status: kotlinx.coroutines.flow.StateFlow<ProbeStatus> = statusFlow

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val channelLock = ReentrantLock()
    private var server: ServerSocket? = null
    private var channel: LineChannel? = null
    private var injectProcess: Process? = null
    private var supervisor: Job? = null
    private var reversePort = -1
    private var nextRequestId = 1
    private var build = "mb1"

    fun start() {
        if (!started.compareAndSet(false, true)) return
        supervisor = scope.launch {
            try {
                deploy()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                statusFlow.value = ProbeStatus.Failed(failure.message ?: "探针启动失败")
                return@launch
            }
            var attempt = 0
            while (isActive && !closed.get()) {
                try {
                    attachOnce()
                    attempt = 0
                    superviseInjected()
                    if (closed.get()) break
                    statusFlow.value = ProbeStatus.Starting("探针掉线，即将重试")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    statusFlow.value = ProbeStatus.Failed(failure.message ?: "探针失败")
                    if (failure !is RetryableProbeException) return@launch
                }
                attempt++
                delay(3000L + attempt.coerceAtMost(6) * 2000L)
            }
        }
    }

    /** 阻塞读取当前页面快照；未就绪/超时/断线返回 null（上层降级视觉），绝不抛出。 */
    override fun page(budgetMillis: Long): ProbePage? {
        val reply = requestJson("page", "[]", budgetMillis) ?: return null
        val ok = reply["ok"]?.jsonPrimitive?.content == "true"
        if (!ok) return ProbePage(ok = false, err = reply["err"]?.jsonPrimitive?.content)
        val data = reply["data"] as? JsonObject ?: return ProbePage(ok = false, err = "bad-data")
        return json.decodeFromJsonElement(ProbePage.serializer(), data)
    }

    /** 诊断：列出某类字段名（name@offset）。仅 probeMemory 真机核对用，不进常规链路。 */
    fun fields(className: String, budgetMillis: Long = 1000): String? {
        val reply = requestJson("fields", "[${JsonPrimitive(className)}]", budgetMillis) ?: return null
        return reply["data"]?.toString()
    }

    /** 串行 JSON 行请求；断线/超时返回 null 并丢弃通道。 */
    private fun requestJson(fn: String, argsJson: String, budgetMillis: Long): JsonObject? {
        if (closed.get()) return null
        val active = channel ?: return null
        return channelLock.withLock {
            val current = channel ?: return@withLock null
            try {
                val id = nextRequestId++
                current.soTimeout(budgetMillis)
                current.write("""{"id":$id,"fn":"$fn","args":$argsJson}""")
                while (true) {
                    val line = current.readLine() ?: return@withLock null
                    val reply = json.parseToJsonElement(line) as? JsonObject ?: continue
                    val replyId = reply["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                    if (replyId == id) return@withLock reply
                }
                @Suppress("UNREACHABLE_CODE") null
            } catch (_: Exception) {
                dropChannel()
                null
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        supervisor?.cancel()
        dropChannel()
        channelLock.withLock {
            server?.close(); server = null
        }
        injectProcess?.let(adb::release)
        injectProcess = null
        runCatching {
            runBlocking { withTimeout(3000) { removeReverse() } }
        }
        runCatching {
            runBlocking { withTimeout(3000) { adb.shell(serial, listOf("su", "-c", "'pkill -f \\.ljinject'"), 5000) } }
        }
        scope.cancel()
        statusFlow.value = ProbeStatus.Idle
    }

    // ── 内部：部署（一次）/ attach（可循环）/ 通道 ─────────────────────────────

    private suspend fun deploy() {
        ensureRoot()
        bindListener()
        pushAssets()
        setReverse(reversePort)
    }

    private suspend fun ensureRoot() {
        statusFlow.value = ProbeStatus.Starting("检查模拟器 root")
        val id = adb.shell(serial, listOf("su", "-c", "id"), 5000)
        val text = (id as? AdbResult.Success)?.text().orEmpty()
        if (id !is AdbResult.Success || "uid=0" !in text) {
            throw DeviceOperationException("内存探针需要模拟器 root（su），当前不可用；已继续使用截图识别")
        }
    }

    private fun bindListener() {
        channelLock.withLock {
            if (server == null) server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            reversePort = checkNotNull(server).localPort
        }
    }

    private suspend fun pushAssets() {
        statusFlow.value = ProbeStatus.Starting("推送探针组件")
        val localScript = Files.createTempFile("ljab", ".js").apply {
            Files.writeString(this, FridaProbeAssets.scriptText(reversePort))
        }
        val localInject = Files.createTempFile("ljinject", ".bin")
        try {
            Files.copy(injectBinary, localInject, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            adb.execute(listOf("push", localInject.toString(), FridaProbeAssets.DEVICE_INJECT_PATH), serial, 180_000)
                .requireSuccess()
            adb.execute(listOf("push", localScript.toString(), FridaProbeAssets.DEVICE_SCRIPT_PATH), serial, 30_000)
                .requireSuccess()
            adb.shell(serial, listOf("chmod", "755", FridaProbeAssets.DEVICE_INJECT_PATH), 5000).requireSuccess()
        } finally {
            Files.deleteIfExists(localInject)
            Files.deleteIfExists(localScript)
        }
    }

    private suspend fun setReverse(port: Int) {
        adb.execute(listOf("reverse", "tcp:$port", "tcp:$port"), serial, 5000).requireSuccess()
    }

    private suspend fun removeReverse() {
        val port = reversePort
        if (port > 0) runCatching { adb.execute(listOf("reverse", "--remove", "tcp:$port"), serial, 5000) }
    }

    private suspend fun attachOnce() {
        val pid = awaitGamePid()
        statusFlow.value = ProbeStatus.Starting("注入探针")
        val process = adb.openInteractive(
            listOf("shell", "su -c '${FridaProbeAssets.DEVICE_INJECT_PATH} -p $pid -s ${FridaProbeAssets.DEVICE_SCRIPT_PATH}'"),
            serial,
        )
        injectProcess = process
        // 注入器 stderr 只排空；脚本日志经 socket 回连，不依赖 stderr。
        scope.launch(Dispatchers.IO) {
            try { process.errorStream.use { input -> val buffer = ByteArray(8192); while (input.read(buffer) >= 0) { } } }
            catch (_: IOException) { }
        }
        awaitHello()
        statusFlow.value = ProbeStatus.Ready(pid, build, "x86_64")
    }

    private suspend fun awaitGamePid(): Int {
        statusFlow.value = ProbeStatus.Starting("等待游戏进程")
        repeat(90) {
            if (closed.get()) throw CancellationException("探针已关闭")
            val result = adb.shell(serial, listOf("pidof", gamePackage), 5000)
            val pid = (result as? AdbResult.Success)?.text()?.trim()
                ?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
            if (pid != null && pid > 0) return pid
            delay(1000)
        }
        throw RetryableProbeException("游戏进程未运行，探针等待超时")
    }

    private suspend fun awaitHello() {
        statusFlow.value = ProbeStatus.Starting("等待探针回连")
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            if (closed.get()) throw CancellationException("探针已关闭")
            val listening = server?.takeIf { !it.isClosed } ?: throw RetryableProbeException("探针监听已关闭")
            val socket = try { runInterruptible(Dispatchers.IO) { listening.accept() } }
            catch (_: Exception) { delay(200); continue }
            socket.tcpNoDelay = true
            val candidate = LineChannel(socket)
            val hello = channelLock.withLock {
                try { candidate.awaitEvent("hello", 8000) } catch (_: Exception) { null }
            }
            if (hello != null) {
                channelLock.withLock { channel = candidate }
                if (hello.build.isNotBlank()) build = hello.build
                return
            }
            candidate.close()
        }
        throw RetryableProbeException("探针脚本未回连")
    }

    private suspend fun superviseInjected() {
        val process = injectProcess ?: return
        scope.launch {
            while (isActive && !closed.get() && process.isAlive) delay(1000)
            if (!closed.get()) dropChannel()
        }.join()
    }

    private fun dropChannel() = channelLock.withLock {
        channel?.close()
        channel = null
    }

    companion object {
        /** 二进制在位则创建；否则返回 (null, 用户可见的放置指引)。 */
        fun open(
            adb: ProcessAdbExecutor, serial: String, gamePackage: String,
            dataDirectory: Path,
        ): Pair<FridaProbeTransport?, String?> {
            val located = FridaProbeAssets.locateInjectBinary(dataDirectory)
                ?: return null to FridaProbeAssets.missingBinaryHint(dataDirectory)
            val transport = FridaProbeTransport(adb, serial, gamePackage, located.first, located.second)
            return transport to null
        }
    }
}

/** 探针脚本 JSON 行通道：请求/回包与 hello 事件共用一条连接，串行访问（调用方持锁）。 */
internal class LineChannel(private val socket: Socket) : AutoCloseable {
    private val output = socket.getOutputStream()
    private val input = socket.getInputStream()
    private var pending = ""

    fun soTimeout(budgetMillis: Long) {
        socket.soTimeout = budgetMillis.toInt().coerceIn(1, 60_000)
    }

    fun write(text: String) {
        output.write((text + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** 读一行（\n 结尾）；流断开或超时返回 null。跨调用保留半行。 */
    fun readLine(): String? {
        return try {
            val builder = StringBuilder(pending)
            pending = ""
            while (true) {
                val byte = input.read()
                if (byte < 0) return null
                if (byte == '\n'.code) break
                builder.append(byte.toChar())
            }
            builder.toString()
        } catch (_: IOException) {
            null
        }
    }

    /** 阻塞等待一条事件（如 hello）；budget 内未到返回 null。 */
    fun awaitEvent(event: String, budgetMillis: Long): HelloInfo? {
        soTimeout(budgetMillis)
        val deadline = System.nanoTime() + budgetMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            val line = readLine() ?: return null
            val parsed = Json.decodeFromString(JsonObject.serializer(), line)
            if (parsed["event"]?.jsonPrimitive?.content == event) {
                fun field(name: String) = parsed[name]?.jsonPrimitive?.content
                return HelloInfo(field("build").orEmpty(), field("pid")?.toIntOrNull() ?: -1, field("arch").orEmpty())
            }
        }
        return null
    }

    override fun close() {
        runCatching { socket.close() }
    }

    data class HelloInfo(val build: String, val pid: Int, val arch: String)
}
