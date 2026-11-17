// 移植自 CyIME（github.com/Cyletix/CyIME，GPL-3.0，tag 1.15.94，ui/keyboard/GapLightNet.kt）。
// 花花：去掉语音音量条相关部分，其余几何与最短路算法原样保留。
package com.yuyan.imemodule.keyboard.effect

import kotlin.math.abs
import kotlin.math.min

/**
 * 键缝网（键缝动效用）。只做几何和最短路，不碰界面，电脑上的单元测试直接跑。
 *
 * 每个键外面一圈（键帽往外半条缝）就是它四周的键缝中线，相邻的键共用一条边。
 * 把所有的边在交点处切开得到一张图；按下一个键时从它那一圈出发，沿这张图算到每个点的最短路程，
 * 光线照这个路程往四面八方走，走到 [GapNet.routes] 给的范围为止。
 */

/** 键缝网里的一段：两头的位置，和两头各自离出发键的最短路程（像素）。 */
internal class GapRoute(
    val ax: Float, val ay: Float, val bx: Float, val by: Float,
    val da: Float, val db: Float, val length: Float,
) {
    /** 从两头走来的光在这一段上碰头的位置（从 a 头量）；只有一头有光时就是整段或 0。 */
    val meet: Float get() = ((db - da + length) / 2f).coerceIn(0f, length)
}

internal class GapNet private constructor(
    private val nodeX: FloatArray,
    private val nodeY: FloatArray,
    private val nodeColumn: IntArray,
    private val nodeRow: IntArray,
    private val edgeA: IntArray,
    private val edgeB: IntArray,
    private val edgeLength: FloatArray,
    /** 每个节点连着的边号。 */
    private val links: Array<IntArray>,
    /** 每个键四个数：左、上、右、下所在的列号和行号；没排出来的键是 -1。 */
    private val cells: IntArray,
    private val columns: FloatArray,
    private val rows: FloatArray,
    /** 一个键的宽高里常见的较小值；范围和线段长度按它算，26 键和九键看起来差不多远。 */
    val unit: Float,
) {
    val keyCount: Int get() = cells.size / 4
    val nodeCount: Int get() = nodeX.size
    val edgeCount: Int get() = edgeA.size

    fun has(key: Int): Boolean = key in 0 until keyCount && cells[key * 4] >= 0
    fun left(key: Int): Float = columns[cells[key * 4]]
    fun top(key: Int): Float = rows[cells[key * 4 + 1]]
    fun right(key: Int): Float = columns[cells[key * 4 + 2]]
    fun bottom(key: Int): Float = rows[cells[key * 4 + 3]]

    /**
     * 从第 [key] 个键的一圈出发，路程不到 [reach] 的那些键缝段。键自己那一圈不在里面（它单独画成一个框）。
     * 节点不过一两百个，直接用最朴素的找最小值，一次按键算一遍。
     */
    fun routes(key: Int, reach: Float): List<GapRoute> {
        if (!has(key) || reach <= 0f) return emptyList()
        val c0 = cells[key * 4]; val r0 = cells[key * 4 + 1]; val c1 = cells[key * 4 + 2]; val r1 = cells[key * 4 + 3]
        val count = nodeCount
        val distance = FloatArray(count) { Float.POSITIVE_INFINITY }
        val done = BooleanArray(count)
        for (i in 0 until count) {
            val c = nodeColumn[i]; val r = nodeRow[i]
            if ((c in c0..c1 && (r == r0 || r == r1)) || (r in r0..r1 && (c == c0 || c == c1))) distance[i] = 0f
        }
        while (true) {
            var next = -1
            for (i in 0 until count) if (!done[i] && distance[i] <= reach && (next < 0 || distance[i] < distance[next])) next = i
            if (next < 0) break
            done[next] = true
            for (edge in links[next]) {
                val other = if (edgeA[edge] == next) edgeB[edge] else edgeA[edge]
                val through = distance[next] + edgeLength[edge]
                if (through < distance[other]) distance[other] = through
            }
        }
        val result = ArrayList<GapRoute>()
        for (edge in edgeA.indices) {
            val a = edgeA[edge]; val b = edgeB[edge]
            if (min(distance[a], distance[b]) < reach && (distance[a] > 0f || distance[b] > 0f)) {
                result += GapRoute(nodeX[a], nodeY[a], nodeX[b], nodeY[b], distance[a], distance[b], edgeLength[edge])
            }
        }
        return result
    }

    companion object {
        /**
         * [frames] 每个键四个数（左、上、右、下），是键帽往外扩了半条缝的那一圈。
         * 相邻两个键算出来的公共边可能差一两个像素（各自取整、个别键内边距不同），
         * 差距不超过 [toleranceX]／[toleranceY] 的并成同一条。
         */
        fun build(frames: FloatArray, toleranceX: Float, toleranceY: Float): GapNet {
            val keys = frames.size / 4
            val xs = snap(FloatArray(keys * 2) { frames[(it / 2) * 4 + if (it % 2 == 0) 0 else 2] }, toleranceX)
            val ys = snap(FloatArray(keys * 2) { frames[(it / 2) * 4 + if (it % 2 == 0) 1 else 3] }, toleranceY)
            val columnCount = xs.values.size
            val rowCount = ys.values.size
            val cells = IntArray(keys * 4)
            val grid = IntArray(columnCount * rowCount) { -1 }
            val nodeColumn = ArrayList<Int>()
            val nodeRow = ArrayList<Int>()
            fun node(column: Int, row: Int) {
                if (grid[column * rowCount + row] < 0) {
                    grid[column * rowCount + row] = nodeColumn.size
                    nodeColumn += column; nodeRow += row
                }
            }
            val widths = ArrayList<Float>()
            val heights = ArrayList<Float>()
            for (key in 0 until keys) {
                val c0 = xs.index[key * 2]; val c1 = xs.index[key * 2 + 1]
                val r0 = ys.index[key * 2]; val r1 = ys.index[key * 2 + 1]
                if (c0 >= c1 || r0 >= r1) {   // 还没排出来或者宽高是 0 的键不进网
                    for (i in 0 until 4) cells[key * 4 + i] = -1
                    continue
                }
                cells[key * 4] = c0; cells[key * 4 + 1] = r0; cells[key * 4 + 2] = c1; cells[key * 4 + 3] = r1
                node(c0, r0); node(c1, r0); node(c0, r1); node(c1, r1)
                widths += xs.values[c1] - xs.values[c0]
                heights += ys.values[r1] - ys.values[r0]
            }
            val seen = HashSet<Long>()
            val edgeA = ArrayList<Int>()
            val edgeB = ArrayList<Int>()
            fun link(a: Int, b: Int) {
                val low = min(a, b); val high = if (a == low) b else a
                if (seen.add(low.toLong() * nodeColumn.size + high)) { edgeA += low; edgeB += high }
            }
            for (key in 0 until keys) {
                val c0 = cells[key * 4]
                if (c0 < 0) continue
                val r0 = cells[key * 4 + 1]; val c1 = cells[key * 4 + 2]; val r1 = cells[key * 4 + 3]
                for (row in intArrayOf(r0, r1)) {
                    var previous = -1
                    for (column in c0..c1) {
                        val here = grid[column * rowCount + row]
                        if (here < 0) continue
                        if (previous >= 0) link(previous, here)
                        previous = here
                    }
                }
                for (column in intArrayOf(c0, c1)) {
                    var previous = -1
                    for (row in r0..r1) {
                        val here = grid[column * rowCount + row]
                        if (here < 0) continue
                        if (previous >= 0) link(previous, here)
                        previous = here
                    }
                }
            }
            // 同一条横缝上隔着一段没有键的空当（分体键盘的中缝）：也连上，光能走过中缝到另一半（#381）。
            // 键边已经连过的相邻点不重复连；只连同一条横线上紧挨着的两个点，穿过键帽里面的（跨两排的高键）不连。
            fun insideKey(row: Int, from: Int, to: Int): Boolean = (0 until keys).any { key ->
                val c0 = cells[key * 4]
                c0 >= 0 && cells[key * 4 + 1] < row && row < cells[key * 4 + 3] && c0 < to && from < cells[key * 4 + 2]
            }
            for (row in 0 until rowCount) {
                var previous = -1
                var previousColumn = -1
                for (column in 0 until columnCount) {
                    val here = grid[column * rowCount + row]
                    if (here < 0) continue
                    if (previous >= 0 && !insideKey(row, previousColumn, column)) link(previous, here)
                    previous = here
                    previousColumn = column
                }
            }
            val nodes = nodeColumn.size
            val nodeX = FloatArray(nodes) { xs.values[nodeColumn[it]] }
            val nodeY = FloatArray(nodes) { ys.values[nodeRow[it]] }
            val lengths = FloatArray(edgeA.size) {
                abs(nodeX[edgeA[it]] - nodeX[edgeB[it]]) + abs(nodeY[edgeA[it]] - nodeY[edgeB[it]])
            }
            val linked = Array(nodes) { ArrayList<Int>(4) }
            for (edge in edgeA.indices) { linked[edgeA[edge]] += edge; linked[edgeB[edge]] += edge }
            return GapNet(
                nodeX, nodeY, nodeColumn.toIntArray(), nodeRow.toIntArray(),
                edgeA.toIntArray(), edgeB.toIntArray(), lengths, Array(nodes) { linked[it].toIntArray() },
                cells, xs.values, ys.values, min(median(widths), median(heights)),
            )
        }

        private class Snapped(val values: FloatArray, val index: IntArray)

        /** 从小到大排，相邻差距不超过 [tolerance] 的归成一组，组里取平均；返回各组的值和每个数所在的组号。 */
        private fun snap(raw: FloatArray, tolerance: Float): Snapped {
            val order = raw.indices.sortedBy { raw[it] }
            val index = IntArray(raw.size)
            val values = ArrayList<Float>()
            var sum = 0f; var size = 0; var last = 0f
            for (i in order) {
                if (size > 0 && raw[i] - last > tolerance) { values += sum / size; sum = 0f; size = 0 }
                index[i] = values.size
                sum += raw[i]; size++; last = raw[i]
            }
            if (size > 0) values += sum / size
            return Snapped(values.toFloatArray(), index)
        }

        private fun median(values: List<Float>): Float = if (values.isEmpty()) 0f else values.sorted()[values.size / 2]
    }
}

/** 动效的进度曲线：出发快、到头慢。 */
internal fun gapLightEase(progress: Float): Float {
    val rest = 1f - progress.coerceIn(0f, 1f)
    return 1f - rest * rest
}

/** 到 [hold] 之前一直不透明，之后很快收掉：边界是切出来的，不是慢慢糊掉的。 */
internal fun gapLightCut(progress: Float, hold: Float): Float =
    if (progress < hold) 1f else (1f - (progress - hold) / (1f - hold)).coerceIn(0f, 1f)

/** 拖尾分几级，每级是实色、边界清楚；不用渐变是为了每帧不新建渐变对象。 */
internal const val GAP_LIGHT_TAIL_STEPS = 5

/** 按键、删除一次动效的时长（速度为 1 时）。 */
internal const val GAP_LIGHT_PRESS_MS = 200
