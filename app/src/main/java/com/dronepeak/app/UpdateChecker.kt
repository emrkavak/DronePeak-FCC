package com.dronepeak.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val version: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long,
    val publishedAt: String,
    val sha256: String?
) {
    fun isNewerThan(currentVersion: String): Boolean {
        val cur = parseVersion(currentVersion)
        val new = parseVersion(version)
        val maxLen = maxOf(cur.size, new.size)
        for (i in 0 until maxLen) {
            val c = cur.getOrElse(i) { 0 }
            val n = new.getOrElse(i) { 0 }
            if (n != c) return n > c
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> =
        Regex("\\d+").findAll(v.removePrefix("v")).map { it.value.toInt() }.toList()
}

data class ProfileUpdateResult(
    val success: Boolean,
    val version: String,
    val updatedCount: Int,
    val message: String
)

object UpdateChecker {

    private const val UPSTREAM_REPO = "doesthings/FreeFCC"
    private const val UPSTREAM_RAW_BASE = "https://raw.githubusercontent.com/$UPSTREAM_REPO"
    private val apiUrl: String
        get() = "https://api.github.com/repos/${BuildConfig.DRONEPEAK_REPO}/releases/latest"
    private val PROFILE_FILES = listOf(
        "fcc.json",
        "fcc_keepalive.json",
        "ce_restore.json",
        "device_info.json",
        "led_on.json",
        "led_off.json",
        "4g.json"
    )

    fun fetchLatest(): UpdateInfo? {
        var conn: HttpURLConnection? = null
        if (BuildConfig.DRONEPEAK_REPO.startsWith("YOUR_GITHUB_USERNAME/")) {
            Log.w("DronePeak-Update", "DronePeak release repo is not configured")
            return null
        }
        return try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DronePeak-App")
            }

            if (conn.responseCode != 200) return null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "").removePrefix("v")
            val name = json.optString("name", "v$tagName")
            val changelog = json.optString("body", "").trim()
            val publishedAt = json.optString("published_at", "")

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L
            var sha256: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val nameField = asset.optString("name", "")
                    if (nameField.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0)
                        sha256 = asset.optString("digest", "").removePrefix("sha256:").ifEmpty { null }
                        break
                    }
                }
            }

            UpdateInfo(
                version = tagName,
                title = name,
                changelog = changelog,
                downloadUrl = apkUrl,
                apkSize = apkSize,
                publishedAt = publishedAt,
                sha256 = sha256
            )
        } catch (e: Exception) {
            Log.w("DronePeak-Update", "fetchLatest failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun downloadProfiles(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit
    ): ProfileUpdateResult {
        val version = info.version.ifBlank { "main" }.substringBefore("-dp.")
        val refs = listOf("v$version", version, "main").distinct()
        val tmpDir = File(context.cacheDir, "upstream_profiles_tmp").apply {
            deleteRecursively()
            mkdirs()
        }
        val destDir = File(context.filesDir, "upstream_profiles").apply { mkdirs() }
        val manifestFiles = JSONArray()

        return try {
            PROFILE_FILES.forEachIndexed { index, fileName ->
                val json = fetchProfileFromRefs(refs, fileName)
                    ?: return ProfileUpdateResult(false, version, index, "missing:$fileName")
                validateProfileJson(fileName, json)
                val bytes = json.toByteArray(Charsets.UTF_8)
                val sha256 = sha256(bytes)
                File(tmpDir, fileName).writeBytes(bytes)
                manifestFiles.put(JSONObject().apply {
                    put("name", fileName)
                    put("sha256", sha256)
                    put("bytes", bytes.size)
                })
                onProgress((index + 1).toFloat() / PROFILE_FILES.size)
            }

            PROFILE_FILES.forEach { fileName ->
                val tmpFile = File(tmpDir, fileName)
                val finalTmp = File(destDir, "$fileName.tmp")
                val finalFile = File(destDir, fileName)
                tmpFile.copyTo(finalTmp, overwrite = true)
                if (!finalTmp.renameTo(finalFile)) {
                    finalTmp.copyTo(finalFile, overwrite = true)
                    finalTmp.delete()
                }
            }

            val manifest = JSONObject().apply {
                put("source", UPSTREAM_REPO)
                put("version", version)
                put("updated_at", System.currentTimeMillis())
                put("files", manifestFiles)
            }
            File(destDir, "profiles_manifest.json").writeText(manifest.toString(2))

            ProfileUpdateResult(true, version, PROFILE_FILES.size, "ok")
        } catch (e: Exception) {
            Log.w("DronePeak-Update", "downloadProfiles failed: ${e.javaClass.simpleName}: ${e.message}")
            ProfileUpdateResult(false, version, 0, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { tmpDir.deleteRecursively() }
        }
    }

    fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit
    ): File? {
        if (info.downloadUrl.isBlank()) return null

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, "DronePeak-FCC-${info.version}.apk")
        var conn: HttpURLConnection? = null

        return try {
            conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", "DronePeak-App")
            }
            if (conn.responseCode !in 200..299) return null

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            var downloaded = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }

            info.sha256?.let { expected ->
                val actual = sha256(apkFile.readBytes())
                require(actual.equals(expected, ignoreCase = true)) { "APK SHA-256 mismatch" }
            }

            onProgress(1f)
            apkFile
        } catch (e: Exception) {
            Log.w("DronePeak-Update", "downloadApk failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { apkFile.delete() }
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchProfileFromRefs(refs: List<String>, fileName: String): String? {
        for (ref in refs) {
            val url = "$UPSTREAM_RAW_BASE/$ref/app/src/main/assets/profiles/$fileName"
            fetchText(url)?.let { return it }
        }
        return null
    }

    private fun fetchText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("User-Agent", "DronePeak-App")
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun validateProfileJson(fileName: String, json: String) {
        require(json.length in 2..524_288) { "invalid profile size: $fileName" }
        val obj = JSONObject(json)
        require(obj.has("name")) { "missing name: $fileName" }
        require(obj.has("sender")) { "missing sender: $fileName" }
        require(obj.has("cmd_type")) { "missing cmd_type: $fileName" }
        require(obj.has("rounds")) { "missing rounds: $fileName" }
        if (fileName == "4g.json") {
            require(obj.has("frame_count")) { "missing frame_count: $fileName" }
            require(obj.has("cmd_set")) { "missing cmd_set: $fileName" }
            require(obj.has("cmd_id_start")) { "missing cmd_id_start: $fileName" }
            require(obj.has("dst")) { "missing dst: $fileName" }
            require(obj.has("payload_prefix_hex")) { "missing payload_prefix_hex: $fileName" }
        } else {
            val frames = obj.getJSONArray("frames")
            require(frames.length() > 0) { "empty frames: $fileName" }
            for (i in 0 until frames.length()) {
                val frame = frames.getJSONObject(i)
                require(frame.has("s") && frame.has("i") && frame.has("d")) { "invalid frame $i: $fileName" }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
