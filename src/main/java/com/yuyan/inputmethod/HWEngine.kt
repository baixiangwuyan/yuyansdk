package com.yuyan.inputmethod

import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.callback.IHandWritingCallBack
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinCaseType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinOutputFormat
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinToneType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinVCharType
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.HandWriting

object HWEngine {
    private var mHanyuPinyinOutputFormat: HanyuPinyinOutputFormat

    /** 手写数据是否已在手机上（导入用户数据后可能没复制过） */
    fun dataReady(): Boolean = java.io.File(
        com.yuyan.imemodule.application.CustomConstant.HW_DICT_PATH,
        "files/sogouhw/Chinese/model/Chinese_weights.data").exists()

    private var recopied = false   // 本次运行是否已强制重解压过
    private var diagOkWritten = false
    private var diagFailCount = 0

    /** 花花：强制覆盖重解压手写数据（导入别的手机数据可能带来不完整的文件夹） */
    private fun recopyData() {
        recopied = true
        runCatching {
            com.yuyan.imemodule.utils.AssetUtils.copyFileOrDir(Launcher.instance.context, "hw", "",
                com.yuyan.imemodule.application.CustomConstant.HW_DICT_PATH, true)
        }
        HandWriting.resetInit()
    }

    private fun tryInit(): Boolean {
        if (HandWriting.initialized) return true
        if (!dataReady()) return false
        if (!HandWriting.init(Launcher.instance.context)) return false
        HandWriting.setProperties()
        HandWriting.selectInputMode(5)
        return true
    }

    /** 花花：引擎没起来时再试；数据在却起不来就重解压一次再试 */
    @Synchronized
    private fun ensureInit(): Boolean {
        if (tryInit()) return true
        if (!recopied) { recopyData(); return tryInit() }
        return false
    }

    /** 花花：手写诊断写进崩溃日志目录，导出崩溃日志时一起带出 */
    private fun writeDiag(stage: String, detail: String) {
        runCatching {
            val dir = java.io.File(Launcher.instance.context.filesDir, "crash_logs").apply { mkdirs() }
            val hw = java.io.File(com.yuyan.imemodule.application.CustomConstant.HW_DICT_PATH)
            val files = hw.walkTopDown().filter { it.isFile }.toList()
            val text = buildString {
                append("time=").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append('\n')
                append("stage=").append(stage).append('\n')
                append("detail=").append(detail).append('\n')
                append("hwDir=").append(hw.path).append('\n')
                append("dataReady=").append(dataReady()).append(" initialized=").append(HandWriting.initialized)
                    .append(" recopied=").append(recopied).append('\n')
                append("fileCount=").append(files.size).append(" totalBytes=").append(files.sumOf { it.length() }).append('\n')
                files.forEach { append(it.path.removePrefix(hw.path)).append(' ').append(it.length()).append('\n') }
            }
            java.io.File(dir, "hw_diag.txt").writeText(text)
        }
    }

    init {
        ensureInit()
        mHanyuPinyinOutputFormat = HanyuPinyinOutputFormat()
        mHanyuPinyinOutputFormat.caseType = HanyuPinyinCaseType.LOWERCASE
        mHanyuPinyinOutputFormat.toneType = HanyuPinyinToneType.WITH_TONE_MARK
        mHanyuPinyinOutputFormat.vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    private fun recognize(points: IntArray): Array<String?>? {
        if (!HandWriting.initialized) return null
        return runCatching {
            HandWriting.reset()
            HandWriting.inputHWPoints(points)
            HandWriting.getCandidatesPyComposition()?.getOrNull(0)
        }.getOrNull()
    }

    fun recognitionData(strokes: MutableList<Short?>, recogResult: IHandWritingCallBack){
        if (!ensureInit()) {   // 花花：手写数据没就绪就不识别，不闪退
            writeDiag("init_failed", "ensureInit=false")
            return
        }
        val intArray = strokes.toMutableList().filterNotNull().map { it.toInt() }.toIntArray()
        var candidates = recognize(intArray)
        if (candidates == null && !recopied) {   // 引擎起来了但识别返回空：重解压一次再试
            writeDiag("null_result_before_recopy", "points=${intArray.size}")
            synchronized(this) { recopyData(); tryInit() }
            candidates = recognize(intArray)
        }
        if ((candidates == null || candidates.isEmpty()) && diagFailCount++ < 3) {
            writeDiag("no_candidates", "points=${intArray.size} result=${if (candidates == null) "null" else "empty"}")
        } else if (!diagOkWritten) {
            diagOkWritten = true
            writeDiag("ok", "points=${intArray.size} candidates=${candidates?.size} first=${candidates?.firstOrNull()}")
        }
        val result = candidates
        if (result != null && result.isNotEmpty()) {
            val recogResultData = recogResult
            val recogResultItems = ArrayList<CandidateListItem>()
            for (candidate in result) {
                if (candidate == null) continue
                recogResultItems.add(
                    CandidateListItem(
                        PinyinHelper.toHanYuPinyin(candidate, mHanyuPinyinOutputFormat, "'").ifEmpty { candidate }, candidate
                    )
                )
            }
            recogResultData.onSucess(recogResultItems.toTypedArray())
        }
    }
}