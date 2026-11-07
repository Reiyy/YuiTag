package com.reiyy.yuitag.data

import com.reiyy.yuitag.util.VersionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 软件更新通道。
 */
enum class UpdateChannel(val key: String, val title: String, val subtitle: String) {
    RELEASE("release", "正式版", "仅接收稳定版本"),
    BETA("beta", "开发版", "接收开发版与正式版");

    companion object {
        const val PREF_KEY = "update_channel"

        fun fromKey(key: String?): UpdateChannel {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: RELEASE
        }
    }
}

data class GithubRelease(
    val tagName: String,
    val htmlUrl: String,
    val name: String = "",
    val isPrerelease: Boolean = false,
    val body: String = ""
)

/**
 * 从 GitHub 仓库 Releases 检查最新发布版本。
 * 仓库尚未发布时抛 [NoReleaseException]。
 */
class GithubReleaseChecker(
    private val repo: String = "Reiyy/YuiTag"
) {

    /**
     * 根据所选更新通道检查最新版本。
     * - [channel] = [UpdateChannel.RELEASE]：仅筛选正式发布版（排除带有 -beta, -alpha 等预发布后缀的版本）；
     * - [channel] = [UpdateChannel.BETA]：开发版（预发布）与正式版均可接收。
     */
    suspend fun checkLatestRelease(channel: UpdateChannel = UpdateChannel.RELEASE): GithubRelease = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/$repo/releases?per_page=30")
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
            if (code == 403) {
                throw IOException("GitHub API 请求频率受限，请稍后再试")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("检查更新失败 (HTTP $code)")
            }

            val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val jsonArray = JSONArray(body)
            if (jsonArray.length() == 0) {
                throw NoReleaseException("仓库尚未发布版本")
            }

            val matchingReleases = mutableListOf<GithubRelease>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val isDraft = item.optBoolean("draft", false)
                if (isDraft) continue

                val tagName = item.optString("tag_name", "").trim()
                if (tagName.isEmpty()) continue

                val isPre = item.optBoolean("prerelease", false) || VersionUtils.isPrerelease(tagName)

                // 正式版通道：排除预发布版
                if (channel == UpdateChannel.RELEASE && isPre) {
                    continue
                }

                matchingReleases.add(
                    GithubRelease(
                        tagName = tagName,
                        htmlUrl = item.optString("html_url", ""),
                        name = item.optString("name", ""),
                        isPrerelease = isPre,
                        body = item.optString("body", "")
                    )
                )
            }

            if (matchingReleases.isEmpty()) {
                if (channel == UpdateChannel.RELEASE) {
                    throw NoReleaseException("正式版通道暂无可用版本")
                } else {
                    throw NoReleaseException("仓库暂无可用版本")
                }
            }

            // 在符合通道条件的 Release 中，按语义化版本选出最新版本
            val bestRelease = matchingReleases.maxWithOrNull { a, b ->
                VersionUtils.compare(a.tagName, b.tagName)
            } ?: matchingReleases.first()

            bestRelease
        } finally {
            conn.disconnect()
        }
    }
}

class NoReleaseException(message: String) : Exception(message)
