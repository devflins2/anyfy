package com.example.service.update

import java.io.File

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpdateAvailable(
        val manifest: VersionManifest,
        val isMandatory: Boolean,
        val isDismissed: Boolean = false
    ) : UpdateStatus()
    data class UpToDate(val versionCode: Int, val versionName: String) : UpdateStatus()
    data class Downloading(
        val manifest: VersionManifest,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercent: Int,
        val speedBytesPerSec: Long = 0L
    ) : UpdateStatus()
    data class Verifying(val manifest: VersionManifest) : UpdateStatus()
    data class ReadyToInstall(
        val manifest: VersionManifest,
        val apkFile: File
    ) : UpdateStatus()
    data class PermissionRequired(
        val manifest: VersionManifest,
        val apkFile: File
    ) : UpdateStatus()
    data class Failed(
        val error: UpdateError,
        val message: String,
        val canRetry: Boolean = true,
        val manifest: VersionManifest? = null
    ) : UpdateStatus()
}

enum class UpdateError {
    NETWORK_UNAVAILABLE,
    INVALID_MANIFEST,
    DOWNLOAD_FAILED,
    CHECKSUM_MISMATCH,
    PACKAGE_MISMATCH,
    SIGNATURE_MISMATCH,
    VERSION_NOT_NEWER,
    INSTALLATION_PERMISSION_DENIED,
    INSTALLATION_LAUNCH_FAILED,
    STORAGE_ERROR
}
