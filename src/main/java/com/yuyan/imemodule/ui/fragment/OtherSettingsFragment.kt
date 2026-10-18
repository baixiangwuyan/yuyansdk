package com.yuyan.imemodule.ui.fragment

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.yuyan.imemodule.utils.clipboardManager
import com.yuyan.imemodule.voice.AsrModelRegistry
import com.yuyan.imemodule.voice.VoiceModelManager
import com.yuyan.imemodule.voice.VoicePermissionActivity
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceScreen
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.UserDataManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.ui.activity.LauncherActivity
import com.yuyan.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.addPreference
import com.yuyan.imemodule.utils.importErrorDialog
import com.yuyan.imemodule.utils.queryFileName
import com.yuyan.imemodule.utils.TimeUtils
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.imemodule.view.widget.withLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable

private val imeHideIcon = AppPrefs.getInstance().other.imeHideIcon

private val switchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
    val componentName = ComponentName(Launcher.instance.context.packageName, LauncherActivity::class.java.name)
    Launcher.instance.context.packageManager.setComponentEnabledSetting(componentName, if(value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
}

private const val GRAM_FILE = "huahua.gram"

class OtherSettingsFragment: ManagedPreferenceFragment(AppPrefs.getInstance().other){

    private var exportTimestamp = System.currentTimeMillis()
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var gramLauncher: ActivityResultLauncher<String>
    private lateinit var voiceLauncher: ActivityResultLauncher<String>

    override fun onStart() {
        super.onStart()
        imeHideIcon.registerOnChangeListener(switchKeyListener)
    }

    override fun onStop() {
        super.onStop()
        imeHideIcon.unregisterOnChangeListener(switchKeyListener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val name = cr.queryFileName(uri) ?: return@withContext
                        if (!name.endsWith(".zip")) {
                            ctx.importErrorDialog(R.string.exception_user_data_filename, name)
                            return@withContext
                        }
                        try {
                            val inputStream = cr.openInputStream(uri)!!
                            UserDataManager.import(inputStream).getOrThrow()
                            lifecycleScope.launch(NonCancellable + Dispatchers.Main) {
                                delay(400L)
                                AppUtil.exit()
                            }
                            withContext(Dispatchers.Main) {
                                AppUtil.showRestartNotification(ctx)
                                Toast.makeText(ctx, R.string.user_data_imported, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
        // 花花：导入语法模型（.gram），放到 Rime 用户目录 huahua.gram，重启后生效
        gramLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val name = cr.queryFileName(uri) ?: return@withContext
                        if (!name.endsWith(".gram", ignoreCase = true)) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "请选择 .gram 语法模型文件", Toast.LENGTH_LONG).show()
                            }
                            return@withContext
                        }
                        try {
                            val target = java.io.File(CustomConstant.RIME_DICT_PATH, GRAM_FILE)
                            val tmp = java.io.File(CustomConstant.RIME_DICT_PATH, "$GRAM_FILE.tmp")
                            cr.openInputStream(uri)!!.use { input ->
                                tmp.outputStream().use { input.copyTo(it) }
                            }
                            if (!tmp.renameTo(target)) {
                                tmp.copyTo(target, overwrite = true); tmp.delete()
                            }
                            lifecycleScope.launch(NonCancellable + Dispatchers.Main) {
                                delay(600L)
                                AppUtil.exit()
                            }
                            withContext(Dispatchers.Main) {
                                AppUtil.showRestartNotification(ctx)
                                Toast.makeText(ctx, "语法模型已导入，重启后生效", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
        // 花花：导入离线语音模型（直接选浏览器下载的 .tar.bz2 压缩包，不用解压）
        voiceLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(ctx) {
                    val name = withContext(Dispatchers.IO) { ctx.contentResolver.queryFileName(uri) }
                    val err = withContext(NonCancellable + Dispatchers.IO) {
                        VoiceModelManager.import(ctx, uri, name)
                    }
                    if (err != null) {
                        AlertDialog.Builder(ctx).setTitle("导入语音模型失败").setMessage(err)
                            .setPositiveButton(android.R.string.ok, null).show()
                    } else {
                        Toast.makeText(ctx, "语音模型已导入：${VoiceModelManager.currentModelName()}", Toast.LENGTH_LONG).show()
                        if (!VoicePermissionActivity.hasPermission(ctx)) VoicePermissionActivity.request(ctx)
                        refreshScreen()
                    }
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            UserDataManager.export(outputStream).getOrThrow()
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
    }

    private fun refreshScreen() {
        onCreatePreferences(null, null)  // 重建整页，刷新“已导入/未导入”状态
    }

    /** 花花：离线语音（导入/删除模型、下载地址） */
    private fun addVoicePreferences(screen: PreferenceScreen) {
        val ctx = requireContext()
        val name = VoiceModelManager.currentModelName()
        val summary = if (name != null) "已导入：%s（%.0f MB）".format(name, VoiceModelManager.modelSizeBytes() / 1048576.0)
            else "未导入。先用“下载语音模型”下载 .tar.bz2 压缩包，再在这里选它（不用解压）"
        screen.addPreference("导入语音模型", summary) {
            voiceLauncher.launch("*/*")
        }
        screen.addPreference("下载语音模型", "用浏览器下载（约 134MB，建议连 Wi-Fi）。推荐第一个：中英文、自动加标点") {
            val profiles = AsrModelRegistry.profiles
            val items = profiles.mapIndexed { i, p ->
                (if (i == 0) "【推荐】" else "") + p.name + "（" + p.size + "）\n" + p.description
            }.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle("选择要下载的语音模型")
                .setItems(items) { _, which ->
                    val p = profiles[which]
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.downloadUrl)))
                    } catch (e: Exception) {
                        ctx.clipboardManager.setPrimaryClip(ClipData.newPlainText("url", p.downloadUrl))
                        Toast.makeText(ctx, "没有找到浏览器，下载地址已复制", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        if (name != null) {
            screen.addPreference("删除语音模型", "释放约 170MB 空间，语音输入将不可用") {
                AlertDialog.Builder(ctx)
                    .setTitle("删除语音模型")
                    .setMessage("确定删除已导入的语音模型吗？")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        VoiceModelManager.delete()
                        refreshScreen()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        if (name != null && !VoicePermissionActivity.hasPermission(ctx)) {
            screen.addPreference("允许使用麦克风", "语音输入需要麦克风权限（只在本机识别，不联网）") {
                VoicePermissionActivity.request(ctx)
            }
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()
        screen.addPreference(R.string.export_user_data) {
            lifecycleScope.launch {
                exportTimestamp = System.currentTimeMillis()
                exportLauncher.launch("yuyanIme_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip")
            }
        }
        screen.addPreference(R.string.import_user_data) {
            AlertDialog.Builder(ctx)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.import_user_data)
                .setMessage(R.string.confirm_import_user_data)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    importLauncher.launch("application/zip")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        val gramFile = java.io.File(CustomConstant.RIME_DICT_PATH, GRAM_FILE)
        val gramSummary = if (gramFile.exists()) "已导入（%.1f MB），整句候选更准".format(gramFile.length() / 1048576.0)
            else "未导入。导入 .gram 文件后，拼音候选和整句排序更聪明"
        screen.addPreference("导入语法模型", gramSummary) {
            gramLauncher.launch("*/*")
        }
        if (gramFile.exists()) {
            screen.addPreference("删除语法模型", "释放空间，恢复为普通词频排序") {
                AlertDialog.Builder(ctx)
                    .setTitle("删除语法模型")
                    .setMessage("删除后需要重启输入法，继续吗？")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        gramFile.delete()
                        AppUtil.showRestartNotification(ctx)
                        lifecycleScope.launch { delay(400L); AppUtil.exit() }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        addVoicePreferences(screen)
    }
}