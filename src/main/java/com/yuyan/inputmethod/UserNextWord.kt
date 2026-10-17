package com.yuyan.inputmethod

import com.yuyan.imemodule.application.Launcher
import java.io.File
import kotlin.concurrent.thread

/**
 * 用户“下一词表”：记住你打完 A 之后常接什么词，越用越准。
 * 不带任何词库，不加依赖；数据存在 files/user_next_word.txt，通常只有几十 KB。
 *
 * 原理：每次联想时拿到光标前的文字，和上一次对比，多出来的那段就是你刚打的词，
 *      记为  上一次结尾的字 -> 新词  的次数。联想时按次数排在最前面。
 */
object UserNextWord {
    private const val FILE_NAME = "user_next_word.txt"
    private const val MAX_KEYS = 3000          // 最多记多少个“前词”
    private const val MAX_NEXT = 8             // 每个前词最多记几个后续词
    private const val MAX_WORD = 6             // 超过 6 个字的不当作词记

    private class Item(var count: Int, var time: Long)

    // key（前面 1~2 个汉字）-> (后续词 -> 次数/时间)；LinkedHashMap 按访问顺序，便于淘汰最久没用的
    private val table = object : LinkedHashMap<String, LinkedHashMap<String, Item>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashMap<String, Item>>?) = size > MAX_KEYS
    }
    private var lastText = ""
    private var loaded = false
    private var changes = 0

    // 放在 external 目录，设置里的“导出/导入用户数据”会自动带上
    private val file: File get() = Launcher.instance.context.let { File(it.getExternalFilesDir(null) ?: it.filesDir, FILE_NAME) }

    private fun isHan(c: Char) = c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf'

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 4) {
                    table.getOrPut(p[0]) { LinkedHashMap() }[p[1]] = Item(p[2].toIntOrNull() ?: 1, p[3].toLongOrNull() ?: 0L)
                }
            }
        } catch (_: Exception) { }
    }

    /** 光标前文字末尾的 1 个、2 个汉字，作为查询键（先长后短） */
    private fun keysOf(text: String): List<String> {
        val han = text.takeLastWhile { isHan(it) }
        return buildList {
            if (han.length >= 2) add(han.takeLast(2))
            if (han.isNotEmpty()) add(han.takeLast(1))
        }
    }

    /**
     * 每次联想时调用，传入光标前的文字（InputView 里取的 10 个字）。
     * 先学习（和上一次比，多出来的就是新打的词），再返回推荐的后续词。
     */
    @Synchronized
    fun onText(text: String, limit: Int = 5): List<String> {
        ensureLoaded()
        learn(text)
        lastText = text
        val result = LinkedHashMap<String, Int>()
        for (key in keysOf(text)) {
            val nexts = table[key] ?: continue
            nexts.entries.sortedWith(compareByDescending<Map.Entry<String, Item>> { it.value.count }.thenByDescending { it.value.time })
                .forEach { if (result.size < limit) result.putIfAbsent(it.key, it.value.count) }
        }
        return result.keys.toList()
    }

    private fun learn(text: String) {
        val prev = lastText
        if (prev.isEmpty() || text.length <= 1) return
        // 光标前文字是滑动窗口（最多 10 个字），找上一次文字的尾巴在这次开头的最长重叠
        var added: String? = null
        for (i in 0 until prev.length) {
            val tail = prev.substring(i)
            if (tail.length < 2) break
            if (text.startsWith(tail) && text.length > tail.length) {
                added = text.substring(tail.length)
                break
            }
        }
        added ?: return
        if (added.length > MAX_WORD || !added.all { isHan(it) }) return
        val now = System.currentTimeMillis()
        for (key in keysOf(prev)) {
            val nexts = table.getOrPut(key) { LinkedHashMap() }
            val item = nexts[added]
            if (item != null) { item.count++; item.time = now }
            else {
                if (nexts.size >= MAX_NEXT) {
                    // 挤掉次数最少、最久没用的那个
                    nexts.minWithOrNull(compareBy<Map.Entry<String, Item>> { it.value.count }.thenBy { it.value.time })?.let { nexts.remove(it.key) }
                }
                nexts[added] = Item(1, now)
            }
        }
        if (++changes >= 20) save()
    }

    /** 输入法收起时调用，写盘放后台线程 */
    @Synchronized
    fun save(sync: Boolean = false) {
        if (!loaded || changes == 0) return
        changes = 0
        val text = buildString {
            for ((key, nexts) in table) for ((word, item) in nexts) {
                append(key).append('\t').append(word).append('\t').append(item.count).append('\t').append(item.time).append('\n')
            }
        }
        val write = { try { file.writeText(text) } catch (_: Exception) { } }
        if (sync) write() else thread { write() }
    }

    /** 导入数据后调用，下次联想时重新读文件 */
    @Synchronized
    fun reload() { table.clear(); lastText = ""; changes = 0; loaded = false }

    /** 换输入框时调用，避免把两个输入框的文字接在一起学 */
    @Synchronized
    fun resetContext() { lastText = "" }

    /** 设置里“清除联想记录”可调用 */
    @Synchronized
    fun clear() { table.clear(); lastText = ""; changes = 0; thread { file.delete() } }
}
