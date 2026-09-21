package com.reiyy.yuitag.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class GithubRelease(
    val tagName: String,
    val htmlUrl: String
)

/**
 * 从 GitHub 仓库 Releases 检查最新发布版本。
 * 仓库尚未发布时 GitHub API 返回 404，抛 [NoReleaseException]。
 */
class GithubReleaseChecker(
    private val repo: String = "Reiyy/YuiTag"
) {

    /** 获取最新正式发布（非草稿、非预发布）的 Release 信息。 */
    suspend fun checkLatestRelease(): GithubRelease = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/$repo/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "YuiTagApp")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw NoReleaseException("仓库尚未发布版本")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("检查更新失败 (HTTP $code)")
            }

            val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val json = JSONObject(body)
            GithubRelease(
                tagName = json.optString("tag_name", ""),
                htmlUrl = json.optString("html_url", "")
            )
        } finally {
            conn.disconnect()
        }
    }
}

class NoReleaseException(message: String) : Exception(message)
