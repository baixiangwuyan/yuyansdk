
package com.yuyan.imemodule.prefs

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.behavior.ClipboardLayoutMode
import com.yuyan.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.yuyan.imemodule.prefs.behavior.FullDisplayCenterMode
import com.yuyan.imemodule.prefs.behavior.FullDisplayKeyMode
import com.yuyan.imemodule.prefs.behavior.HalfWidthSymbolsMode
import com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.view.preference.ManagedPreference


class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val pinyinModeRime = string("input_method_pinyin_mode_rime", CustomConstant.SCHEMA_ZH_T9) //拼音输入模式记录，记录引擎
        val inputDefaultMode = int("input_default_method_mode", InputModeSwitcher.MODE_T9_CHINESE)   //默认输入法类型
        val inputMethodPinyinMode = int("input_method_pinyin_mode", InputModeSwitcher.MODE_T9_CHINESE)  // 保存中文输入法类型
        val dataDictVersion = int("rime_dict_data_version", 0)  //缓存rime词库版本号,用于校验是否覆盖词库文件
        val keyboardHeightRatio = float("keyboard_height_ratio", 0.3f)     //键盘高度比例
        val keyboardHeightRatioLandscape = float("keyboard_height_ratio_landscape", 0.5f)     //键盘高度比例:横屏
        val candidatesHeightRatio = float("candidates_height_ratio", 0.035f)     //候选词栏高度比例
        val candidatesHeightRatioLandscape = float("candidates_height_ratio_landscape", 0.06f)     //候选词栏高度比例:横屏
        val keyboardModeFloat = bool("keyboard_mode_float", false)     // 悬浮模式
        val keyboardModeFloatLandscape = bool("keyboard_mode_float_landscape", false)// 悬浮模式:横屏
        val keyboardBottomPaddingFloat = int("keyboard_padding_bottom", DevicesUtils.dip2px(100))     //竖屏悬浮模式底边距
        val keyboardRightPaddingFloat = int("keyboard_padding_right", DevicesUtils.dip2px(20))     //竖屏悬浮模式右边距
        val keyboardBottomPaddingLandscapeFloat = int("keyboard_padding_bottom_landscape", DevicesUtils.dip2px(50))     //横屏悬浮模式底边距
        val keyboardRightPaddingLandscapeFloat = int("keyboard_padding_right_landscape", DevicesUtils.dip2px(20))     //横屏悬浮模式右边距
        val keyboardBottomPadding = int("keyboard_padding_bottom_normal", DevicesUtils.dip2px(0))     //竖屏非悬浮底边距
        val keyboardRightPadding = int("keyboard_padding_right_normal", DevicesUtils.dip2px(0))     //竖屏非悬浮右边距
        val clipboardUpdateTime = long("clipboard_update_time", 0)     //剪切板更新时间
        val clipboardUpdateContent = string("clipboard_update_content","")     //剪切板更新内容
        val fullDisplayKeyboardEnable = bool("full_display_keyboard_enable", true)     //全面屏键盘优化
        val fullDisplayKeyModeLeft = string("full_display_key_mode_left", FullDisplayKeyMode.SwitchIme.name)     //全面屏键盘优化:左键盘
        val fullDisplayKeyModeRight = string("full_display_key_mode_right", FullDisplayKeyMode.Clipboard.name)     //全面屏键盘优化：右键盘
        val fullDisplayCenterMode = string("full_display_center_mode", FullDisplayCenterMode.MoveCursor.name)     //全面屏键盘优化：中间区域

        val soundOnKeyPress = int("key_press_vibration_amplitude",0)     //按键音量
        val vibrationAmplitude = int("key_press_sound_volume", 0)     //触感强度

        val privacyPolicySure = bool("privacy_policy_sure", false) //是否同意隐私政策
    }

    inner class Input : ManagedPreferenceCategory(R.string.setting_ime_input, sharedPreferences) {

        val titleChinese = category(R.string.chinese_input_setting)

        val chineseFanTi = switch(
            R.string.setting_jian_fan, "chinese_jian_fan_enable", false
        )

        val doublePYSchemaMode =
            list(
                R.string.double_pinyin_schema_mode,
                "double_pinyin_schema_mode",
                DoublePinyinSchemaMode.flypy,
                DoublePinyinSchemaMode,
                listOf(
                    DoublePinyinSchemaMode.flypy,
                    DoublePinyinSchemaMode.natural,
                    DoublePinyinSchemaMode.abc,
                    DoublePinyinSchemaMode.mspy,
                    DoublePinyinSchemaMode.sogou,
                    DoublePinyinSchemaMode.ziguang,
                ),
                listOf(
                    R.string.double_pinyin_flypy_plus,
                    R.string.double_pinyin_natural,
                    R.string.double_pinyin_abc,
                    R.string.double_pinyin_mspy,
                    R.string.double_pinyin_sougou,
                    R.string.double_pinyin_ziguang,
                )
            )

        val chinesePrediction = switch(
            R.string.chinese_association, "chinese_association_enable", true
        )

        val chinesePredictionDate = switch(
            R.string.chinese_association_date, "chinese_association_date_enable", true
        )

        val titleEnglish = category(R.string.EnglishInput)

        //输出英文单词:英文补全
        val abcSearchEnglishCell = switch(
            R.string.search_english_cell, "search_english_cell_enable", true
        )

        val abcSpaceAuto = switch(
            R.string.space_auto, "abc_space_auto_enable", false
        ){
            abcSearchEnglishCell.getValue()
        }

        val titleEmoji = category(R.string.emoji_setting)
        val emojiInput = switch(
            R.string.emoji_input, "emoji_input_enable", true
        )

        val titleSymbol = category(R.string.symbol_setting)
        val symbolPairInput = switch(
            R.string.symbol_pair_input, "symbol_pair_input_enable", true
        )
    }

    inner class KeyboardSetting : ManagedPreferenceCategory(R.string.setting_ime_keyboard, sharedPreferences) {

        val candidateTextSize = int(
            R.string.candidate_size_input_setting,
            "candidate_size",
            55,
            25,
            100,
            "%",
            defaultLabel = R.string.system_default
        )

        // 花花：按键气泡默认开，开关挪到「按键效果」页（这里不再显示）
        val keyboardBalloonShow = ManagedPreference.PBool(sharedPreferences, "keyboard_balloon_show_enable", true).apply { register() }

        val longPressTimeout = int(
            R.string.long_press_timeout,
            "long_press_timeout",
            400,
            100,
            700,
            "毫秒",
            50,
            defaultLabel = R.string.number_400_ms
        )


        val abcNumberLine = switch(R.string.engish_full_keyboard, "keyboard_abc_number_line_enable", false)

        val lx17WithLeftPrefix = switch(R.string.lx17_with_left_prefix, "lx17_with_left_prefix_enable", true)

        val keyboardDoubleInputKey = switch(R.string.keyboard_double_input_key, "keyboard_double_input_pinyin_enable", true)

        val keyboardMnemonic = switch(R.string.keyboard_mnemonic_show, "keyboard_mnemonic_show_enable", false)

        val spaceSwipeMoveCursor = switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)

        val spaceSwipeMoveCursorSpeed = int(
            R.string.swipe_move_cursor_speed,
            "swipe_move_cursor_speed",
            10,
            1,
            50,
            "px"
        )

        // 锁定英语键盘:锁定后，切换到英语键盘，下次弹出键盘使用英语模式
        val keyboardLockEnglish = switch(R.string.keyboard_menu_lock_english, "keyboard_menu_lock_english_enable", false)

        val oneHandedModSwitch = switch(R.string.keyboard_one_handed_mod, "keyboard_one_handed_mod_enable", false)

        val oneHandedMod = list(
            R.string.keyboard_one_handed_mod,
            "keyboard_one_handed_mod",
            KeyboardOneHandedMod.LEFT,
            KeyboardOneHandedMod,
            listOf(
                KeyboardOneHandedMod.LEFT,
                KeyboardOneHandedMod.RIGHT
            ),
            listOf(
                R.string.keyboard_one_handed_mod_left,
                R.string.keyboard_one_handed_mod_right
            )
        ) {
            oneHandedModSwitch.getValue()
        }

        val halfWidthSymbolsMode = list(
            R.string.half_width_symbols_tips,
            "half_width_symbols_tips",
            HalfWidthSymbolsMode.All,
            HalfWidthSymbolsMode,
            listOf(
                HalfWidthSymbolsMode.All,
                HalfWidthSymbolsMode.OnlyUsed,
                HalfWidthSymbolsMode.None
            ),
            listOf(
                R.string.half_width_symbols_tips_all,
                R.string.half_width_symbols_tips_only_used,
                R.string.half_width_symbols_tips_none
            )
        )

        val showVirtualKeyboardOnPhysicalKeyboard = switch(R.string.show_virtual_keyboard_with_external, "show_virtual_keyboard_with_external", false)
    }

    inner class Voice : ManagedPreferenceCategory(R.string.setting_ime_input, sharedPreferences) {
//        val titleChinese = category(R.string.chinese_input_setting)
    }

    inner class Other : ManagedPreferenceCategory(R.string.setting_ime_other, sharedPreferences) {
        val imeHideIcon = switch(R.string.ime_hide_icon, "ime_hide_icon_enable", false, R.string.ime_hide_icon_tips)
    }

    inner class Handwriting : ManagedPreferenceCategory(R.string.setting_ime_input, sharedPreferences) {

        val handWritingFullScreen = switch(
            R.string.hand_writing_full_screen, "hand_writing_full_screen", false, R.string.hand_writing_full_screen_tips
        )


        val handWritingWidth = int(
            R.string.paint_thickness,
            "hand_writing_width",
            35,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        )

        val handWritingSpeed = int(
            R.string.discern_sensitive,
            "hand_writing_speed",
            500,
            300,
            1300,
            "毫秒",
            100,
            defaultLabel = R.string.number_500_ms
        )
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimit = int(
            R.string.clipboard_limit,
            "clipboard_limit",
            50,
            10,
            110,
            "条",
            10,
            defaultLabel = R.string.num_50
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            10,
            200,
            "秒"
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }

        val clipboardLayoutCompact = list(
            R.string.clipboard_layout_compact_mode,
            "clipboard_layout_mode",
            ClipboardLayoutMode.ListView,
            ClipboardLayoutMode,
            listOf(
                ClipboardLayoutMode.ListView,
                ClipboardLayoutMode.GridView,
                ClipboardLayoutMode.FlexboxView
            ),
            listOf(
                R.string.clipboard_layout_mode_list,
                R.string.clipboard_layout_mode_grid,
                R.string.clipboard_layout_mode_flexbox
            )
        ) {
            clipboardListening.getValue()
        }
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    /** 花花：「按键效果」里的视觉效果（按键气泡见 [KeyboardSetting.keyboardBalloonShow]），默认全关 */
    inner class KeyEffect : ManagedPreferenceInternal(sharedPreferences) {
        val motion = bool("key_effect_motion", false)          // 按键动效：缩放回弹
        val glow = bool("key_effect_glow", false)              // 键内炫光
        val gap = bool("key_effect_gap", false)                // 键缝动效
        val gapStyle = string("key_effect_gap_style", "trace") // trace 线路 / frame 方框 / diamond 菱形
        val gapSpeed = int("key_effect_gap_speed", 10)         // 速度，×0.1 倍（5～20）
        val gapReach = int("key_effect_gap_reach", 20)         // 范围，×0.1 个键（10～40）
        val gapWidth = int("key_effect_gap_width", 20)         // 线宽，×0.1 dp（10～40）
        val gapBrightness = int("key_effect_gap_brightness", 90) // 亮度 %（30～100）
        val gapTail = int("key_effect_gap_tail", 60)           // 拖尾 %（0～100）
        val gapLayers = int("key_effect_gap_layers", 2)        // 层数（1～3 道）
        val glass = bool("key_effect_glass", false)            // 玻璃效果
        val liquid = bool("key_effect_liquid", false)          // 液态玻璃（需玻璃开着）
        val liquidStrength = string("key_effect_liquid_strength", "standard") // standard/strong/strongest
        val glowColors = string("key_effect_glow_colors", "vivid")   // 花花：炫光颜色 vivid 多彩 / theme 跟随主题
        val rainbowBackground = bool("key_effect_rainbow_bg", false) // 花花：彩虹渐变键盘背景，默认关
        val soundStyle = string("key_sound_style", "cyime")          // 花花：按键音色 cyime（CyIME Rain75 实录，新默认）/ system（花花原声=系统按键音）
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }


    val internal = Internal().register()
    val voice = Voice().register()
    val handwriting = Handwriting().register()
    val input = Input().register()
    val clipboard = Clipboard().register()
    val keyboardSetting = KeyboardSetting().register()
    val other = Other().register()
    val keyEffect = KeyEffect().register()

    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            providers.forEach {
                it.managedPreferences[key]?.fireChange()
            }
        }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = Launcher.instance.context.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            internal.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }

            input.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            voice.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            handwriting.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            clipboard.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            keyboardSetting.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            other.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            keyEffect.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }
    companion object {
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}