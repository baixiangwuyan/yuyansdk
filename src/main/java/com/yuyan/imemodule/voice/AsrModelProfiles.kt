package com.yuyan.imemodule.voice

/**
 * 花花：可导入的离线语音模型，照搬 Xime 的模型适配
 * （xime/app/src/main/java/com/kingzcheung/xime/speech/models/ 下两个 Profile 文件，commit 585cf96）。
 * 两个模型的文件角色完全相同：encoder.int8.onnx / decoder.onnx / joiner.int8.onnx / tokens.txt。
 */
data class AsrModelProfile(
    val id: String,
    val name: String,
    val description: String,
    val language: String,
    val size: String,
    val downloadUrl: String,
    val encoderFile: String,
    val decoderFile: String,
    val joinerFile: String,
    val tokensFile: String = "tokens.txt",
)

/** X-ASR-zh-en 流式模型（480ms chunk，int8，带标点）。花花推荐：中文为主、自动加标点 */
val XAsr480msZhEnPunctInt8Profile = AsrModelProfile(
    id = "x-asr-480ms-zh-en-punct-int8",
    name = "X-ASR 中英文语音识别（带标点）",
    description = "中英文混合识别，说中文、说英文都行，结果自动加标点，边说边出字，离线运行",
    language = "zh-en",
    size = "133.90MB",
    downloadUrl = "https://www.modelscope.cn/models/adaada88/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8/resolve/master/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
    encoderFile = "encoder.int8.onnx",
    decoderFile = "decoder.onnx",
    joinerFile = "joiner.int8.onnx",
)

/** sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30：字级词表，中文识别，无标点 */
val ZipformerZhInt8Profile = AsrModelProfile(
    id = "zipformer-zh-int8",
    name = "中文 Zipformer int8",
    description = "Zipformer 架构，适合实时语音识别，int8 量化",
    language = "zh",
    size = "132.63MB",
    downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
    encoderFile = "encoder.int8.onnx",
    decoderFile = "decoder.onnx",
    joinerFile = "joiner.int8.onnx",
)

object AsrModelRegistry {
    /** 首项为推荐模型 */
    val profiles: List<AsrModelProfile> = listOf(XAsr480msZhEnPunctInt8Profile, ZipformerZhInt8Profile)
    fun findById(id: String?): AsrModelProfile? = profiles.find { it.id == id }
}
