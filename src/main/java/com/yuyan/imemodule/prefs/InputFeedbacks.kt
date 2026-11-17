package com.yuyan.imemodule.prefs

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.view.HapticFeedbackConstants
import android.view.View
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.utils.audioManager
import com.yuyan.imemodule.utils.vibrator

object InputFeedbacks {

    private val soundOnKeyPress by AppPrefs.getInstance().internal.soundOnKeyPress
    private val vibrationAmplitude by AppPrefs.getInstance().internal.vibrationAmplitude

    private val vibrator = Launcher.instance.context.vibrator
    private val hasAmplitudeControl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
    private val audioManager = Launcher.instance.context.audioManager

    // ---------------- 花花：按键音色 ----------------
    // 「CyIME 风格」用 CyIME（github.com/Cyletix/CyIME，GPL-3.0，tag 1.15.94）res/raw 里的
    // Rain75 紫薇轴实录（kb_rec_normal/backspace/space/enter.wav，CyIME 软键盘的默认音色），
    // 只切掉开头 4ms 的静音、加 1ms 淡入，见 res/raw/cyime_key_*.wav 与 yuyansdk/THIRD_PARTY_SOUNDS.md。
    // 播放方式同 CyIME：SoundPool 预加载、解码好的 PCM 直接出声，play() 不走 Binder、不等解码，不卡主线程。
    // 「花花原声」= 以前的系统按键音（AudioManager.playSoundEffect）。
    private val soundStyle by AppPrefs.getInstance().keyEffect.soundStyle
    private var soundPool: SoundPool? = null
    private val soundIds = IntArray(4)
    private val loaded = BooleanArray(4)
    private var lastDeleteStream = 0

    /** 键盘弹出时调用：建 SoundPool 并后台解码四个音（每个十几 KB），第一下按键就有声。 */
    @Synchronized
    fun preloadSounds() {
        if (soundPool != null) return
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
            pool.setOnLoadCompleteListener { _, id, status ->
                if (status == 0) for (i in soundIds.indices) if (soundIds[i] == id) loaded[i] = true
            }
            val ctx = Launcher.instance.context
            soundIds[SoundEffect.Standard.ordinal] = pool.load(ctx, com.yuyan.imemodule.R.raw.cyime_key_normal, 1)
            soundIds[SoundEffect.SpaceBar.ordinal] = pool.load(ctx, com.yuyan.imemodule.R.raw.cyime_key_space, 1)
            soundIds[SoundEffect.Delete.ordinal] = pool.load(ctx, com.yuyan.imemodule.R.raw.cyime_key_delete, 1)
            soundIds[SoundEffect.Return.ordinal] = pool.load(ctx, com.yuyan.imemodule.R.raw.cyime_key_enter, 1)
            soundPool = pool
        }
    }

    /** CyIME 风格出声；还没加载好（极少见，刚开机第一下）返回 false，交给系统按键音。 */
    private fun playCyime(effect: SoundEffect, volume: Float): Boolean {
        val pool = soundPool ?: run { preloadSounds(); return false }
        val i = effect.ordinal
        if (!loaded[i]) return false
        // 长按删除连发时只保留最新一下，免得叠成嗡嗡声（同 CyIME）
        if (effect == SoundEffect.Delete && lastDeleteStream != 0) pool.stop(lastDeleteStream)
        val stream = pool.play(soundIds[i], volume, volume, 1, 0, 1f)
        if (effect == SoundEffect.Delete) lastDeleteStream = stream
        return stream != 0
    }

    fun hapticFeedback(view: View) {
        val duration = when (vibrationAmplitude) {
            0 -> {
                @Suppress("DEPRECATION")
                val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, flags)
                0L
            }
            1 -> 0L
            2 -> 5L
            3 -> 30L
            else -> 50L
        }
        if (duration != 0L) {
            if (hasAmplitudeControl) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    }

    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    fun soundEffect(effect: SoundEffect) {
        val fx = when (effect) {
            SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
            SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
            SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
            SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
        }
        if (soundOnKeyPress in 5..10) return   // 声音关
        // 系统默认音量档（滑条最左）：CyIME 风格按 CyIME 默认的 50% 放
        val volume = if (soundOnKeyPress < 5) -1f else ((soundOnKeyPress - 10) / 30f).coerceIn(0f, 1f)
        if (soundStyle != "system" && runCatching { playCyime(effect, if (volume < 0f) 0.5f else volume) }.getOrDefault(false)) return
        audioManager.playSoundEffect(fx, volume)
    }

}