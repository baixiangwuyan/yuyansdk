package com.yuyan.imemodule.database

import androidx.room.InvalidationTracker
import com.yuyan.imemodule.database.entry.SkbFun
import java.util.concurrent.Executors

/**
 * 花花：常用语（phrase）和候选栏工具栏（skbfun）的内存缓存。
 *
 * 以前每按一个字母都在主线程查一次 SQLite（phrase 表，看拼音是不是某条常用语的编码），
 * 候选清空时又在主线程查一次工具栏表。平时一两毫秒，但碰上剪贴板写库、WAL 落盘时会被锁住几十上百毫秒，
 * 打字就“偶尔一顿”。现在后台读进内存，按编码查 HashMap；表一改（Room 失效通知）就后台重读。
 * 缓存没好之前照旧查库，结果和以前完全一样（同样按 isKeep、time 倒序）。
 */
object PhraseCache {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "huahua-phrase-cache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    @Volatile private var byCode: Map<String, List<String>>? = null
    @Volatile private var barMenus: List<SkbFun>? = null
    @Volatile private var observing = false

    /** 后台加载（键盘弹出时调用；重复调用只会多读一次小表） */
    fun preload() {
        ensureObserver()
        if (!pending.compareAndSet(false, true)) return
        io.execute { pending.set(false); reloadPhrases(); reloadBarMenus() }
    }
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun ensureObserver() {
        if (observing) return
        synchronized(this) {
            if (observing) return
            observing = true
            runCatching {
                DataBaseKT.instance.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("phrase", "skbfun")) {
                    override fun onInvalidated(tables: Set<String>) {
                        if ("phrase" in tables) { byCode = null; io.execute { reloadPhrases() } }
                        if ("skbfun" in tables) { barMenus = null; io.execute { reloadBarMenus() } }
                    }
                })
            }.onFailure { observing = false }
        }
    }

    private fun reloadPhrases() {
        runCatching {
            val all = DataBaseKT.instance.phraseDao().getAll()   // 已按 isKeep DESC, time DESC 排好
            val map = HashMap<String, MutableList<String>>(all.size * 2 + 8)
            for (p in all) {
                // 同一条常用语三种编码可能相同，只加一次（和 SQL 的 OR 查询一致）
                for (code in linkedSetOf(p.qwerty, p.t9, p.lx17)) {
                    if (code.isNotEmpty()) map.getOrPut(code) { ArrayList(2) }.add(p.content)
                }
            }
            byCode = map
        }
    }

    private fun reloadBarMenus() {
        runCatching { barMenus = DataBaseKT.instance.skbFunDao().getALlBarMenu() }
    }

    /** 键盘弹出时调用：缓存还没有才去读 */
    fun preloadIfNeeded() { if (byCode == null || barMenus == null) preload() }

    /** 等同 phraseDao().query(code).map { it.content } */
    fun phrasesFor(code: String): List<String> {
        val m = byCode
        if (m != null) return m[code] ?: emptyList()
        preload()
        return DataBaseKT.instance.phraseDao().query(code).map { it.content }
    }

    /** 等同 skbFunDao().getALlBarMenu() */
    fun barMenus(): List<SkbFun> {
        barMenus?.let { return it }
        preload()
        return DataBaseKT.instance.skbFunDao().getALlBarMenu()
    }
}
