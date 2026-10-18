package com.yuyan.imemodule.voice

/**
 * 花花：离线语音识别 JNI（libhuahuaasr.so，仅 arm64-v8a）。
 * 识别算法移植自 Xime（kingzcheung/xime，GPL-3）的自研流式 zipformer2，
 * 参考 sherpa-onnx（Apache-2.0），特征提取 kaldi-native-fbank（Apache-2.0）。
 */
object AsrNative {
    /** 原生库能否加载（非 arm64 设备或缺少 libonnxruntime.so 时为 false） */
    val available: Boolean = try {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("huahuaasr")
        true
    } catch (e: Throwable) {
        false
    }

    external fun nativeCreate(encoder: String, decoder: String, joiner: String, tokens: String): Long
    external fun nativeReset(handle: Long)
    external fun nativeAcceptPcm(handle: Long, pcm: ByteArray)
    external fun nativeGetPartial(handle: Long): String
    external fun nativeFinalize(handle: Long): String
    external fun nativeRelease(handle: Long)

    /** 解压模型包（.tar.bz2/.tar.gz/.tar）。fd 所有权交给原生层。返回 "" 成功，"ZIP" 表示是 zip 包，其余为错误信息 */
    external fun nativeExtractModel(fd: Int, outDir: String): String
}
