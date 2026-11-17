package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.PrefixAdapter
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.SideSymbol
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.utils.KeyboardLoaderUtil.Companion.instance
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.TextKeyboard
import com.yuyan.imemodule.keyboard.HandwritingKeyboard
import com.yuyan.imemodule.libs.recyclerview.SwipeRecyclerView
import splitties.dimensions.dp
import splitties.views.dsl.core.margin

/**
 * 九宫格键盘容器
 *
 * 包含输入键盘键盘[TextKeyboard]及拼音选择界面两层。
 *
 * 其中：
 *  输入键盘占据全部空间，左上角由拼音选择栏占位按键[InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL]站位。
 *
 *  拼音选择栏（无拼音时显示中文符号）位于键盘左上角，拼音选择栏占位按键正上方。
 */
@SuppressLint("ViewConstructor")
open class T9TextContainer(context: Context?, inputView: InputView, skbValue: Int = 0) : InputBaseContainer(context, inputView) {
    private var mSkbValue: Int = 0
    private val mSideSymbolsPinyin:List<SideSymbol>
    // 键盘、候选词界面上符号(T9左侧、手写右侧)、候选拼音ListView
    private val mRVLeftPrefix : SwipeRecyclerView = inflate(getContext(), R.layout.sdk_view_rv_prefix, null) as SwipeRecyclerView
    private var mPrefixAdapter: PrefixAdapter? = null
    /** 花花：左侧列表当前显示的是候选拼音（true）还是侧边符号（false）；点击监听器按它分派 */
    private var mPrefixShowsPinyin = false
    private val mLlAddSymbol : LinearLayout = LinearLayout(context).apply{
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { margin = (dp(20)) }
        gravity = Gravity.CENTER
    }

    init {
        mSkbValue = skbValue
        val ivAddSymbol = ImageView(context).apply {
            setPadding(dp(5))
            setImageResource(R.drawable.ic_menu_setting)
            drawable.setTint(ThemeManager.activeTheme.keyTextColor)
        }
        ivAddSymbol.setOnClickListener { _:View ->
            val arguments = Bundle()
            arguments.putInt("type", if(skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) 1 else 0)
            AppUtil.launchSettingsToPrefix(context!!, arguments)
        }
        mLlAddSymbol.addView(ivAddSymbol)
        mSideSymbolsPinyin =  if(skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) DataBaseKT.instance.sideSymbolDao().getAllSideSymbolNumber()
                else DataBaseKT.instance.sideSymbolDao().getAllSideSymbolPinyin()
        // 花花：SwipeRecyclerView 只允许在 setAdapter 之前设置点击监听器（之后设置会抛 IllegalStateException），
        // 而现在打字时复用适配器、不再 setAdapter(null)，所以监听器只在这里（首次 setAdapter 之前）设一次，
        // 点击时按 mPrefixShowsPinyin 判断当前显示的是拼音还是符号。
        mRVLeftPrefix.setOnItemClickListener{ _: View?, position: Int ->
            if (!mPrefixShowsPinyin) {
                val symbol = mSideSymbolsPinyin.getOrNull(position)?.symbolValue ?: return@setOnItemClickListener
                val softKey = SoftKey(label = symbol)
                // 播放按键声音和震动
                DevicesUtils.tryPlayKeyDown()
                DevicesUtils.tryVibrate(this)
                inputView.responseKeyEvent(softKey)
            } else {
                inputView.selectPrefix(position)
            }
        }
    }

    /**
     * 更新软键盘布局
     */
    override fun updateSkbLayout() {
        if (null == mMajorView) {
            mMajorView = HandwritingKeyboard(context)
            val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            addView(mMajorView, params)
            mMajorView!!.setResponseKeyEvent(inputView)
        }
        val softKeyboard = instance.getSoftKeyboard(mSkbValue)
        mMajorView!!.setSoftKeyboard(softKeyboard)
        updateKeyboardView()
        mMajorView!!.invalidate()
    }

    // 更新键盘上侧边符号列表
    private fun updateKeyboardView() {
        val softKeyboard = mMajorView!!.getSoftKeyboard()
        val softKeySymbolHolder = softKeyboard.getKeyByCode(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL) ?: return
        val prefixLayoutParams = LayoutParams(softKeySymbolHolder.width(), LayoutParams.MATCH_PARENT)
        prefixLayoutParams.setMargins(
            softKeyboard.keyXMargin,
            softKeySymbolHolder.mTop + softKeyboard.keyYMargin,
            softKeyboard.keyXMargin,
            EnvironmentSingleton.instance.skbHeight - softKeySymbolHolder.mBottom + softKeyboard.keyYMargin
        )
        val prefixLayoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        mRVLeftPrefix.setLayoutManager(prefixLayoutManager)
        if (mRVLeftPrefix.parent != null) {
            val parent = mRVLeftPrefix.parent as ViewGroup
            parent.removeView(mRVLeftPrefix)
        }
        addView(mRVLeftPrefix, prefixLayoutParams)
        updateSymbolListView()
    }

    //更新符号显示,九宫格左侧符号栏
    fun updateSymbolListView() {
        var prefixs = DictDecoder.prefixs
        val isPrefixs = prefixs.isNotEmpty()
        if (!isPrefixs) { // 有候选拼音显示候选拼音
            prefixs = mSideSymbolsPinyin.map { it.symbolKey }.toTypedArray()
        }
        mPrefixShowsPinyin = isPrefixs
        // 花花：每打一个字都 setAdapter(null)+setAdapter(新适配器) 会清空回收池、重新创建所有条目视图。
        // 页脚（符号设置按钮）状态没变时只换数据；变了（有拼音↔无拼音）仍按原来的方式整个换。
        val adapter = mPrefixAdapter
        val footerShown = mRVLeftPrefix.footerCount > 0
        if (adapter != null && footerShown == !isPrefixs && mRVLeftPrefix.adapter != null) {
            adapter.setData(prefixs)
            mRVLeftPrefix.scrollToPosition(0)
            return
        }
        if (!isPrefixs) {
            if (mRVLeftPrefix.footerCount <= 0) {
                mRVLeftPrefix.addFooterView(mLlAddSymbol)
            }
        } else {
            if (mRVLeftPrefix.footerCount > 0) {
                mRVLeftPrefix.removeFooterView(mLlAddSymbol)
            }
        }
        val newAdapter = PrefixAdapter(context, prefixs)
        mPrefixAdapter = newAdapter
        mRVLeftPrefix.setAdapter(null)
        mRVLeftPrefix.setAdapter(newAdapter)
    }
}
