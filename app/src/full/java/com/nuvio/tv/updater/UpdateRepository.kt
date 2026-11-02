package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.updater.model.AppUpdate
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class NoEligibleUpdateException(channel: UpdateChannel) :
    IllegalStateException("No compatible APK release found for ${channel.storedValue} channel")

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi,
    private val okHttpClient: OkHttpClient
) {

    suspend fun getLatestUpdate(channel: UpdateChannel): Result<AppUpdate> {
        // Test builds are their own app: they update only from the tester-only service, never from
        // the public releases.
        if (BuildConfig.NUVIO_CHANNEL == "test") {
            return runCatching { latestTestBuild(channel) }
        }
        return runCatching {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val releases = when (channel) {
                UpdateChannel.STABLE -> {
                    val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
                    if (!response.isSuccessful) {
                        error("GitHub API error: ${response.code()}")
                    }
                    listOf(response.body() ?: error("Empty GitHub release response"))
                }
                UpdateChannel.BETA -> {
                    val response = gitHubReleaseApi.getReleases(owner = owner, repo = repo)
                    if (!response.isSuccessful) {
                        error("GitHub API error: ${response.code()}")
                    }
                    response.body() ?: error("Empty GitHub release response")
                }
            }
            val releaseWithAsset = ReleaseSelector
                .eligibleReleases(releases, channel)
                .firstNotNullOfOrNull { release ->
                    AbiSelector.chooseBestApkAsset(release.assets)?.let { asset ->
                        release to asset
                    }
                }
                ?: throw NoEligibleUpdateException(channel)
            val (dto, asset) = releaseWithAsset

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            AppUpdate(
                tag = tag,
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size,
                assetSha256 = Checksum.fromDigest(asset.digest)
            )
        }
    }

    /** The newest test build, from the tester-only service (needs the tester key the build was made with). */
    private suspend fun latestTestBuild(channel: UpdateChannel): AppUpdate = withContext(Dispatchers.IO) {
        val key = BuildConfig.TESTER_KEY
        if (key.isBlank()) throw NoEligibleUpdateException(channel)
        val base = BuildConfig.TEST_UPDATES_URL.trimEnd('/')
        val request = Request.Builder()
            .url("$base/v1/updates/test/latest")
            .header("X-Tester-Key", key)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            // 404: no test build yet, or this build's key is no longer accepted.
            if (response.code == 404) throw NoEligibleUpdateException(channel)
            if (!response.isSuccessful) error("Couldn't check for test builds (${response.code})")
            val json = JSONObject(response.body?.string() ?: error("Empty test update response"))
            val versionName = json.optString("versionName")
            if (VersionUtils.parse(versionName) == null) error("Test update has no usable version")
            val apk = json.getJSONObject("apk")
            val url = apk.optString("url").takeIf { it.startsWith("https://") }
                ?: error("Test update has no download address")
            AppUpdate(
                tag = versionName,
                title = versionName,
                notes = json.optString("notes"),
                releaseUrl = null,
                assetName = apk.optString("name").ifBlank { "NuvioIPTV-Test.apk" },
                // The download needs the key too; it is sent in the address because the downloader
                // makes a plain request.
                assetUrl = url + (if ('?' in url) "&" else "?") + "key=" + URLEncoder.encode(key, "UTF-8"),
                assetSizeBytes = apk.optLong("size", -1L).takeIf { it > 0 },
                assetSha256 = Checksum.fromDigest(apk.optString("sha256"))
            )
        }
    }
}
