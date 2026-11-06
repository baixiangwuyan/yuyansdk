package com.yuyan.imemodule.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yuyan.imemodule.utils.thread.ThreadPoolUtils

/**
 * 花花：开机解锁后（以及覆盖安装后）预热。
 * 收到广播时进程已启动，Application 里已经开始后台加载引擎；这里只是让系统多留进程一会儿，
 * 等引擎和词库缓存都就绪，第一次点输入框就不用再冷启动。
 */
class BootWarmupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ThreadPoolUtils.executeSingleton {   // 排在引擎加载之后执行
            try { EngineLoader.awaitReady(8000) } finally { pending.finish() }
        }
    }
}
