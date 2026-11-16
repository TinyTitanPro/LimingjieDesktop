package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * 显式手动验证：frida-inject 部署 + 只读页快照 RPC 真机直验。
 * 需要模拟器 root 与已放置并校验的注入器二进制（见 FridaProbeAssets.missingBinaryHint）。
 * 仅读取当前视图类名/ViewId/弹窗计数，不发送任何游戏输入。
 *
 * 用法：./gradlew probeMemory -PprobeAdb=<adb> -PprobeSerial=<serial> -PprobeOutput=<dir>
 *       [-PprobeGame=com.bilibili.priconne] [-PprobeSeconds=30]
 */
fun main(args: Array<String>) {
    require(args.size >= 3 && args.take(3).all(String::isNotBlank)) { "Specify probeAdb, probeSerial and probeOutput explicitly" }
    val dataDirectory = Path.of(args[2]).also(Files::createDirectories)
    val gamePackage = args.getOrElse(3) { "com.bilibili.priconne" }
    val seconds = args.getOrElse(4) { "30" }.toLongOrNull()?.coerceIn(1, 600) ?: 30L
    val located = FridaProbeAssets.locateInjectBinary(dataDirectory)
    if (located == null) {
        println(FridaProbeAssets.missingBinaryHint(dataDirectory))
        return
    }
    ProcessAdbExecutor(args[0]).use { adb ->
        val (transport, reason) = checkNotNull(FridaProbeTransport.open(adb, args[1], gamePackage, dataDirectory))
        checkNotNull(transport) { reason ?: "内存探针创建失败" }
        transport.start()
        runBlocking {
            try {
                withTimeout(seconds * 1000 + 150_000) {
                    var lastStatus: ProbeStatus? = null
                    var pages = 0
                    var hits = 0
                    var fieldDumped = false
                    val startedAt = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startedAt < seconds * 1000) {
                        val status = transport.status.value
                        if (status != lastStatus) {
                            println("status: $status")
                            lastStatus = status
                        }
                        val page = transport.page(1000)
                        if (page != null) {
                            pages++
                            if (page.view != null) hits++
                            println("page#$pages: $page")
                            // il2cpp 初始化（类扫描+校准）需约 1 秒，首个 ok 页后再核对字段表。
                            if (!fieldDumped && page.ok) {
                                fieldDumped = true
                                println("ViewManager fields: " + (transport.fields("ViewManager") ?: "<unavailable>"))
                            }
                        }
                        delay(300)
                    }
                    println("summary: pages=$pages viewHits=$hits within=${seconds}s")
                }
            } finally {
                transport.close()
            }
        }
    }
    println("probeMemory completed")
}
