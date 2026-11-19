package com.example.service.update

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class AppUpdateManager(
    private val context: Context,
    private val repository: UpdateRepository = DefaultUpdateRepository(context),
    private val downloadManager: ApkDownloadManager = DefaultApkDownloadManager(context),
    private val integrityVerifier: ApkIntegrityVerifier = DefaultApkIntegrityVerifier(context),
    private val installer: ApkInstaller = DefaultApkInstaller(context),
    private val manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    private var activeJob: Job? = null

    val currentVersionCode: Int by lazy {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            1
        }
    }

    val currentVersionName: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    /**
     * Checks for updates against the remote manifest.
     * @param force If true, ignores the check cooldown interval and dismissed updates.
     */
    fun checkForUpdates(force: Boolean = false) {
        val currentStatus = _updateStatus.value
        if (currentStatus is UpdateStatus.Checking || currentStatus is UpdateStatus.Downloading || currentStatus is UpdateStatus.Verifying) {
            return
        }

        // Check throttle interval: at most once every 1 hour unless forced
        val now = System.currentTimeMillis()
        val lastCheck = repository.getLastCheckTimestamp()
        val cooldownMs = TimeUnit.HOURS.toMillis(1)
        if (!force && (now - lastCheck < cooldownMs)) {
            val cached = repository.getCachedManifest()
            if (cached != null && cached.versionCode > currentVersionCode) {
                val isMandatory = cached.mandatory || currentVersionCode < cached.minSupportedVersionCode
                val isDismissed = repository.getDismissedVersionCode() == cached.versionCode
                if (!isDismissed || isMandatory) {
                    _updateStatus.value = UpdateStatus.UpdateAvailable(cached, isMandatory, isDismissed)
                }
            }
            return
        }

        _updateStatus.value = UpdateStatus.Checking

        activeJob?.cancel()
        activeJob = scope.launch {
            val result = repository.fetchManifest(manifestUrl)
            if (result.isSuccess) {
                val manifest = result.getOrThrow()
                repository.setLastCheckTimestamp(now)

                if (manifest.versionCode > currentVersionCode) {
                    val isMandatory = manifest.mandatory || currentVersionCode < manifest.minSupportedVersionCode
                    val isDismissed = repository.getDismissedVersionCode() == manifest.versionCode
                    if (force || isMandatory || !isDismissed) {
                        _updateStatus.value = UpdateStatus.UpdateAvailable(
                            manifest = manifest,
                            isMandatory = isMandatory,
                            isDismissed = isDismissed
                        )
                    } else {
                        _updateStatus.value = UpdateStatus.Idle
                    }
                } else {
                    _updateStatus.value = UpdateStatus.UpToDate(currentVersionCode, currentVersionName)
                }
            } else {
                val error = result.exceptionOrNull()
                _updateStatus.value = UpdateStatus.Failed(
                    error = if (!repository.isNetworkAvailable()) UpdateError.NETWORK_UNAVAILABLE else UpdateError.INVALID_MANIFEST,
                    message = error?.message ?: "Failed to check for updates",
                    canRetry = true
                )
            }
        }
    }

    /**
     * Starts downloading and verifying the update APK.
     */
    fun startDownload(targetManifest: VersionManifest? = null) {
        val manifest = targetManifest ?: when (val s = _updateStatus.value) {
            is UpdateStatus.UpdateAvailable -> s.manifest
            is UpdateStatus.Failed -> (repository.getCachedManifest())
            else -> null
        } ?: return

        activeJob?.cancel()
        activeJob = scope.launch {
            _updateStatus.value = UpdateStatus.Downloading(
                manifest = manifest,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                progressPercent = 0
            )

            // Step 1: Download
            val downloadResult = downloadManager.downloadApk(manifest) { bytes, total, percent, speed ->
                _updateStatus.value = UpdateStatus.Downloading(
                    manifest = manifest,
                    bytesDownloaded = bytes,
                    totalBytes = total,
                    progressPercent = percent,
                    speedBytesPerSec = speed
                )
            }

            if (downloadResult.isFailure) {
                val error = downloadResult.exceptionOrNull()
                _updateStatus.value = UpdateStatus.Failed(
                    error = UpdateError.DOWNLOAD_FAILED,
                    message = error?.message ?: "APK download failed",
                    canRetry = true
                )
                return@launch
            }

            val apkFile = downloadResult.getOrThrow()

            // Step 2: Verification
            _updateStatus.value = UpdateStatus.Verifying(manifest)
            val verificationResult = integrityVerifier.verifyApk(apkFile, manifest)

            when (verificationResult) {
                is VerificationResult.Success -> {
                    _updateStatus.value = UpdateStatus.ReadyToInstall(manifest, apkFile)
                }
                is VerificationResult.ChecksumMismatch -> {
                    _updateStatus.value = UpdateStatus.Failed(
                        error = UpdateError.CHECKSUM_MISMATCH,
                        message = "Downloaded APK failed SHA-256 integrity verification",
                        canRetry = true
                    )
                }
                is VerificationResult.PackageMismatch -> {
                    _updateStatus.value = UpdateStatus.Failed(
                        error = UpdateError.PACKAGE_MISMATCH,
                        message = "APK package mismatch (${verificationResult.actual} vs ${verificationResult.expected})",
                        canRetry = false
                    )
                }
                is VerificationResult.SignatureMismatch -> {
                    _updateStatus.value = UpdateStatus.Failed(
                        error = UpdateError.SIGNATURE_MISMATCH,
                        message = "APK signing certificate does not match the installed version",
                        canRetry = false
                    )
                }
                is VerificationResult.VersionNotNewer -> {
                    _updateStatus.value = UpdateStatus.Failed(
                        error = UpdateError.VERSION_NOT_NEWER,
                        message = "APK version (${verificationResult.archiveCode}) is not newer than installed (${verificationResult.installedCode})",
                        canRetry = false
                    )
                }
                is VerificationResult.InvalidApk -> {
                    _updateStatus.value = UpdateStatus.Failed(
                        error = UpdateError.STORAGE_ERROR,
                        message = "Invalid APK file: ${verificationResult.reason}",
                        canRetry = true
                    )
                }
            }
        }
    }

    /**
     * Prompts the system package installer.
     */
    fun installUpdate(activity: Activity) {
        val current = _updateStatus.value
        val (manifest, apkFile) = when (current) {
            is UpdateStatus.ReadyToInstall -> current.manifest to current.apkFile
            is UpdateStatus.PermissionRequired -> current.manifest to current.apkFile
            else -> return
        }

        if (!installer.canInstallPackages()) {
            _updateStatus.value = UpdateStatus.PermissionRequired(manifest, apkFile)
            installer.openInstallPermissionSettings(activity)
            return
        }

        val result = installer.installApk(activity, apkFile)
        if (result.isFailure) {
            _updateStatus.value = UpdateStatus.Failed(
                error = UpdateError.INSTALLATION_LAUNCH_FAILED,
                message = result.exceptionOrNull()?.message ?: "Failed to open package installer",
                canRetry = true
            )
        }
    }

    /**
     * Dismisses an optional update dialog and records the dismissal to prevent repeated popups.
     */
    fun dismissUpdate() {
        val current = _updateStatus.value
        if (current is UpdateStatus.UpdateAvailable && !current.isMandatory) {
            repository.setDismissedVersionCode(current.manifest.versionCode)
        }
        _updateStatus.value = UpdateStatus.Idle
    }

    fun cancelActiveOperation() {
        downloadManager.cancelDownload()
        activeJob?.cancel()
        _updateStatus.value = UpdateStatus.Idle
    }

    companion object {
        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(context: Context): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
