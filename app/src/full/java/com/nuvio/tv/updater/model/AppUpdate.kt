package com.nuvio.tv.updater.model

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
    // Expected SHA-256 of the APK (lower-case hex), when the release says what it should be.
    val assetSha256: String? = null
)
