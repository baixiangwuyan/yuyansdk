package com.yuyan.imemodule.manager

import com.yuyan.imemodule.BuildConfig
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.utils.errorRuntime
import com.yuyan.imemodule.utils.extract
import com.yuyan.imemodule.utils.withTempDir
import com.yuyan.imemodule.utils.versionCodeCompat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.yuyan.inputmethod.UserNextWord

object UserDataManager {

    private val json = Json { prettyPrint = true }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )

    private fun writeFileTree(srcDir: File, destPrefix: String, dest: ZipOutputStream) {
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "" && !skipInBackup(destPrefix, related)) {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    /** 花花：备份不含词库编译产物（rime/build，可由安装包重建）和模型文件（体积大，单独导入） */
    private fun skipInBackup(destPrefix: String, related: File): Boolean {
        if (destPrefix != "external") return false
        val path = related.invariantSeparatorsPath
        return path == "rime/build" || path.startsWith("rime/build/") ||
            path.endsWith(".gram") || path == "models" || path.startsWith("models/")
    }

    private val sharedPrefsDir = File(Launcher.instance.context.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(Launcher.instance.context.applicationInfo.dataDir, "databases")
    private val externalDir = Launcher.instance.context.getExternalFilesDir(null)!!

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        UserNextWord.save(sync = true)   // 先把内存里的联想记录写盘
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs
            writeFileTree(sharedPrefsDir, "shared_prefs", zipStream)
            // databases
            writeFileTree(dataBasesDir, "databases", zipStream)
            // external
            writeFileTree(externalDir, "external", zipStream)
            // metadata
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = Launcher.instance.context.packageManager.getPackageInfo(Launcher.instance.context.packageName, 0)
            val metadata = Metadata(
                pkgInfo.packageName,
                pkgInfo.versionCodeCompat,
                BuildConfig.versionName,
                timestamp
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun copyDir(source: File, target: File) {
        val exists = source.exists()
        val isDir = source.isDirectory
        if (exists && isDir) {
            source.copyRecursively(target, overwrite = true)
        } else {
            source.toString()
        }
    }

    fun import(src: InputStream) = runCatching {
        ZipInputStream(src).use { zipStream ->
            withTempDir { tempDir ->
                val metadataFile = zipStream.extract(tempDir).find { it.name == "metadata.json" } ?: errorRuntime(R.string.exception_user_data_metadata)
                val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
                copyDir(File(tempDir, "shared_prefs"), sharedPrefsDir)
                copyDir(File(tempDir, "databases"), dataBasesDir)
                copyDir(File(tempDir, "external"), externalDir)
                UserNextWord.reload()
                metadata
            }
        }
    }
}