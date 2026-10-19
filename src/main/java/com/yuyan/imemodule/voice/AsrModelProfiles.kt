package com.yuyan.imemodule.voice

/**
 * 花花：可导入的离线语音模型。
 * - 两个流式 transducer 模型照搬 Xime 的模型适配
 *   （xime/app/src/main/java/com/kingzcheung/xime/speech/models/ 下两个 Profile 文件，commit 585cf96），
 *   文件角色：encoder.int8.onnx / decoder.onnx / joiner.int8.onnx / tokens.txt。
 * - Dolphin（DataoceanAI，sherpa-onnx 转换）是非流式 CTC：model.int8.onnx / tokens.txt，
 *   说完一段才出字，支持普通话和陕西话等方言。
 */
enum class AsrModelKind { TRANSDUCER, CTC_DOLPHIN }

data class AsrModelProfile(
    val id: String,
    val name: String,
    /** 语音面板上显示的短名 */
    val shortName: String,
    val description: String,
    val language: String,
    val size: String,
    val downloadUrl: String,
    val kind: AsrModelKind = AsrModelKind.TRANSDUCER,
    /** “下载语音模型”列表里的标题；为空时用 name（size） */
    val listLabel: String? = null,
)

/** X-ASR-zh-en 流式模型（480ms chunk，int8，带标点）。花花推荐：中文为主、自动加标点 */
val XAsr480msZhEnPunctInt8Profile = AsrModelProfile(
    id = "x-asr-480ms-zh-en-punct-int8",
    name = "X-ASR 中英文语音识别（带标点）",
    shortName = "X-ASR 中英",
    description = "中英文混合识别，说中文、说英文都行，结果自动加标点，边说边出字，离线运行",
    language = "zh-en",
    size = "133.90MB",
    downloadUrl = "https://www.modelscope.cn/models/adaada88/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8/resolve/master/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
)

/** sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30：字级词表，中文识别，无标点 */
val ZipformerZhInt8Profile = AsrModelProfile(
    id = "zipformer-zh-int8",
    name = "中文 Zipformer int8",
    shortName = "中文 Zipformer",
    description = "Zipformer 架构，适合实时语音识别，int8 量化",
    language = "zh",
    size = "132.63MB",
    downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
)

/** sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02：普通话 + 22 种中国方言（含陕西话），非流式 */
val DolphinBaseCtcInt8Profile = AsrModelProfile(
    id = "dolphin-base-ctc-multi-lang-int8",
    name = "Dolphin 方言（含陕西话）",
    shortName = "Dolphin 方言",
    description = "普通话和陕西话等 22 种方言都能认，离线运行；说完一句（停顿约 1 秒或点结束）才出字",
    language = "zh-dialects",
    size = "80.67MB",
    downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02.tar.bz2",
    kind = AsrModelKind.CTC_DOLPHIN,
    listLabel = "Dolphin 方言（含陕西话，约81MB，说完才出字）",
)

object AsrModelRegistry {
    /** 首项为推荐模型 */
    val profiles: List<AsrModelProfile> =
        listOf(XAsr480msZhEnPunctInt8Profile, DolphinBaseCtcInt8Profile, ZipformerZhInt8Profile)
    fun findById(id: String?): AsrModelProfile? = profiles.find { it.id == id }
}
