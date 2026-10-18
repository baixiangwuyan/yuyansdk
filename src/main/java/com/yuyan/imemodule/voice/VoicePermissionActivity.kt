package com.yuyan.imemodule.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * 花花：透明 Activity，专门向用户申请麦克风权限（输入法自己不能弹权限框）。
 * 被永久拒绝（“不再询问”）时，跳到系统的应用信息页让用户手动打开。
 */
class VoicePermissionActivity : Activity() {

    companion object {
        private const val REQ = 1001

        fun hasPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 23 ||
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun request(context: Context) {
            context.startActivity(Intent(context, VoicePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasPermission(this) || Build.VERSION.SDK_INT < 23) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "已允许麦克风，回到输入框点麦克风即可说话", Toast.LENGTH_LONG).show()
        } else if (Build.VERSION.SDK_INT >= 23 && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "请在“权限 → 麦克风”里选择允许", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            Toast.makeText(this, "没有麦克风权限，无法语音输入", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
