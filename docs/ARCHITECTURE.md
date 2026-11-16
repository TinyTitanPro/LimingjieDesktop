# 项目架构说明与进度

本文档说明黎明界助手桌面版的整体架构、上游来源与借鉴边界、近期改动及预期效果。早期方案背景见 `docs/archive/`，单项决策见 [DECISIONS.md](DECISIONS.md)，上游同步边界见 [UPSTREAM.md](UPSTREAM.md)。

## 一、项目定位

黎明界助手桌面版在 Windows 上管理《公主连结 Re:Dive》的黎明界（露娜塔）任务：模拟器只负责运行游戏客户端，助手在桌面侧完成账号与策略管理、画面状态判定、动作执行与运行记录。业务逻辑复用上游 Android 共享源码，桌面层负责设备操作、界面、存储与多设备隔离。

## 二、技术栈与工具链

- 语言与框架：Kotlin 2.1.20 + Compose Multiplatform 1.8.2
- 构建：Gradle 8.12.1，JDK 21
- 数据库：Room 2.7.2 + `BundledSQLiteDriver`，schema identity 与上游一致
- 文字识别：ONNX Runtime + PP-OCRv5（中文 mobile 模型，SHA 锁见 `desktop/ocr/MODELS.md`）
- 设备控制：ADB（`exec-out screencap` 截图，MaaTouch 常驻触控，`dumpsys` 前台包名）
- 状态判定：截图模板匹配为主，新增只读内存探针（frida-inject）作为可选快路径
- 凭据安全：Windows DPAPI（CurrentUser）加密，不回退明文
- 发行：`jpackage` 生成含内置 JRE 的应用镜像，打包为便携 ZIP

## 三、模块架构与依赖边界

仓库分为共享上游与桌面工程两部分：

- `android/`：上游共享源码（业务逻辑、识别、协议、数据库迁移）
- `desktop/shared/`：桌面构建的同名替身、排除规则与派生清单
- `desktop/`：桌面工程，按单向依赖分层

桌面模块分层（D-40，`platform`、`device`、`ocr` 不反向依赖 `shared`，由 `app` 完成组装）：

| 模块 | 职责 |
| --- | --- |
| `platform` | 桌面垫片：`Context`、`SharedPreferences`、系统信息等 |
| `device` | ADB 设备操作、截图捕获、触控、前台守卫、只读内存探针 |
| `ocr` | PP-OCRv5 模型加载、检测/识别、短 ROI 回退 |
| `app` | 装配、Compose 界面、会话编排、策略与账号管理、持久化 |

多设备隔离（D-17）：主服务进程管理共享数据，每台在线模拟器运行在独立设备 worker JVM 中，互不干扰。

## 四、核心运行链路

```
设备连接 → 分辨率/前台包名守卫
  → 截图捕获（独立线程，300 ms 采样）
  → 页面状态判定（内存探针优先 → 截图视觉降级）
  → 动作执行（MaaTouch；点击前再次校验前台包名与停止许可）
  → 循环，直到任务结束
```

点击动作前实时校验前台包名（D-27），非挂起回调读取 500 ms 轮询缓存，超过 2 s 视为失效。分辨率仅在任务启动时检查，动作前复用启动许可，不额外截图。

## 五、上游仓库与借鉴功能

| 来源 | 借鉴内容 | 使用边界 |
| --- | --- | --- |
| [wbero/limingjie-assistant](https://github.com/wbero/limingjie-assistant) | 共享业务基线（识别、协议、迷宫流程、数据库） | 初始移植基线，通过替身与派生复用，不在桌面层复制分叉 |
| [SatenRuiko10032/limingjie-assistant](https://github.com/SatenRuiko10032/limingjie-assistant) | 十公会阵容与新增公会修正 | 选择性同步，目标提交由 `upstream-drift.properties` SHA 锁约束 |
| [cc004/autopcr](https://github.com/cc004/autopcr) 及本机 Android 参考 | 登录、协议与迷宫行为的可观察输入/输出/错误语义 | 不包含 Python、Chaquopy 或 AutoPCR HTTP 服务 |
| 项目所有者的可可萝自动会战助手（PC 版探针脚本） | 内存探针领域逻辑、Il2Cpp 字段偏移与稳定性铁律 | 自有领域逻辑可迁移；只读探针，不写内存 |
| [Ice9Coffee/LandosolRoster](https://github.com/Ice9Coffee/LandosolRoster) | 角色 ID、名称、别名与可用性 | GPL-3.0 来源声明保留，不推断评分/职能 |
| [国服数据库](https://github.com/SonderXiaoming/priconne-database) | 核对主键、技能和正式名称 | 完整数据库不打包进 APK，离线导入保持可重复 |
| [frida/frida](https://github.com/frida/frida) | `frida-inject` 17.16.4（android-x86_64） | 注入只读探针脚本；二进制按 SHA-256 锁校验 |
| [角色图标](https://redive.estertion.win/icon/unit/) 及所有者工作簿 | 结构化迷宫资料与可追溯图标 | 源素材保持本地，游戏素材权利属于原权利人 |

维护边界：保持 `upstream-drift.properties` 的 SHA 锁、分辨率与实时前台校验、同账号租约、DPAPI 凭据加密、原子存储及损坏备份。不提交凭据、令牌、会话密钥、未脱敏报文及本机参考素材。

## 六、本次改动：只读内存探针快路径

### 背景

原有页面状态判定完全依赖截图 + 模板匹配/OCR。在主页、地图、战斗中等高频页面，整帧匹配是可避免的开销。参考 `labyrinth-auto-release` APK 的 Frida 注入方案，其核心探针源自项目所有者本人的 PC 版脚本，属于可迁移的自有领域逻辑，故在桌面端实现只读内存探针作为状态判定的快路径。

### 设计原则

1. **只读不写**：探针仅读取 `ViewManager` 的当前视图（类名 + ViewId）与 `DialogArea` 的活动弹窗计数，零 hook、零内存写入。
2. **保守短路**：仅当"视图类名即页面全部语义"且活动弹窗数确认为 0 时才短路截图识别；弹窗页与依赖细判定的状态一律交视觉。
3. **失败降级**：探针未就绪、超时、断线或返回未知视图时，自动回落截图识别，不打断识别链。
4. **熔断保护**：传输层连续 8 次失败后停用 30 s，期间完全走视觉，避免阻塞识别流水线。

### 实现组成

| 文件 | 职责 |
| --- | --- |
| `desktop/device/.../device/FridaProbe.kt` | frida-inject 部署编排：root 检查、二进制 SHA 锁、`adb push`、`adb reverse`、JSON 行 RPC 客户端；每设备独立 `ServerSocket(0)` 端口，掉线自愈 |
| `desktop/device/src/main/resources/device/labyrinth-probe.js` | 只读探针脚本：运行时校准 `Il2CppClass.name` 偏移、ART 线程上下文包装、`DialogArea` 活动弹窗计数、回连桥 |
| `desktop/app/.../app/probe/LabyrinthProbeFastProcessor.kt` | 装饰器：探针命中 → 短路整帧模板匹配；熔断 30 s；其余回落视觉 |
| `desktop/app/.../app/DesktopAppGraph.kt` | `ensureMemoryProbe()` / `closeMemoryProbe()` 接线到 `fastProcessorFactory`；任务停止与断开设备时清理 |
| `desktop/device/src/probe/.../device/LiveMemoryProbe.kt` | 手动真机直验任务（显式 opt-in），部署并核对页面快照 |
| `desktop/device/build.gradle.kts` | `probeMemory` JavaExec 任务（`-PprobeAdb`/`-PprobeSerial`/`-PprobeOutput`） |

### 关键技术点

- **frida-inject 而非 frida-server**：免宿主 frida 客户端库；脚本在模拟器内回连 `127.0.0.1:<port>`，经 `adb reverse` 转发到桌面监听口。
- **Il2CppClass.name 偏移运行时校准**：不同 il2cpp 版本类布局不同（实测某国服客户端 name 不在 0x8），用 `il2cpp_class_get_name` 的 API 结果在 klass 头部 0x00..0x40 扫描匹配，校准失败则只回 viewId、走视觉降级。
- **ART 线程上下文**：`runtime_invoke` 前通过 `Java.perform` / `thread_attach` 补齐 ART 线程，避免 GC stop-the-world 时主线程死等。
- **弹窗闸门**：`DialogArea` 活动弹窗数 ≠ 0（含未知 -1）时一律不短路，保证编队/商店/事件等弹窗页不会被底层视图误判。

### 当前视图映射（保守）

| 视图类名 | 页面状态 |
| --- | --- |
| `ViewBattle` | `BATTLE_IN_PROGRESS` |
| `ViewLabyrinthMap` | `NODE_MAP_VIEW` |
| `ViewHome` | `HOME` |

未知类名或有活动弹窗时返回 `null`，交视觉处理，最坏情况等同原有路径。

### 真机验证

在 MuMu 模拟器（x86_64，已 root，游戏运行中，全程只读）上验证：

- 全链路通：部署 → 注入 → 回连 → `Ready(gamePid, mb1, x86_64)`
- 页面快照正确：`view=ViewHome, viewId=5, dialogCount=0`
- `ViewManager` 字段偏移：`currentView@0x110`、`<CurrentViewId>k__BackingField@0x120`
- 探针掉线自动重连，游戏无卡顿

单元测试 `LabyrinthProbeFastProcessorTest` 5 项通过（映射、短路、降级、熔断）。

## 七、预期功能与效果

- **降低高频页识别开销**：主页、地图、战斗中页跳过整帧模板匹配，减少识别延迟与 CPU 占用。
- **不误判弹窗页**：编队、商店、事件等弹窗仍走视觉细判定。
- **零侵入降级**：frida-inject 未放置、SHA 不符、模拟器未 root 或探针失败时，行为等同原有截图识别路径，不影响正常使用。

## 八、后续工作

1. **扩展视图映射覆盖**：增加 `DAWN_REALM_HOME_*`、`BATTLE_FAILED`、`TITLE` 等页面；ACTIVE/IDLE 歧义页需额外字段或保守跳过。
2. **弹窗名直判**：`DialogArea` 子节点 `GameObject` 名已知（如 `DialogLabyrinthShop`、`DialogLabyrinthSelection`），可直接映射商店/遗物选择等高频页，避免走视觉。
3. **labStatus 全量快照**：移植所有者 PC 版的 `LabyrinthTempData` 快照（持有角色、地图面板、商店货架、选择内容），替换三选一卡面与商店货架的图像识别，进一步提速。
4. **可选**：设置页开关（当前靠二进制在位启用，无 UI）；frida-server 作为补充方案。
