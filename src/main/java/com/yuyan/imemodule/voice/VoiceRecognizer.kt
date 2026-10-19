package com.yuyan.imemodule.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * 花花：录音 + 离线识别（单例）。
 *
 * - 流式模型（X-ASR / Zipformer）：录音线程边录边识别，实时出字，结束时整句上屏。
 * - Dolphin（非流式 CTC）：录音线程只录音并做简单能量 VAD，停顿约 0.8 秒就把这一句交给
 *   识别线程整体识别并上屏；点结束/松开时把最后一句也识别掉。这样长段说话一句一句上屏，不用大缓冲。
 * - 模型在第一次说话时加载（约 1~2 秒），之后常驻；语音面板关闭 30 秒后自动卸载，
 *   并结束两个后台线程，避免输入法进程长期多占内存和电。切换模型时卸载旧的。
 * - 省电：连续约 10 秒没声音自动停麦；面板隐藏（含收起键盘）立刻停麦；识别只用 2 个线程且不自旋。
 * - 只在输入法进程内运行，不联网。
 */
object VoiceRecognizer {
    enum class State { LOADING, LISTENING, DECODING, IDLE, ERROR }

    interface Listener {
        fun onState(state: State, message: String? = null)
        fun onPartial(text: String)
        fun onFinal(text: String)
    }

    private const val SAMPLE_RATE = 16000
    private const val CHUNK_SAMPLES = 1600            // 100ms
    private const val MAX_RECORD_MS = 60_000L          // 流式：单次最长 60 秒
    private const val MAX_RECORD_MS_SEGMENTED = 300_000L  // Dolphin 分句：单次最长 5 分钟
    private const val IDLE_RELEASE_MS = 30_000L
    private const val AUTO_STOP_SILENCE_CHUNKS = 100   // 连续 10 秒没声音自动停麦

    // Dolphin 分句 VAD（每块 100ms）
    private const val VAD_SILENCE_CHUNKS = 8           // 停顿 0.8 秒断句
    private const val VAD_MIN_SPEECH_CHUNKS = 3        // 少于 0.3 秒的响声当噪音丢掉
    private const val VAD_PREROLL_CHUNKS = 3           // 句首多留 0.3 秒
    private const val VAD_TAIL_CHUNKS = 3              // 句尾留 0.3 秒静音
    private const val VAD_MAX_SEGMENT_CHUNKS = 200     // 一句最长 20 秒，超了强制断开
    private const val VAD_MIN_THRESHOLD = 180.0        // int16 RMS 门限下限

    // 录音线程和识别线程：用时才建，模型卸载时一起结束
    private var thread: HandlerThread? = null
    private var decodeThread: HandlerThread? = null
    private lateinit var worker: Handler
    private lateinit var decoder: Handler
    private val main = Handler(Looper.getMainLooper())
    private val nativeLock = Any()

    @Volatile private var handle = 0L
    @Volatile private var loadedKind: AsrModelKind? = null
    @Volatile private var loadedId: String? = null
    @Volatile private var recording = false
    @Volatile private var session = AtomicBoolean(false)   // 本次是否已取消
    @Volatile private var listener: Listener? = null
    private val pendingDecodes = AtomicInteger(0)

    val isRecording: Boolean get() = recording

    private val releaseRunnable = Runnable { releaseModel() }

    private var releaseSeq = 0
    private const val AUTO_STOP_MSG = "约 10 秒没听到声音，已自动停止"

    @Synchronized
    private fun ensureThreads() {
        releaseSeq++                       // 作废还没执行的“结束线程”
        if (thread == null) {
            thread = HandlerThread("huahua-asr").apply { start() }.also { worker = Handler(it.looper) }
            decodeThread = HandlerThread("huahua-asr-decode").apply { start() }.also { decoder = Handler(it.looper) }
        }
    }

    /** 开始一次识别。调用前需确认已有录音权限、模型已导入。 */
    fun start(l: Listener) {
        if (recording) return
        listener = l
        main.removeCallbacks(releaseRunnable)
        ensureThreads()
        pendingDecodes.set(0)
        recording = true
        val s = AtomicBoolean(false)
        session = s
        worker.post { runSession(s) }
    }

    /** 说完了：停止录音，出最终结果 */
    fun stop() { recording = false }

    /** 放弃本次（不上屏） */
    fun cancel() {
        session.set(true)
        recording = false
    }

    /** 面板关闭时调用：停止录音，稍后卸载模型 */
    fun onPanelHidden() {
        cancel()
        listener = null
        main.removeCallbacks(releaseRunnable)
        main.postDelayed(releaseRunnable, IDLE_RELEASE_MS)
    }

    @Synchronized
    fun releaseModel() {
        main.removeCallbacks(releaseRunnable)
        cancel()
        val t = thread ?: return
        val seq = ++releaseSeq
        // 在录音线程上排队：等本次录音退出循环后卸载模型（会等识别线程手上的那句识别完），
        // 期间没有新的说话就结束两个线程。新的说话也排在同一线程上，不会和卸载交错。
        worker.post {
            releaseNative()
            synchronized(this) {
                if (seq == releaseSeq && thread === t) {
                    decodeThread?.quitSafely()
                    t.quitSafely()
                    thread = null
                    decodeThread = null
                }
            }
        }
    }

    private fun releaseNative() {
        synchronized(nativeLock) {
            val h = handle
            if (h != 0L) {
                if (loadedKind == AsrModelKind.CTC_DOLPHIN) AsrNative.nativeDolphinRelease(h)
                else AsrNative.nativeRelease(h)
            }
            handle = 0L
            loadedKind = null
            loadedId = null
        }
    }

    private fun post(block: (Listener) -> Unit) {
        main.post { listener?.let(block) }
    }

    private fun ensureModel(): String? {
        if (!AsrNative.available) return "当前设备不支持离线语音（需要 64 位 arm64 手机）"
        val m = VoiceModelManager.activeModel() ?: return "还没有导入语音模型"
        if (handle != 0L && loadedId == m.id) return null
        releaseNative()
        post { it.onState(State.LOADING) }
        val f = m.files
        val h = try {
            if (f.kind == AsrModelKind.CTC_DOLPHIN) {
                AsrNative.nativeCreateDolphin(f.model!!.absolutePath, f.tokens.absolutePath)
            } else {
                AsrNative.nativeCreate(f.encoder!!.absolutePath, f.decoder!!.absolutePath,
                    f.joiner!!.absolutePath, f.tokens.absolutePath)
            }
        } catch (e: Throwable) { 0L }
        if (h == 0L) return "语音模型「${m.shortName}」加载失败，请在设置里重新导入"
        synchronized(nativeLock) {
            handle = h
            loadedKind = f.kind
            loadedId = m.id
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, CHUNK_SAMPLES * 2 * 8)
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (_: Exception) { }
        }
        return null
    }

    private fun runSession(cancelled: AtomicBoolean) {
        val err = ensureModel()
        if (err != null) {
            recording = false
            post { it.onState(State.ERROR, err) }
            return
        }
        if (!recording) {             // 加载期间用户已取消
            post { it.onState(State.IDLE) }
            return
        }
        val recorder = openRecorder()
        if (recorder == null) {
            recording = false
            post { it.onState(State.ERROR, "无法打开麦克风（可能被其他应用占用，或没有录音权限）") }
            return
        }
        val segmented = loadedKind == AsrModelKind.CTC_DOLPHIN
        if (!segmented) AsrNative.nativeReset(handle)
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            recorder.release()
            recording = false
            post { it.onState(State.ERROR, "无法开始录音，请检查麦克风权限") }
            return
        }
        post { it.onState(State.LISTENING) }
        if (segmented) recordSegmented(recorder, cancelled) else recordStreaming(recorder, cancelled)
    }

    // ---------------- 流式（X-ASR / Zipformer） ----------------

    private fun recordStreaming(recorder: AudioRecord, cancelled: AtomicBoolean) {
        val buf = ByteArray(CHUNK_SAMPLES * 2)
        val startAt = System.currentTimeMillis()
        var lastPartial = ""
        val vad = Vad()
        var autoStopped = false
        while (recording) {
            val n = recorder.read(buf, 0, buf.size)
            if (n < 0) break
            if (n > 0) {
                if (!vad.feedBytes(buf, n) && vad.silentChunks >= AUTO_STOP_SILENCE_CHUNKS) { autoStopped = true; break }
                AsrNative.nativeAcceptPcm(handle, if (n == buf.size) buf else buf.copyOf(n))
                val p = AsrNative.nativeGetPartial(handle)
                if (p != lastPartial) {
                    lastPartial = p
                    post { it.onPartial(p) }
                }
            }
            if (System.currentTimeMillis() - startAt > MAX_RECORD_MS) break
        }
        recording = false
        try { recorder.stop() } catch (_: Exception) { }
        recorder.release()
        if (cancelled.get()) {
            AsrNative.nativeReset(handle)
            post { it.onState(State.IDLE) }
            return
        }
        val text = AsrNative.nativeFinalize(handle).trim()
        post {
            if (text.isNotEmpty() || !autoStopped) it.onFinal(text)
            it.onState(State.IDLE, if (autoStopped) AUTO_STOP_MSG else null)
        }
    }

    // ---------------- Dolphin：录音 + 能量 VAD 分句，识别线程逐句识别 ----------------

    /** 简单能量 VAD：每块 100ms，底噪快降慢升，响度超过底噪 3 倍（且不低于下限）算有声 */
    private class Vad {
        private var noise = 100.0
        var silentChunks = 0
            private set

        fun feed(a: ShortArray, n: Int): Boolean {
            var sum = 0.0
            for (i in 0 until n) { val v = a[i].toDouble(); sum += v * v }
            return update(sqrt(sum / n))
        }

        fun feedBytes(b: ByteArray, n: Int): Boolean {
            var sum = 0.0
            val cnt = n / 2
            if (cnt == 0) return false
            for (i in 0 until cnt) {
                val v = ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort().toDouble()
                sum += v * v
            }
            return update(sqrt(sum / cnt))
        }

        private fun update(rms: Double): Boolean {
            noise = if (rms < noise) noise * 0.7 + rms * 0.3 else minOf(noise * 1.02 + 0.5, 3000.0)
            val voiced = rms > maxOf(VAD_MIN_THRESHOLD, noise * 3.0)
            silentChunks = if (voiced) 0 else silentChunks + 1
            return voiced
        }
    }

    /** 一次说话里的分句状态 */
    private class SegmentState {
        val committed = AtomicInteger(0)
    }

    private fun recordSegmented(recorder: AudioRecord, cancelled: AtomicBoolean) {
        val st = SegmentState()
        val chunk = ShortArray(CHUNK_SAMPLES)
        val preroll = ArrayDeque<ShortArray>()
        var seg = ShortArray(CHUNK_SAMPLES * 50)
        var segLen = 0
        var segChunks = 0
        var inSpeech = false
        var speechChunks = 0
        var silenceChunks = 0
        val vad = Vad()
        var autoStopped = false
        val startAt = System.currentTimeMillis()

        fun append(a: ShortArray, n: Int) {
            if (segLen + n > seg.size) seg = seg.copyOf(maxOf(seg.size * 2, segLen + n))
            System.arraycopy(a, 0, seg, segLen, n)
            segLen += n
            segChunks++
        }
        fun resetSeg() {
            segLen = 0; segChunks = 0; inSpeech = false; speechChunks = 0; silenceChunks = 0
            if (seg.size > CHUNK_SAMPLES * 50) seg = ShortArray(CHUNK_SAMPLES * 50)  // 别一直占着大缓冲
        }
        fun cut() {
            if (speechChunks >= VAD_MIN_SPEECH_CHUNKS) {
                // 句尾只留 0.3 秒静音
                val dropTail = (silenceChunks - VAD_TAIL_CHUNKS).coerceAtLeast(0) * CHUNK_SAMPLES
                val len = (segLen - dropTail).coerceAtLeast(0)
                submit(seg.copyOf(len), st, cancelled)
            }
            resetSeg()
        }

        while (recording) {
            var n = 0
            while (n < CHUNK_SAMPLES && recording) {     // 凑满 100ms 一块
                val r = recorder.read(chunk, n, CHUNK_SAMPLES - n)
                if (r < 0) { recording = false; break }
                n += r
            }
            if (n <= 0) break
            val voiced = vad.feed(chunk, n)
            if (!inSpeech && vad.silentChunks >= AUTO_STOP_SILENCE_CHUNKS) { autoStopped = true; break }  // 10 秒没声音，自动停麦

            if (!inSpeech) {
                if (voiced) {
                    inSpeech = true
                    for (p in preroll) append(p, p.size)
                    preroll.clear()
                    append(chunk, n)
                    speechChunks = 1
                    silenceChunks = 0
                } else {
                    preroll.addLast(chunk.copyOf(n))
                    while (preroll.size > VAD_PREROLL_CHUNKS) preroll.removeFirst()
                }
            } else {
                append(chunk, n)
                if (voiced) { speechChunks++; silenceChunks = 0 } else silenceChunks++
                if (silenceChunks >= VAD_SILENCE_CHUNKS || segChunks >= VAD_MAX_SEGMENT_CHUNKS) cut()
            }
            if (System.currentTimeMillis() - startAt > MAX_RECORD_MS_SEGMENTED) break
        }
        recording = false
        try { recorder.stop() } catch (_: Exception) { }
        recorder.release()
        if (inSpeech && !cancelled.get()) {
            // 点结束/松开：最后一句短一点也识别（按住说一个字的情况）
            if (speechChunks >= 1) speechChunks = maxOf(speechChunks, VAD_MIN_SPEECH_CHUNKS)
            cut()
        }
        if (pendingDecodes.get() > 0 && !cancelled.get()) post { it.onState(State.DECODING, "识别中…") }
        // 交给识别线程收尾：等前面的句子都识别完再回到空闲
        decoder.post {
            if (cancelled.get()) { post { it.onState(State.IDLE) }; return@post }
            post {
                if (st.committed.get() == 0 && !autoStopped) it.onFinal("")   // 显示“没有听清”
                it.onState(State.IDLE, if (autoStopped) AUTO_STOP_MSG else null)
            }
        }
    }

    private fun submit(pcm: ShortArray, st: SegmentState, cancelled: AtomicBoolean) {
        if (cancelled.get() || pcm.isEmpty()) return
        pendingDecodes.incrementAndGet()
        post { it.onState(State.DECODING, if (recording) "识别中…（可以接着说）" else "识别中…") }
        decoder.post {
            var text = ""
            try {
                if (!cancelled.get()) synchronized(nativeLock) {
                    val h = handle
                    if (h != 0L && loadedKind == AsrModelKind.CTC_DOLPHIN) {
                        text = AsrNative.nativeDolphinRecognize(h, pcm, pcm.size)
                    }
                }
            } catch (_: Throwable) { }
            val left = pendingDecodes.decrementAndGet()
            text = tidy(text)
            if (cancelled.get()) return@post
            if (text.isNotEmpty()) st.committed.incrementAndGet()
            post {
                if (text.isNotEmpty()) it.onFinal(text)
                if (left == 0 && recording) it.onState(State.LISTENING)
            }
        }
    }

    /** Dolphin 偶尔输出半角逗号/问号：跟在汉字后面的换成全角 */
    private fun tidy(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        val sb = StringBuilder(t.length)
        for (i in t.indices) {
            val c = t[i]
            val prevCjk = i > 0 && Character.UnicodeScript.of(t[i - 1].code) == Character.UnicodeScript.HAN
            sb.append(
                if (prevCjk) when (c) { ',' -> '，'; '?' -> '？'; '!' -> '！'; ';' -> '；'; ':' -> '：'; else -> c } else c
            )
        }
        return sb.toString()
    }
}
