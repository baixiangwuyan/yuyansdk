package com.yuyan.imemodule.voice

import android.content.Context
import android.net.Uri
import com.yuyan.imemodule.application.Launcher
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 花花：离线语音模型的导入、识别和删除。
 *
 * 模型放在 外部私有目录/models/asr/（"models/" 已在用户数据导出里排除，不进备份）。
 * 导入的就是浏览器下载下来的那个 .tar.bz2 压缩包（也接受 .tar.gz / .tar / .zip），
 * 只解出 encoder/decoder/joiner 的 .onnx 和 tokens.txt，目录结构拍平。
 */
object VoiceModelManager {
    private const val INFO_FILE = "model_id.txt"

    private val modelsDir: File
        get() = File(Launcher.instance.context.getExternalFilesDir(null), "models")
    val modelDir: File get() = File(modelsDir, "asr")

    data class ModelFiles(val encoder: File, val decoder: File, val joiner: File, val tokens: File)

    /** 当前已导入的模型文件；不齐全时为 null */
    fun findModelFiles(dir: File = modelDir): ModelFiles? {
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: return null
        fun pick(role: String, preferInt8: Boolean): File? {
            val c = files.filter { it.name.startsWith(role) && it.name.endsWith(".onnx") }
            if (c.isEmpty()) return null
            val int8 = c.filter { it.name.contains(".int8.") }
            val fp = c - int8.toSet()
            return if (preferInt8) (int8.firstOrNull() ?: fp.first()) else (fp.firstOrNull() ?: int8.first())
        }
        // 与 Xime 适配一致：encoder/joiner 用 int8，decoder 用浮点版
        val enc = pick("encoder", true) ?: return null
        val dec = pick("decoder", false) ?: return null
        val joi = pick("joiner", true) ?: return null
        val tok = files.find { it.name == "tokens.txt" } ?: return null
        return ModelFiles(enc, dec, joi, tok)
    }

    fun isReady(): Boolean = AsrNative.available && findModelFiles() != null

    /** 已导入模型对应的适配（识别不出时 null，显示为“自定义模型”） */
    fun currentProfile(): AsrModelProfile? =
        AsrModelRegistry.findById(runCatching { File(modelDir, INFO_FILE).readText().trim() }.getOrNull())

    fun currentModelName(): String? {
        if (findModelFiles() == null) return null
        return currentProfile()?.name ?: "自定义模型"
    }

    fun modelSizeBytes(): Long = modelDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun delete() {
        VoiceRecognizer.releaseModel()
        modelDir.deleteRecursively()
        File(modelsDir, "asr.tmp").deleteRecursively()
    }

    /**
     * 导入模型压缩包。成功返回 null，失败返回给用户看的错误信息。
     * 在 IO 线程调用。
     */
    fun import(context: Context, uri: Uri, fileName: String?): String? {
        if (!AsrNative.available) return "当前设备不支持离线语音（需要 64 位 arm64 手机）"
        val tmp = File(modelsDir, "asr.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) return "无法创建模型目录"
        val free = modelsDir.usableSpace
        if (free in 1 until 400L * 1024 * 1024) {
            tmp.deleteRecursively()
            return "手机剩余空间不足（至少需要约 400MB：解压后模型约 170MB）"
        }
        val err = try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return "无法打开文件"
            val r = AsrNative.nativeExtractModel(pfd.detachFd(), tmp.absolutePath)
            if (r == "ZIP") extractZip(context, uri, tmp) else r.ifEmpty { null }
        } catch (e: Exception) {
            "导入失败：${e.message}"
        }
        if (err != null) {
            tmp.deleteRecursively()
            return err
        }
        val files = findModelFiles(tmp)
        if (files == null) {
            tmp.deleteRecursively()
            return "压缩包里缺少模型文件（需要 encoder / decoder / joiner 三个 .onnx 和 tokens.txt）"
        }
        // 只保留用得到的 4 个文件，省空间
        val keep = setOf(files.encoder.name, files.decoder.name, files.joiner.name, files.tokens.name)
        tmp.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        detectProfile(fileName, files)?.let { File(tmp, INFO_FILE).writeText(it.id) }

        VoiceRecognizer.releaseModel()
        modelDir.deleteRecursively()
        if (!tmp.renameTo(modelDir)) {
            tmp.copyRecursively(modelDir, overwrite = true)
            tmp.deleteRecursively()
        }
        return null
    }

    private fun extractZip(context: Context, uri: Uri, out: File): String? {
        var count = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val base = e.name.substringAfterLast('/').substringAfterLast('\\')
                    val wanted = base == "tokens.txt" || (base.endsWith(".onnx") &&
                        (base.startsWith("encoder") || base.startsWith("decoder") || base.startsWith("joiner")))
                    if (!wanted || base.contains("..")) continue
                    File(out, base).outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
        } ?: return "无法打开文件"
        return if (count == 0) "压缩包里没有找到语音模型文件" else null
    }

    /** 按文件名识别是哪个模型，识别不出再看词表特征 */
    private fun detectProfile(fileName: String?, files: ModelFiles): AsrModelProfile? {
        val n = fileName?.lowercase().orEmpty()
        if (n.contains("x-asr")) return XAsr480msZhEnPunctInt8Profile
        if (n.contains("zipformer-zh-int8")) return ZipformerZhInt8Profile
        return runCatching {
            val text = files.tokens.readText()
            when {
                text.contains("\u2581，") || text.contains("\u2581,") -> XAsr480msZhEnPunctInt8Profile
                text.lines().count { it.isNotBlank() } in 1900..2100 -> ZipformerZhInt8Profile
                else -> null
            }
        }.getOrNull()
    }
}
