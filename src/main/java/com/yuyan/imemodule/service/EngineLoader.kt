package com.yuyan.imemodule.service

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.inputmethod.RimeEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 花花：引擎后台加载。
 *
 * 冷启动时 librime 初始化（部署检查 + 打开词库 + 语法模型）要几秒。以前主线程弹键盘时会去切方案，
 * 卡在同一把锁上等引擎，键盘就弹不出来。现在：
 *  - 引擎没好之前，主线程不碰引擎：切方案只记下来，按键先排队；
 *  - 引擎在后台线程加载，好了以后回到主线程应用最后的方案、补上排队的按键、刷新候选；
 *  - 加载完再把词库文件顺序读一遍，放进系统缓存，第一次打字不再卡磁盘。
 * 以上所有“ready / 队列 / 待切方案”的读写都在主线程，后台线程只做加载。
 */
object EngineLoader {
    private val main = Handler(Looper.getMainLooper())
    private val doneLatch = CountDownLatch(1)

    /** 引擎可用（只在主线程改） */
    @Volatile
    var ready = false
        private set

    /** 加载期间主线程想切到的方案 */
    @Volatile
    var pendingSchema: String? = null

    private val pendingKeys = ArrayList<KeyEvent>()
    private val readyListeners = ArrayList<() -> Unit>()

    val hasPendingKeys get() = pendingKeys.isNotEmpty()

    fun queueKey(event: KeyEvent) {
        if (pendingKeys.size < 64) pendingKeys.add(KeyEvent(event))
    }

    fun popKey(): Boolean = if (pendingKeys.isEmpty()) false else { pendingKeys.removeAt(pendingKeys.size - 1); true }

    fun clearKeys() = pendingKeys.clear()

    /** 主线程注册：引擎好了以后要做的事（如刷新候选栏） */
    fun addReadyListener(l: () -> Unit) {
        readyListeners.add(l)
    }

    fun currentSchemaGuess(): String =
        pendingSchema ?: AppPrefs.getInstance().internal.pinyinModeRime.getValue()

    /** 等引擎加载完成（开机预热广播用，避免进程被提前回收） */
    fun awaitReady(timeoutMs: Long): Boolean = doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * 花花：换了语法模型等文件后，在当前进程里重新加载引擎（不再杀进程，设置页不会闪退）。
     * 主线程先把 ready 置 false（期间按键排队、不碰引擎），再在后台线程重载；好了回调 [done]。
     */
    fun reloadAsync(done: (() -> Unit)? = null) {
        main.post {
            ready = false
            if (done != null) {
                lateinit var once: () -> Unit
                once = { readyListeners.remove(once); done() }
                readyListeners.add(once)
            }
            Thread({ loadInBackground() }, "huahua-reload").start()
        }
    }

    /** 在后台线程调用 */
    private val loadLock = Any()

    fun loadInBackground() = synchronized(loadLock) { loadInBackgroundLocked() }

    private fun loadInBackgroundLocked() {
        try {
            RimeEngine.destroy()
            var applied = currentSchemaGuess()
            RimeEngine.selectSchema(applied)
            applyOptions()
            // 加载期间又切了方案：后台先切好，主线程就不用再等
            val p = pendingSchema
            if (p != null && p != applied) {
                RimeEngine.selectSchema(p); applyOptions(); applied = p
            }
        } catch (t: Throwable) {
            android.util.Log.e("Huahua", "engine load failed", t)
        }
        main.post { onLoadedMainThread() }
        warmFileCache()
    }

    private fun onLoadedMainThread() {
        val p = pendingSchema
        pendingSchema = null
        ready = true
        if (p != null && p != RimeEngine.getCurrentRimeSchema()) InputDispatcher.initImeSchema(p)
        if (pendingKeys.isNotEmpty()) {
            val keys = ArrayList(pendingKeys)
            pendingKeys.clear()
            keys.forEach { InputDispatcher.inputKeyCode(it) }
        }
        ArrayList(readyListeners).forEach { runCatching { it() } }
        doneLatch.countDown()
    }

    private fun applyOptions() {
        val input = AppPrefs.getInstance().input
        RimeEngine.setImeOption("traditionalization", input.chineseFanTi.getValue())
        RimeEngine.setImeOption("emoji", input.emojiInput.getValue())
    }

    @Volatile private var lastWarm = 0L
    private val warmRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 花花：键盘每次弹出时（最多 30 秒一次）在后台把语法模型和主词库的页“摸”一遍。
     * 切到别的应用一阵后系统会把 39MB 的 bgw 语法模型和 68MB 的词库页从缓存里挤掉，
     * 回来打第一个字时 librime 在主线程上缺页读盘，一卡就是两三百毫秒（主机实测：缓存冷时
     * 头几个键 100/58/249ms、最慢 495ms；缓存热时 0.3ms）。这里用 mmap+load 只碰每页一个字节，
     * 页本来就在缓存里时几毫秒就完；不复制数据，不改引擎，不影响候选。
     */
    fun rewarmAsync() {
        if (!ready) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastWarm < 30_000) return
        if (!warmRunning.compareAndSet(false, true)) return
        lastWarm = now
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                touchHotFiles()
            } finally { warmRunning.set(false) }
        }, "huahua-rewarm").start()
    }

    private fun touchHotFiles() {
        runCatching {
            val root = File(CustomConstant.RIME_DICT_PATH)
            val schema = currentSchemaGuess()
            val hot = root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".gram") } +
                listOf(File(root, "build/pinyin.table.bin"), File(root, "build/$schema.prism.bin"))
            for (f in hot) {
                if (!f.isFile || f.length() <= 0) continue
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, f.length()).load()
                }
            }
        }
    }

    /** 把词库、语法模型顺序读一遍（librime 用 mmap，读过的页留在系统缓存里） */
    private fun warmFileCache() {
        runCatching {
            val root = File(CustomConstant.RIME_DICT_PATH)
            val files = (File(root, "build").listFiles().orEmpty().toList() + root.listFiles().orEmpty().toList())
                .filter { it.isFile && (it.name.endsWith(".bin") || it.name.endsWith(".gram")) }
            val buf = ByteArray(1 shl 16)
            var budget = 300L shl 20
            for (f in files) {
                if (budget <= 0) break
                f.inputStream().use { s -> while (true) { val n = s.read(buf); if (n <= 0) break; budget -= n } }
            }
            lastWarm = android.os.SystemClock.uptimeMillis()
        }
    }
}
