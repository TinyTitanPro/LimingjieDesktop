package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode

/**
 * 节点会话状态
 */
data class NodeSessionState(
    val currentArea: Int = 1,
    val currentNodeId: Long? = null,
    val visitedNodes: List<Long> = emptyList(),
    val isComplete: Boolean = false,
)

/**
 * 节点调试日志回调。默认 no-op，后续接入 android.util.Log。
 */
fun interface NodeDebugLogger {
    fun log(message: String)
}

/**
 * 默认的日志回调占位，便于调用方按引用判断是否不需要输出日志。
 */
object NoOpNodeDebugLogger : NodeDebugLogger {
    override fun log(message: String) = Unit
}

/**
 * 黎明界节点会话
 * 管理节点匹配和操作的完整流程
 */
class LabyrinthNodeSession(
    private val matcher: LabyrinthNodeMatcher = LabyrinthNodeMatcher(),
    private val planner: LabyrinthNodeActionPlanner = LabyrinthNodeActionPlanner(),
    private val debugLogger: NodeDebugLogger = NoOpNodeDebugLogger,
) {
    private var state = NodeSessionState()
    private var allMapNodes: List<LabyrinthMapNode> = emptyList()
    private var routeNodes: List<LabyrinthMapNode> = emptyList()
    private var templates: NodeTemplateSet = NodeTemplateSet(emptyMap())
    private var lastMatchResult: NodeMatchResult? = null
    private var lastClassifications: List<NodeClassification> = emptyList()

    /**
     * 逐格路线顾问：给定当前格、本区完整地图与已踏破格，返回下一格；返回 null 表示沿用
     * 预计算路线。仅在该格尚未咨询过时调用一次，避免每帧阻塞。
     */
    private var routeAdvisor: ((currentBlockId: Long, areaNodes: List<LabyrinthMapNode>, visited: Set<Long>) -> Long?)? = null
    private var advisedForNodeId: Long? = null

    /** 外部决策接入点；null 恢复为完全按预计算路线行进。 */
    fun setRouteAdvisor(
        advisor: ((currentBlockId: Long, areaNodes: List<LabyrinthMapNode>, visited: Set<Long>) -> Long?)?,
    ) {
        routeAdvisor = advisor
        advisedForNodeId = null
    }

    /**
     * 询问路线顾问并就地重算剩余路线。
     *
     * 只接受当前格真实存在的后继；偏离原路线时，用地图 nextBlockIds 的 BFS 结果替换剩余
     * 路线（仍以本区终点/首领为终点），从而让匹配、目标绑定与区域推进都继续有效。
     */
    private fun applyRouteAdvisor() {
        val advisor = routeAdvisor ?: return
        val currentId = state.currentNodeId ?: return
        if (advisedForNodeId == currentId) return
        advisedForNodeId = currentId
        val current = allMapNodes.firstOrNull { it.blockId == currentId } ?: return
        if (current.area != state.currentArea) return
        val areaNodes = allMapNodes.filter { it.area == current.area }
        val choice = advisor(currentId, areaNodes, state.visitedNodes.toSet()) ?: return
        if (choice == currentId || choice !in current.nextBlockIds) return
        val target = areaNodes.firstOrNull { it.blockId == choice } ?: return
        if (target.area != current.area) return
        val currentIndex = routeNodes.indexOfFirst { it.blockId == currentId }
        if (currentIndex >= 0 && routeNodes.getOrNull(currentIndex + 1)?.blockId == choice) return
        val tail = searchPathToAreaEnd(areaNodes, target.blockId) ?: return
        val prefix = if (currentIndex >= 0) {
            routeNodes.take(currentIndex + 1)
        } else {
            routeNodes.takeWhile { it.area < current.area } + current
        }
        routeNodes = (prefix + tail).distinctBy(LabyrinthMapNode::blockId)
        debugLogger.log("route-advisor current=$currentId choice=$choice path=${tail.joinToString { it.blockId.toString() }}")
    }

    /** 从 [fromId] 沿 nextBlockIds 找一条到本区终点/首领的路径；优先保持协议给出的顺序。 */
    private fun searchPathToAreaEnd(areaNodes: List<LabyrinthMapNode>, fromId: Long): List<LabyrinthMapNode>? {
        val byId = areaNodes.associateBy(LabyrinthMapNode::blockId)
        val start = byId[fromId] ?: return null
        fun isEnd(node: LabyrinthMapNode): Boolean =
            node.isAreaLastPoint || node.blockType == LabyrinthNodeTypes.BOSS ||
                node.nextBlockIds.none { byId.containsKey(it) }
        if (isEnd(start)) return listOf(start)
        val previous = linkedMapOf<Long, Long>()
        val queue = ArrayDeque<Long>().apply { add(fromId) }
        val seen = mutableSetOf(fromId)
        var goal: Long? = null
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val node = byId[id] ?: continue
            if (id != fromId && isEnd(node)) {
                goal = id
                break
            }
            node.nextBlockIds.forEach { next ->
                if (byId.containsKey(next) && seen.add(next)) {
                    previous[next] = id
                    queue.add(next)
                }
            }
        }
        val end = goal ?: return null
        val path = mutableListOf<LabyrinthMapNode>()
        var cursor: Long? = end
        while (cursor != null && cursor != fromId) {
            byId[cursor]?.let(path::add)
            cursor = previous[cursor]
        }
        if (cursor != fromId) return null
        path.add(start)
        return path.asReversed()
    }

    /**
     * 初始化会话
     */
    fun initialize(
        allNodes: List<LabyrinthMapNode>,
        route: List<LabyrinthMapNode>,
        nodeTemplates: NodeTemplateSet,
        currentNodeId: Long? = null,
    ) {
        allMapNodes = allNodes
        routeNodes = route
        templates = nodeTemplates
        val currentNode = currentNodeId?.let { id ->
            requireNotNull(allNodes.firstOrNull { it.blockId == id }) { "当前节点不在完整地图中" }
        } ?: route.firstOrNull { it.blockType == LabyrinthNodeTypes.START }
        val currentRouteIndex = currentNode?.let { node ->
            route.indexOfFirst { it.blockId == node.blockId }.also { index ->
                require(index >= 0) { "当前节点不在选定路线中" }
            }
        }
        val visitedNodes = currentRouteIndex
            ?.let { index -> route.take(index + 1).map(LabyrinthMapNode::blockId) }
            .orEmpty()
        // Some saved route payloads omit isAreaLastPoint on the final node of an area. The
        // selected route order is still authoritative: if the next route node belongs to a
        // later area, resume from that area's START node instead of trying to map the next
        // area's target against the old area's columns.
        val nextRouteNode = currentRouteIndex
            ?.let { index -> route.getOrNull(index + 1) }
        val nextAreaFromRoute = currentNode?.area?.let { currentArea ->
            nextRouteNode?.takeIf { node -> node.area > currentArea }?.area
        }
        val nextArea = nextAreaFromRoute ?: currentNode
            ?.takeIf(LabyrinthMapNode::isAreaLastPoint)
            ?.let { node -> route.asSequence().map(LabyrinthMapNode::area).filter { it > node.area }.minOrNull() }
        val nextAreaStart = nextArea?.let { area -> route.firstOrNull { it.area == area } }
        state = when {
            currentNode != null &&
                nextArea == null &&
                (currentNode.isAreaLastPoint || currentRouteIndex == route.lastIndex) -> NodeSessionState(
                currentArea = currentNode.area,
                currentNodeId = currentNode.blockId,
                visitedNodes = visitedNodes,
                isComplete = true,
            )

            nextArea != null -> NodeSessionState(
                currentArea = nextArea,
                currentNodeId = nextAreaStart?.blockId,
                visitedNodes = visitedNodes + listOfNotNull(nextAreaStart?.blockId),
                isComplete = false,
            )

            else -> NodeSessionState(
                currentArea = currentNode?.area ?: route.firstOrNull()?.area ?: 1,
                currentNodeId = currentNode?.blockId,
                visitedNodes = visitedNodes,
                isComplete = false,
            )
        }
        lastMatchResult = null
        lastClassifications = emptyList()
        advisedForNodeId = null
    }

    /**
     * 处理当前帧，返回操作建议
     */
    fun processFrame(frame: PixelImage): NodeAction {
        if (state.isComplete) {
            return NodeAction.Complete
        }
        applyRouteAdvisor()

        val matchResult = matcher.match(
            frame = frame,
            allMapNodes = allMapNodes,
            routeNodes = routeNodes,
            currentArea = state.currentArea,
            currentNodeId = state.currentNodeId,
            templates = templates,
        )
        lastMatchResult = matchResult

        logNodeDebugInfo(matchResult)

        return planner.planAction(matchResult)
    }

    /**
     * 用入口帧处理器已产出的节点分类结果推进会话，避免重复模板匹配。
     */
    fun processClassifications(
        classifications: List<NodeClassification>,
    ): NodeAction {
        if (state.isComplete) {
            return NodeAction.Complete
        }
        applyRouteAdvisor()

        lastClassifications = classifications
        val matchResult = matcher.matchClassified(
            classifications = classifications,
            allMapNodes = allMapNodes,
            routeNodes = routeNodes,
            currentArea = state.currentArea,
            currentNodeId = state.currentNodeId,
        )
        lastMatchResult = matchResult

        logNodeDebugInfo(matchResult)

        return planner.planAction(matchResult)
    }

    /**
     * 输出节点校准日志：内部编号（blockId / nodeNumber）与视觉行号（visualIndexFromTop）。
     */
    private fun logNodeDebugInfo(matchResult: NodeMatchResult) {
        if (debugLogger === NoOpNodeDebugLogger) return
        if (matchResult.topologyObservedColumns.isNotEmpty()) {
            debugLogger.log(
                "topology alignment=${"%.3f".format(matchResult.topologyAlignmentScore)} " +
                    "columns=" + matchResult.topologyObservedColumns.joinToString(";") { column ->
                    "v${column.visualColumn}@${column.centerX}[${column.nodeCount}]" +
                        "->c${column.mappedLogicalColumn ?: "?"}"
                },
            )
        }
        matchResult.topologyMappings.forEach { topology ->
            debugLogger.log(
                "topology-map blockId=${topology.blockId} " +
                    "logicalColumn=${topology.logicalColumn} " +
                    "visual=${topology.visualColumn}/${topology.visualRow} " +
                    "expected=${LabyrinthNodeTypes.labelOf(topology.expectedBlockType)} " +
                    "detected=${LabyrinthNodeTypes.labelOf(topology.detectedBlockType)} " +
                    "topologyConfidence=${"%.3f".format(topology.topologyConfidence)} " +
                    "visualConfidence=${"%.3f".format(topology.visualConfidence)} " +
                    "clickable=${topology.isClickable} binding=${topology.bindingKind} " +
                    "rect=${topology.screenRect}",
            )
        }
        if (lastClassifications.isNotEmpty()) {
            lastClassifications.forEach { classification ->
                debugLogger.log(
                    "classification visual=${classification.column}/${classification.row} " +
                        "type=${LabyrinthNodeTypes.labelOf(classification.blockType)} " +
                        "confidence=${"%.3f".format(classification.confidence)} " +
                        "clickable=${classification.isClickable} " +
                        "cyan=${"%.3f".format(classification.cyanGlowScore)}/" +
                        "${"%.3f".format(classification.cyanGlowRowCoverage)} " +
                        "purple=${"%.3f".format(classification.purpleGlowScore)}/" +
                        "${"%.3f".format(classification.purpleGlowRowCoverage)} " +
                        "purpleSide=${"%.3f".format(classification.purpleGlowSideScore)} " +
                        "purpleLower=${"%.3f".format(classification.purpleGlowLowerScore)} " +
                        "rect=${classification.screenRect}",
                )
            }
        }
        matchResult.visibleNodes.forEach { mapping ->
            val topologySource = matchResult.topologyMappings.firstOrNull { topology ->
                topology.blockId == mapping.blockId && topology.screenRect == mapping.screenRect
            }
            if (
                mapping.protocolPromoted &&
                topologySource != null &&
                !topologySource.isClickable &&
                topologySource.bindingKind == NodeTopologyBindingKind.FULL_COLUMN
            ) {
                debugLogger.log(
                    "topology-route-override blockId=${mapping.blockId} " +
                        "visualClickable=false protocolReachable=true " +
                        "topologyConfidence=${"%.3f".format(mapping.topologyConfidence ?: mapping.confidence)} " +
                    "rect=${mapping.screenRect}",
                )
            }
            if (mapping.routeSemanticRepair) {
                debugLogger.log(
                    "route-semantic-repair blockId=${mapping.blockId} " +
                        "type=${LabyrinthNodeTypes.labelOf(mapping.blockType)} " +
                        "confidence=${"%.3f".format(mapping.confidence)} " +
                        "rect=${mapping.screenRect}",
                )
            }
            debugLogger.log(
                "mapping blockId=${mapping.blockId} " +
                    "type=${LabyrinthNodeTypes.labelOf(mapping.blockType)} " +
                    "column=${mapping.column} row=${mapping.row} " +
                    "confidence=${"%.3f".format(mapping.confidence)} " +
                    "clickable=${mapping.isClickable} " +
                    "source=${when {
                        mapping.routeSemanticRepair -> "semantic-repair"
                        mapping.topologyBindingKind != null -> "topology"
                        else -> "legacy"
                    }} " +
                    "binding=${mapping.topologyBindingKind ?: "none"} " +
                    "protocolPromoted=${mapping.protocolPromoted} " +
                    "routeSemanticRepair=${mapping.routeSemanticRepair} " +
                    "rect=${mapping.screenRect}",
            )
            allMapNodes.firstOrNull { it.blockId == mapping.blockId }?.let { node ->
                debugLogger.log(formatNodeDebugLog(node, nodesInColumnOf(node)))
            }
        }
        matchResult.currentNodeId?.let { currentId ->
            val currentNode = allMapNodes.firstOrNull { it.blockId == currentId } ?: return@let
            val nodesInColumn = nodesInColumnOf(currentNode)
            val expectedVisualRow = resolveVisualIndexFromTop(currentId.toInt(), nodesInColumn) + 1
            matchResult.topologyMappings
                .filter { it.blockId == currentId }
                .forEach { topology ->
                    if (topology.visualRow != expectedVisualRow) {
                        debugLogger.log(
                            "node-order-conflict current=$currentId logicalColumn=${currentNode.column} " +
                                "expectedVisualRow=$expectedVisualRow observedVisualRow=${topology.visualRow} " +
                                "binding=${topology.bindingKind} confidence=" +
                                "${"%.3f".format(topology.topologyConfidence)} rect=${topology.screenRect}",
                        )
                    }
                }
        }
        matchResult.conflicts.forEach { conflict ->
            debugLogger.log(
                "conflict reason=${conflict.reason} blockId=${conflict.blockId} " +
                    "expected=${LabyrinthNodeTypes.labelOf(conflict.expectedBlockType)} " +
                    "detected=${LabyrinthNodeTypes.labelOf(conflict.detectedBlockType)} " +
                    "visual=${conflict.visualColumn}/${conflict.visualRow} " +
                    "bindingOrClassificationConfidence=${"%.3f".format(conflict.confidence)} " +
                    "sourceClickable=${conflict.sourceClickable} " +
                    "rect=${conflict.screenRect}",
            )
        }
        matchResult.nextNode?.let { node ->
            val reachable = allMapNodes.firstOrNull { it.blockId == matchResult.currentNodeId }
                ?.nextBlockIds
                .orEmpty()
            debugLogger.log(
                "route-target blockId=${node.blockId} " +
                    "type=${LabyrinthNodeTypes.labelOf(node.blockType)} " +
                    "current=${matchResult.currentNodeId} " +
                    "currentType=${LabyrinthNodeTypes.labelOf(matchResult.currentNodeType)} " +
                    "visibleTarget=${matchResult.visibleNodes.any { it.blockId == node.blockId }} " +
                    "typeConflict=${matchResult.conflicts.any { it.blockId == node.blockId }} " +
                    "reachable=${reachable.joinToString(prefix = "[", postfix = "]")} " +
                    "columnOffset=${matchResult.logicalColumnOffset}",
            )
            debugLogger.log(formatNodeDebugLog(node, nodesInColumnOf(node)))
        }
    }

    private fun nodesInColumnOf(node: LabyrinthMapNode): Int {
        val counts = countNodesByColumn(allMapNodes)
        return counts[NodeColumnKey(node.area, node.column)] ?: 1
    }

    /**
     * 记录点击操作
     */
    fun afterClick(blockId: Long) {
        val (newNodeId, _) = matcher.afterClick(
            clickedBlockId = blockId,
            allMapNodes = allMapNodes,
            currentArea = state.currentArea,
        )

        state = state.copy(
            currentNodeId = newNodeId,
            visitedNodes = state.visitedNodes + blockId,
        )

        // 检查是否完成当前区域
        val currentNode = allMapNodes.firstOrNull { it.blockId == newNodeId }
        val clickedRouteIndex = routeNodes.indexOfFirst { it.blockId == newNodeId }
        val nextRouteNode = clickedRouteIndex
            .takeIf { it >= 0 }
            ?.let { index -> routeNodes.getOrNull(index + 1) }
        val nextAreaFromRoute = nextRouteNode
            ?.takeIf { node -> node.area > state.currentArea }
            ?.area
        if (currentNode?.isAreaLastPoint == true || nextAreaFromRoute != null) {
            // 检查是否还有下一个区域；路线顺序优先于可能缺失的协议标记。
            val nextArea = nextAreaFromRoute ?: state.currentArea + 1
            val hasNextArea = allMapNodes.any { it.area == nextArea }
            if (hasNextArea) {
                val nextAreaStart = routeNodes.firstOrNull { it.area == nextArea }
                state = state.copy(
                    currentArea = nextArea,
                    currentNodeId = nextAreaStart?.blockId,
                    visitedNodes = state.visitedNodes + listOfNotNull(nextAreaStart?.blockId),
                )
            } else {
                state = state.copy(isComplete = true)
            }
        }
    }

    /**
     * 获取当前状态
     */
    fun getState(): NodeSessionState = state

    /** Route-proven next node, available before the first visual map pass of a viewport. */
    fun currentRouteTarget(): LabyrinthMapNode? {
        if (state.isComplete) return null
        val currentId = state.currentNodeId ?: return null
        val currentIndex = routeNodes.indexOfFirst { it.blockId == currentId }
        if (currentIndex < 0) return null
        return routeNodes.getOrNull(currentIndex + 1)
            ?.takeIf { it.area == state.currentArea }
    }

    /**
     * Keep enough semantic variety around the target column for topology mapping while avoiding
     * templates that cannot possibly occur near the route target.
     */
    fun searchTypesNearTarget(target: LabyrinthMapNode): Set<Int> {
        val columns = (target.column - 1).coerceAtLeast(1)..(target.column + 1)
        return allMapNodes.asSequence()
            .filter { it.area == target.area && it.column in columns }
            .map(LabyrinthMapNode::blockType)
            .plus(target.blockType)
            .toSet()
    }

    /**
     * Returns the route-proven final Boss that may be clicked without visual Boss classification.
     *
     * Safety constraints are intentionally strict:
     * - only areas 3 and 5 use this shortcut;
     * - the next route node must be a Boss in the current area;
     * - it must be exactly one logical column after the current route node;
     * - the route must leave the area immediately afterwards (or end after area 5).
     *
     * This avoids turning a generic Boss-looking screen region into a route decision.
     */
    fun directFinalBossTarget(): LabyrinthMapNode? {
        if (state.currentArea !in DIRECT_FINAL_BOSS_AREAS) return null
        val currentId = state.currentNodeId ?: return null
        val currentIndex = routeNodes.indexOfFirst { it.blockId == currentId }
        if (currentIndex < 0) return null
        val currentNode = routeNodes[currentIndex]
        if (currentNode.area != state.currentArea) return null
        val next = routeNodes.getOrNull(currentIndex + 1) ?: return null
        if (next.area != state.currentArea || next.blockType != LabyrinthNodeTypes.BOSS) return null
        if (next.column != currentNode.column + 1) return null
        val afterBoss = routeNodes.getOrNull(currentIndex + 2)
        if (afterBoss == null && next.area != 5) return null
        if (afterBoss != null && afterBoss.area != next.area + 1) return null
        return next
    }

    /**
     * Recovers the semantic route target for an already-open movement-confirmation dialog.
     *
     * This is intentionally stricter than the normal route planner: recovery is allowed only
     * when the protocol graph says the current node has exactly one reachable successor and that
     * successor is also the next node on the saved route.  In that situation an orphaned/manual
     * movement dialog cannot refer to a different selectable branch, so the session may safely
     * rebuild the lost pending transition without using stale screen coordinates.
     */
    fun uniqueReachableRouteTarget(): LabyrinthMapNode? {
        val currentId = state.currentNodeId ?: return null
        val current = allMapNodes.firstOrNull { it.blockId == currentId } ?: return null
        if (current.area != state.currentArea) return null
        val routeIndex = routeNodes.indexOfFirst { it.blockId == currentId }
        if (routeIndex < 0) return null
        val next = routeNodes.getOrNull(routeIndex + 1) ?: return null
        if (next.area != state.currentArea) return null
        val reachable = current.nextBlockIds.distinct()
        if (reachable.size != 1 || reachable.single() != next.blockId) return null
        return next
    }

    /** Last raw-to-route mapping pass, retained only for diagnostics/overlay rendering. */
    fun getLastMatchResult(): NodeMatchResult? = lastMatchResult

    /** A search hit is the exact route target accepted by the ordinary click planner. */
    fun matchedRouteTargetId(): Long? {
        val target = currentRouteTarget() ?: return null
        val match = lastMatchResult ?: return null
        if (match.nextNode?.blockId != target.blockId) return null
        return (planner.planAction(match) as? NodeAction.ClickNode)
            ?.takeIf { it.blockId == target.blockId && it.screenRect != null }?.blockId
    }

    /** All raw visual candidates from the most recent externally classified frame. */
    fun getLastClassifications(): List<NodeClassification> = lastClassifications

    private companion object {
        val DIRECT_FINAL_BOSS_AREAS = setOf(3, 5)
    }
}
