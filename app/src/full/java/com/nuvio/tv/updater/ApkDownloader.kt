package com.nuvio.tv.updater

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun download(
        url: String,
        destinationFile: File,
        expectedSha256: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Result<File> {
        return runCatching {
            destinationFile.parentFile?.mkdirs()
            if (destinationFile.exists()) destinationFile.delete()

            val request = Request.Builder()
                .url(url)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed: HTTP ${response.code}")
                }

                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }
                val digest = MessageDigest.getInstance("SHA-256")

                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        output.flush()
                    }
                }

                // The release says what the file should be; a damaged or swapped download is never installed.
                if (!Checksum.matches(expectedSha256, Checksum.toHex(digest.digest()))) {
                    destinationFile.delete()
                    error("The downloaded update didn't pass its safety check, so it was not installed. Please try again.")
                }
            }

            destinationFile
        }
    }
}
