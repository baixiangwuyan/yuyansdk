package com.yuyan.imemodule.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * 花花：录音 + 流式离线识别（单例，全部在一个后台线程上串行执行）。
 *
 * - 模型在第一次说话时加载（约 1~2 秒），之后常驻；语音面板关闭 2 分钟后自动卸载，
 *   避免输入法进程长期多占 ~200MB 内存。
 * - 只在输入法进程内运行，不联网。
 */
object VoiceRecognizer {
    enum class State { LOADING, LISTENING, IDLE, ERROR }

    interface Listener {
        fun onState(state: State, message: String? = null)
        fun onPartial(text: String)
        fun onFinal(text: String)
    }

    private const val SAMPLE_RATE = 16000
    private const val CHUNK_SAMPLES = 1600            // 100ms
    private const val MAX_RECORD_MS = 60_000L          // 单次最长 60 秒
    private const val IDLE_RELEASE_MS = 120_000L

    private val thread = HandlerThread("huahua-asr").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var handle = 0L
    @Volatile private var recording = false
    @Volatile private var cancelled = false
    @Volatile private var listener: Listener? = null

    val isRecording: Boolean get() = recording

    private val releaseRunnable = Runnable { releaseModel() }

    /** 开始一次识别。调用前需确认已有录音权限、模型已导入。 */
    fun start(l: Listener) {
        if (recording) return
        listener = l
        main.removeCallbacks(releaseRunnable)
        recording = true
        cancelled = false
        worker.post { runSession() }
    }

    /** 说完了：停止录音，出最终结果 */
    fun stop() { recording = false }

    /** 放弃本次（不上屏） */
    fun cancel() {
        cancelled = true
        recording = false
    }

    /** 面板关闭时调用：停止录音，稍后卸载模型 */
    fun onPanelHidden() {
        cancel()
        listener = null
        main.removeCallbacks(releaseRunnable)
        main.postDelayed(releaseRunnable, IDLE_RELEASE_MS)
    }

    fun releaseModel() {
        main.removeCallbacks(releaseRunnable)
        cancel()
        worker.post {
            if (handle != 0L) {
                AsrNative.nativeRelease(handle)
                handle = 0L
            }
        }
    }

    private fun post(block: (Listener) -> Unit) {
        main.post { listener?.let(block) }
    }

    private fun ensureModel(): String? {
        if (handle != 0L) return null
        if (!AsrNative.available) return "当前设备不支持离线语音（需要 64 位 arm64 手机）"
        val files = VoiceModelManager.findModelFiles() ?: return "还没有导入语音模型"
        post { it.onState(State.LOADING) }
        val h = try {
            AsrNative.nativeCreate(files.encoder.absolutePath, files.decoder.absolutePath,
                files.joiner.absolutePath, files.tokens.absolutePath)
        } catch (e: Throwable) { 0L }
        if (h == 0L) return "语音模型加载失败，请在设置里重新导入"
        handle = h
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, CHUNK_SAMPLES * 2 * 4)
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (_: Exception) { }
        }
        return null
    }

    private fun runSession() {
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
        AsrNative.nativeReset(handle)
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            recorder.release()
            recording = false
            post { it.onState(State.ERROR, "无法开始录音，请检查麦克风权限") }
            return
        }
        post { it.onState(State.LISTENING) }
        val buf = ByteArray(CHUNK_SAMPLES * 2)
        val startAt = System.currentTimeMillis()
        var lastPartial = ""
        while (recording) {
            val n = recorder.read(buf, 0, buf.size)
            if (n < 0) break
            if (n > 0) {
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
        if (cancelled) {
            AsrNative.nativeReset(handle)
            post { it.onState(State.IDLE) }
            return
        }
        val text = AsrNative.nativeFinalize(handle).trim()
        post {
            it.onFinal(text)
            it.onState(State.IDLE)
        }
    }
}
