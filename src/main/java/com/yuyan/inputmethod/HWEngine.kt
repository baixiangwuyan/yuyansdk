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

    /** 花花：引擎没起来时，数据到位后再试一次初始化 */
    @Synchronized
    private fun ensureInit(): Boolean {
        if (HandWriting.initialized) return true
        if (!dataReady()) return false
        if (!HandWriting.init(Launcher.instance.context)) return false
        HandWriting.setProperties()
        HandWriting.selectInputMode(5)
        return true
    }

    init {
        ensureInit()
        mHanyuPinyinOutputFormat = HanyuPinyinOutputFormat()
        mHanyuPinyinOutputFormat.caseType = HanyuPinyinCaseType.LOWERCASE
        mHanyuPinyinOutputFormat.toneType = HanyuPinyinToneType.WITH_TONE_MARK
        mHanyuPinyinOutputFormat.vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    fun recognitionData(strokes: MutableList<Short?>, recogResult: IHandWritingCallBack){
        if (!ensureInit()) return   // 花花：手写数据没就绪就不识别，不闪退（后台会补复制）
        HandWriting.reset()
        val strokesData = strokes.toMutableList()
        val intArray = strokesData.filterNotNull().map { it.toInt() }.toIntArray()
        HandWriting.inputHWPoints(intArray)
        val candidatesPyComposition: Array<Array<String?>?>? =
            runCatching { HandWriting.getCandidatesPyComposition() }.getOrNull()
        val candidates = candidatesPyComposition?.getOrNull(0)
        if (candidates != null && candidates.isNotEmpty()) {
            val recogResultData = recogResult
            val recogResultItems = ArrayList<CandidateListItem>()
            for (candidate in candidates) {
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