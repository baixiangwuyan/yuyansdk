package com.yuyan.imemodule.voice

import android.content.Context
import android.net.Uri
import com.yuyan.imemodule.application.Launcher
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 花花：离线语音模型的导入、选择和删除。可以同时保留多个模型。
 *
 * 每个模型一个目录：外部私有目录/models/asr/<模型id>/（"models/" 已在用户数据导出里排除，不进备份），
 * 当前使用哪个记在 models/asr/active.txt。
 * 导入的就是浏览器下载下来的那个 .tar.bz2 压缩包（也接受 .tar.gz / .tar / .zip），
 * 只解出 .onnx 和 tokens.txt，目录结构拍平。按文件自动区分：
 * encoder/decoder/joiner → 流式 transducer；只有 model*.onnx → Dolphin CTC。
 * 同一个模型再导入一次会覆盖它自己，不影响其他模型。
 */
object VoiceModelManager {
    private const val INFO_FILE = "model_id.txt"
    private const val NAME_FILE = "source_name.txt"
    private const val ACTIVE_FILE = "active.txt"

    private val modelsDir: File
        get() = File(Launcher.instance.context.getExternalFilesDir(null), "models")
    private val rootDir: File get() = File(modelsDir, "asr")

    data class ModelFiles(
        val kind: AsrModelKind,
        val tokens: File,
        val encoder: File? = null,
        val decoder: File? = null,
        val joiner: File? = null,
        val model: File? = null,
    ) {
        val names: Set<String> get() = listOfNotNull(tokens, encoder, decoder, joiner, model).map { it.name }.toSet()
    }

    data class InstalledModel(
        val id: String,
        val dir: File,
        val profile: AsrModelProfile?,
        val files: ModelFiles,
        val sourceName: String?,
    ) {
        val kind: AsrModelKind get() = files.kind
        val name: String get() = profile?.name ?: ("自定义模型" + (sourceName?.let { "（$it）" } ?: ""))
        val shortName: String get() = profile?.shortName ?: "自定义"
        val sizeBytes: Long get() = dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** 目录里的模型文件；不齐全时为 null */
    fun findModelFiles(dir: File): ModelFiles? {
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: return null
        val tok = files.find { it.name == "tokens.txt" } ?: return null
        fun pick(role: String, preferInt8: Boolean): File? {
            val c = files.filter { it.name.startsWith(role) && it.name.endsWith(".onnx") }
            if (c.isEmpty()) return null
            val int8 = c.filter { it.name.contains(".int8.") }
            val fp = c - int8.toSet()
            return if (preferInt8) (int8.firstOrNull() ?: fp.first()) else (fp.firstOrNull() ?: int8.first())
        }
        // 与 Xime 适配一致：encoder/joiner 用 int8，decoder 用浮点版
        val enc = pick("encoder", true)
        val dec = pick("decoder", false)
        val joi = pick("joiner", true)
        if (enc != null && dec != null && joi != null) {
            return ModelFiles(AsrModelKind.TRANSDUCER, tok, encoder = enc, decoder = dec, joiner = joi)
        }
        val model = pick("model", true) ?: return null
        return ModelFiles(AsrModelKind.CTC_DOLPHIN, tok, model = model)
    }

    /** 旧版（只能放一个模型）把文件直接放在 models/asr/ 下：搬进子目录 */
    @Synchronized
    private fun migrateLegacy() {
        val root = rootDir
        val loose = root.listFiles()?.filter { it.isFile && it.name != ACTIVE_FILE } ?: return
        if (loose.isEmpty()) return
        val files = findModelFiles(root)
        if (files == null) {                     // 残缺的旧文件，直接清掉
            loose.forEach { it.delete() }
            return
        }
        val id = runCatching { File(root, INFO_FILE).readText().trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: "custom"
        val dest = File(root, safeId(id))
        dest.mkdirs()
        loose.forEach { f ->
            val target = File(dest, f.name)
            if (!f.renameTo(target)) { f.copyTo(target, overwrite = true); f.delete() }
        }
        if (!File(dest, INFO_FILE).exists()) File(dest, INFO_FILE).writeText(id)
        if (!File(root, ACTIVE_FILE).exists()) File(root, ACTIVE_FILE).writeText(dest.name)
    }

    private fun safeId(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(60).ifEmpty { "custom" }

    /** 已导入的模型（按推荐顺序） */
    fun listModels(): List<InstalledModel> {
        if (!rootDir.isDirectory) return emptyList()
        migrateLegacy()
        val dirs = rootDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val list = dirs.mapNotNull { d ->
            val files = findModelFiles(d) ?: return@mapNotNull null
            val pid = runCatching { File(d, INFO_FILE).readText().trim() }.getOrNull()
            val src = runCatching { File(d, NAME_FILE).readText().trim() }.getOrNull()?.ifEmpty { null }
            InstalledModel(d.name, d, AsrModelRegistry.findById(pid), files, src)
        }
        val order = AsrModelRegistry.profiles
        return list.sortedWith(compareBy({ m -> order.indexOf(m.profile).let { if (it < 0) 99 else it } }, { it.id }))
    }

    /** 当前使用的模型：设置里选的；没选过或已删除时用第一个 */
    fun activeModel(): InstalledModel? {
        val list = listModels()
        if (list.isEmpty()) return null
        val id = runCatching { File(rootDir, ACTIVE_FILE).readText().trim() }.getOrNull()
        return list.find { it.id == id } ?: list.first()
    }

    fun setActive(id: String) {
        if (activeModel()?.id == id) return
        rootDir.mkdirs()
        File(rootDir, ACTIVE_FILE).writeText(id)
        VoiceRecognizer.releaseModel()
    }

    /** 切到下一个已导入的模型（语音面板上的快捷切换）；返回切换后的模型 */
    fun switchToNext(): InstalledModel? {
        val list = listModels()
        if (list.size < 2) return activeModel()
        val cur = activeModel()
        val next = list[(list.indexOfFirst { it.id == cur?.id } + 1) % list.size]
        setActive(next.id)
        return next
    }

    fun isReady(): Boolean = AsrNative.available && activeModel() != null

    fun currentModelName(): String? = activeModel()?.name

    fun delete(id: String) {
        if (activeModel()?.id == id) VoiceRecognizer.releaseModel()
        File(rootDir, id).deleteRecursively()
        val act = File(rootDir, ACTIVE_FILE)
        if (runCatching { act.readText().trim() }.getOrNull() == id) act.delete()
    }

    /**
     * 导入模型压缩包，导入后设为当前模型。成功返回 null，失败返回给用户看的错误信息。
     * 在 IO 线程调用。
     */
    fun import(context: Context, uri: Uri, fileName: String?): String? {
        if (!AsrNative.available) return "当前设备不支持离线语音（需要 64 位 arm64 手机）"
        migrateLegacy()
        val tmp = File(modelsDir, "asr.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) return "无法创建模型目录"
        val free = modelsDir.usableSpace
        if (free in 1 until 400L * 1024 * 1024) {
            tmp.deleteRecursively()
            return "手机剩余空间不足（至少需要约 400MB：解压后模型约 100~170MB）"
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
            return "压缩包里缺少模型文件（需要 encoder / decoder / joiner 三个 .onnx，或 Dolphin 的 model.onnx，以及 tokens.txt）"
        }
        // 只保留用得到的文件，省空间
        val keep = files.names
        tmp.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        val profile = detectProfile(fileName, files)
        val id = profile?.id ?: safeId("custom-" + (fileName?.substringBefore('.') ?: "model"))
        profile?.let { File(tmp, INFO_FILE).writeText(it.id) }
        fileName?.let { File(tmp, NAME_FILE).writeText(it) }

        VoiceRecognizer.releaseModel()
        rootDir.mkdirs()
        val dest = File(rootDir, id)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) {
            tmp.copyRecursively(dest, overwrite = true)
            tmp.deleteRecursively()
        }
        File(rootDir, ACTIVE_FILE).writeText(id)
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
                        (base.startsWith("encoder") || base.startsWith("decoder") ||
                            base.startsWith("joiner") || base.startsWith("model")))
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
        if (files.kind == AsrModelKind.CTC_DOLPHIN) {
            if (n.contains("dolphin")) return DolphinBaseCtcInt8Profile
            return runCatching {
                if (files.tokens.readText().contains("<nospeech>")) DolphinBaseCtcInt8Profile else null
            }.getOrNull()
        }
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
