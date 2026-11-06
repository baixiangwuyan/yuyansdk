package com.yuyan.imemodule.service

import android.view.KeyEvent
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.inputmethod.RimeEngine

object InputDispatcher {

    // 初始化输入法
    @Synchronized
    fun initImeSchema(schema: String) {
        if (!EngineLoader.ready) { EngineLoader.pendingSchema = schema; return }  // 花花：引擎没好先记下，不在主线程等
        RimeEngine.selectSchema(schema)
        nativeUpdateImeOption()
    }

    fun getCurrentRimeSchema(): String {
        if (!EngineLoader.ready) return EngineLoader.currentSchemaGuess()
        return RimeEngine.getCurrentRimeSchema()
    }

    // 传入一个键码
    fun inputKeyCode(event: KeyEvent) {
        if (!EngineLoader.ready) { EngineLoader.queueKey(event); return }  // 花花：先排队，引擎好了补上
        DictDecoder.inputAction()
        RimeEngine.onNormalKey(event)
    }

    // 是否输入完毕，等待上屏。
    val isFinish: Boolean
        get() = if (!EngineLoader.ready) !EngineLoader.hasPendingKeys else RimeEngine.isFinish()

    // 选择某个候选拼音
    fun selectPrefix(index: Int) {
        if (!EngineLoader.ready) return
        DictDecoder.selectPrefix(index)
        RimeEngine.selectPinyin(index)
    }

    // 执行选择动作，选择了index指向的词语
    fun getWordSelectedWord(index: Int) {
        if (!EngineLoader.ready) return
        if (DictDecoder.isAssociate) RimeEngine.selectAssociation(index)
        else if(!isFinish)RimeEngine.selectCandidate(index)
    }

    // 删除操作
    fun deleteAction() {
        if (!EngineLoader.ready) { EngineLoader.popKey(); return }
        DictDecoder.deleteAction()
        RimeEngine.onDeleteKey()
    }

    // 重置输入状态
    fun reset() {
        DictDecoder.reset()
        if (!EngineLoader.ready) { EngineLoader.clearKeys(); return }
        RimeEngine.reset()
    }

    // 释放内存
    fun resetIme() {
        EngineLoader.loadInBackground()  // 花花：后台加载，主线程不等
    }

    // 根据输入的字符查询候选词
    fun predictAssociationWords(words: String) {
        if (!EngineLoader.ready) return
        DictDecoder.getAssociateWord(words)
        RimeEngine.predictAssociationWords(words)
    }

    // 刷新引擎配置
    fun nativeUpdateImeOption() {
        if (!EngineLoader.ready) return  // 加载完成时会一起设置
        val chineseFanTi = AppPrefs.getInstance().input.chineseFanTi.getValue()
        RimeEngine.setImeOption("traditionalization", chineseFanTi)
        val emojiInput = AppPrefs.getInstance().input.emojiInput.getValue()
        RimeEngine.setImeOption("emoji", emojiInput)
    }

    fun setCharCase(charCase: Int) {
        RimeEngine.setCharCase(charCase)
    }
}