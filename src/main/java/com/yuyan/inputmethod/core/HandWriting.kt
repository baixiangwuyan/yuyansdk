package com.yuyan.inputmethod.core

import android.content.Context
import androidx.annotation.Keep

object HandWriting {

    init {
        System.loadLibrary("handwriting")
    }

    var initialized = false
        private set

    fun init(context: Context): Boolean {
        if (this.initialized) return true
        val result = initWithDirectory(context, context.getExternalFilesDir("hw").toString())
        this.initialized = result
        return  result
    }

    /** 花花：释放引擎，下次 init 重新加载（手写数据重解压后用） */
    fun resetInit() {
        if (initialized) runCatching { release() }
        initialized = false
    }

    fun setProperties() {
        reloadConfig()
    }

    fun selectInputMode(i: Int): Boolean {
        return activeMode(i)
    }

    @Throws(NumberFormatException::class)
    fun getCandidatesPyComposition(): Array<Array<String?>?>? {
        return getCandidates()
    }

    @Keep
    external fun getPackageName(): String?

    external fun activeMode(mode: Int): Boolean

    external fun getCandidates(): Array<Array<String?>?>?

    external fun initWithDirectory(context: Context, str: String?): Boolean

    external fun inputHWPoints(iArr: IntArray?): Boolean

    external fun release()

    external fun reloadConfig(): Boolean

    external fun reset(): Boolean
}