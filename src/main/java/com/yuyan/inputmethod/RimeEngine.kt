package com.yuyan.inputmethod

import android.view.KeyEvent
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Rime
import com.yuyan.inputmethod.data.InputKey
import com.yuyan.inputmethod.data.KeyRecordStack
import com.yuyan.inputmethod.util.DoublePinYinUtils
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.QwertyPinYinUtils
import com.yuyan.inputmethod.util.T9PinYinUtils
import java.util.Locale

object RimeEngine {
    private val keyRecordStack = KeyRecordStack()
    private var pinyins: Array<String> = emptyArray() // 候选词界面的候选拼音列表
    var showCandidates: List<CandidateListItem> = emptyList() // 所有待展示的候选词
    var showComposition: String = "" // 候选词上方展示的拼音
    var preCommitText: String = "" // 待提交的文字
    private var rimeAssoc: List<String?> = emptyList()   // Rime 原始联想顺序
    private var customPhraseSize: Int = 0 // 自定义引擎候选词长度
    const val MASK_CASE_LOWER = 0
    private var charCase = 0x0000
    private val typedCase = StringBuilder()   // 逐字母记录，如 "HeLLo"
    var nextKeyUpper = false
    fun init() {
        Rime.getInstance(false)
    }

    fun selectSchema(mod: String): Boolean {
        keyRecordStack.clear()
        typedCase.clear()
        charCase = MASK_CASE_LOWER
        Rime.startup(Launcher.instance.context, false)
        return Rime.selectSchema(mod)
    }

    fun getCurrentRimeSchema(): String {
        return Rime.getCurrentRimeSchema()
    }

    /**
     * 是否输入完毕
     */
    fun isFinish(): Boolean {
        return keyRecordStack.isEmpty()
    }

    fun onNormalKey(event: KeyEvent) {
        val keyCode = event.keyCode
        val keyChar = if(keyCode == KeyEvent.KEYCODE_APOSTROPHE) if(isFinish()) '/'.code else '\''.code
            else event.unicodeChar
        if (keyRecordStack.pushKey(event)) {
            Rime.processKey(keyChar, event.action)
            if (Character.isLetter(keyChar)) {
                val c = keyChar.toChar()
                typedCase.append(if (nextKeyUpper) c.uppercaseChar() else c.lowercaseChar())
            }
        }
        nextKeyUpper = false
        updateCandidatesOrCommitText()
    }

    fun onDeleteKey() {
        keyRecordStack.processDelAction()
        if (typedCase.isNotEmpty()) typedCase.deleteCharAt(typedCase.length - 1)
        updateCandidatesOrCommitText()
    }

    fun selectCandidate(index: Int): String? {
        val indexReal = index - customPhraseSize
        Rime.selectCandidate(indexReal)
        keyRecordStack.pushCandidateSelectAction()
        return updateCandidatesOrCommitText()
    }

    fun getNextPageCandidates(): Array<CandidateListItem> {
        return if (Rime.hasRight()) {
            Rime.processKey(keyRecordStack.getRimeKeycodeByName("Page_Down"), 0)
           val candidates = Rime.getRimeContext()!!.candidates
            if (InputModeSwitcher.isEnglish) {
                for (item in candidates) item.text = applyTypedCase(item.text)
            } else when (charCase) {
                KeyEvent.META_SHIFT_ON -> {
                    for (item in candidates) {
                        item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    }
                }
                KeyEvent.META_CAPS_LOCK_ON -> {
                    for (item in candidates) {
                        item.text = item.text.uppercase()
                    }
                }
                else -> {
                    for (item in candidates) {
                        item.text = item.text.lowercase()
                    }
                }
            }
            candidates
        } else emptyArray()
    }

    fun selectPinyin(index: Int) {
        val pinyinKey = keyRecordStack.pushPinyinSelectAction(pinyins[index]) ?: return
        Rime.replaceKey(pinyinKey.posInInput, pinyinKey.t9Keys().length, pinyinKey.pinyin())
        updateCandidatesOrCommitText()
    }

    fun predictAssociationWords(text: String) {
        pinyins = emptyArray()
        if (text.isNotEmpty()) {
            showCandidates = buildList {
                val seen = HashSet<String>()
                fun addWord(w: String?) { if (!w.isNullOrEmpty() && seen.add(w)) add(CandidateListItem("", w)) }
                UserNextWord.onText(text).forEach(::addWord)          // 你常接的词排最前
                val words = Rime.getAssociateList(text)
                rimeAssoc = words.toList()
                words.take(5).forEach(::addWord)
                CustomEngine.predictAssociationWordsChinese(text).forEach(::addWord)
                words.drop(5).forEach(::addWord)
            }
            showComposition = ""
        }
    }

    fun selectAssociation(index: Int) {
        val indexReal = index - customPhraseSize
        val word = showCandidates.getOrNull(indexReal)?.text ?: ""
        // 列表顺序被重排过，按词找回它在 Rime 联想里的位置；不是 Rime 的词就不通知 Rime
        val rimeIndex = rimeAssoc.indexOf(word)
        if (rimeIndex >= 0) Rime.chooseAssociate(rimeIndex)
        updateCandidatesOrCommitText()
        preCommitText = word
    }

    fun reset() {
        showCandidates = emptyList()
        pinyins = emptyArray()
        showComposition = ""
        preCommitText = ""
        keyRecordStack.clear()
        typedCase.clear()
        Rime.clearComposition()
        if(charCase == KeyEvent.META_SHIFT_ON) charCase = MASK_CASE_LOWER
    }

    fun destroy() = Rime.destroy()

    private fun updateCandidatesOrCommitText(): String? {
        val rimeCommit = Rime.getRimeCommit()
        if (rimeCommit != null) {
            keyRecordStack.clear()
            preCommitText = rimeCommit.commitText
            preCommitText = if (InputModeSwitcher.isEnglish) {
                applyTypedCase(preCommitText)
            } else if (charCase == KeyEvent.META_SHIFT_ON) {
                preCommitText.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            } else if (charCase == KeyEvent.META_CAPS_LOCK_ON) {
                preCommitText.uppercase()
            } else {
                preCommitText.lowercase()
            }
            typedCase.clear()
            showComposition = ""
            showCandidates = emptyList()
            return preCommitText
        }
        val candidates = Rime.getRimeContext()?.candidates?.asList() ?: emptyList()
        customPhraseSize = 0
        val compositionText = Rime.compositionText
        showCandidates = when {
            compositionText.isNotBlank() -> {
                val phrase = CustomEngine.processPhrase(compositionText.replace("\'", ""))
                if(InputModeSwitcher.isEnglish && StringUtils.isLetter(compositionText) &&
                    !compositionText.equals(candidates.first().text, ignoreCase = true) ){
                    phrase.add(0, compositionText)
                }
                customPhraseSize = phrase.size
                phrase.map { content -> CandidateListItem("📋", content) }.toMutableList().plus(candidates)
            }
            else -> candidates
        }
        var count = Rime.compositionText.count { it in 'A'..'Z' }
        if (count > 0) {
            keyRecordStack.forEachReversed { inputKey ->
                if (inputKey is InputKey.T9Key) inputKey.consumed = count-- <= 0
            }
        }
        var composition = getCurrentComposition(candidates)
        if (InputModeSwitcher.isEnglish) {
            for (item in showCandidates) item.text = applyTypedCase(item.text)
            composition = applyTypedCase(composition)
        } else when (charCase) {
            KeyEvent.META_SHIFT_ON -> {
                for (item in showCandidates) item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                composition = composition.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            KeyEvent.META_CAPS_LOCK_ON -> {
                for (item in showCandidates) item.text = item.text.uppercase()
                composition = composition.uppercase()
            }
            else -> {
                for (item in showCandidates) item.text = item.text.lowercase()
                composition = composition.lowercase()
            }
        }
        val rimeSchema = Rime.getCurrentRimeSchema()
        pinyins = when (rimeSchema) {
            CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.t9KeyToPinyin(Regex("[A-Z]+").find(compositionText.replace("'", ""))?.value ?: "")
            }
            CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> {
                LX17PinYinUtils.lx17KeyToPinyin(Regex("[A-Z]+").find(compositionText.replace("'", ""))?.value ?: "")
            }
            else -> {
                emptyArray()
            }
        }
        showComposition = composition
        preCommitText = ""
        return null
    }

    /**
     * 拿到候选词拼音组合
     */
    fun getPrefixs(): Array<String> {
        return pinyins
    }

    private fun getCurrentComposition(candidates: List<CandidateListItem>): String {
        val composition = Rime.compositionText
        val rimeSchema = Rime.getCurrentRimeSchema()
        if(rimeSchema == CustomConstant.SCHEMA_EN) return ""
        if(composition.isEmpty()) return ""
        if(candidates.isEmpty()) return composition
        val comment = candidates.first().comment
        val result =  when {
            comment.isNotBlank() && comment.startsWith("~") -> composition
            rimeSchema == CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.getT9Composition(composition, comment)
            }
            rimeSchema.startsWith(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) -> {
                if(!AppPrefs.getInstance().keyboardSetting.keyboardDoubleInputKey.getValue()) composition
                else DoublePinYinUtils.getDoublePinYinComposition(rimeSchema, composition, comment)
            }
            else -> {
                QwertyPinYinUtils.getQwertyComposition(composition, comment)
            }
        }
        return if (!composition.endsWith("'") && result.endsWith("'")) result.dropLast(1) else result
    }

    /**
     * 设置输入法搜索参数
     */
    fun setImeOption(option: String, value: Boolean) {
        Rime.setOption(option, value)
    }

    private fun applyTypedCase(text: String): String {
        val raw = typedCase.toString()
        if (text.isEmpty() || raw.isEmpty()) return text
        if (text.equals(raw, ignoreCase = true)) return raw          // 和敏的字母一样：完全按敏的大小写
        if (raw.none { it.isUpperCase() }) return text               // 没按 Shift：其他候选保持词库原样
        return when {
            raw.length > 1 && raw.all { it.isUpperCase() } -> text.uppercase()   // 全大写
            raw.first().isUpperCase() -> text.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            else -> text
        }
    }

    fun setCharCase(charCase: Int) {
        this.charCase = charCase
    }

}