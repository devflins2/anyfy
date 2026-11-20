package com.example.service.update

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val installedVersionCodeOverride: Int? = null,
    private val installedVersionNameOverride: String? = null
) {

    companion object {
        private const val TAG = "AppUpdateManager"

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

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    private var activeJob: Job? = null

    val currentVersionCode: Int by lazy {
        installedVersionCodeOverride ?: run {
            try {
                val bCode = BuildConfig.VERSION_CODE
                if (bCode > 0) return@run bCode
            } catch (_: Throwable) {}

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
    }

    val currentVersionName: String by lazy {
        installedVersionNameOverride ?: run {
            try {
                val bName = BuildConfig.VERSION_NAME
                if (!bName.isNullOrBlank()) return@run bName
            } catch (_: Throwable) {}

            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            } catch (_: Exception) {
                "1.0.0"
            }
        }
    }

    /**
     * Checks for updates against the remote manifest.
     * @param force If true, ignores the check cooldown interval and dismissed updates.
     */
    fun checkForUpdates(force: Boolean = false) {
        val currentStatus = _updateStatus.value
        if (currentStatus is UpdateStatus.Checking || currentStatus is UpdateStatus.Downloading || currentStatus is UpdateStatus.Verifying) {
            Log.d(TAG, "Update check already in progress (currentStatus=$currentStatus); skipping duplicate trigger")
            return
        }

        // If a newer version is already cached in memory or prefs, display it immediately
        val cached = repository.getCachedManifest()
        if (cached != null && cached.versionCode > currentVersionCode) {
            val isMandatory = cached.mandatory || currentVersionCode < cached.minSupportedVersionCode
            val isDismissed = repository.getDismissedVersionCode() == cached.versionCode
            if (!isDismissed || isMandatory) {
                Log.d(TAG, "Displaying cached update manifest: v${cached.versionName} (${cached.versionCode})")
                _updateStatus.value = UpdateStatus.UpdateAvailable(cached, isMandatory, isDismissed)
                return
            }
        }

        // Check throttle interval: 15-minute cooldown between background checks unless forced
        val now = System.currentTimeMillis()
        val lastCheck = repository.getLastCheckTimestamp()
        val cooldownMs = TimeUnit.MINUTES.toMillis(15)
        if (!force && (now - lastCheck < cooldownMs)) {
            Log.d(TAG, "Throttling update check: last check was ${(now - lastCheck) / 1000}s ago (cooldown: ${cooldownMs / 1000}s)")
            return
        }

        _updateStatus.value = UpdateStatus.Checking
        Log.i(TAG, "Initiating update check against $manifestUrl (force=$force, installedVersionCode=$currentVersionCode)")

        activeJob?.cancel()
        activeJob = scope.launch {
            val result = repository.fetchManifest(manifestUrl)
            if (result.isSuccess) {
                val manifest = result.getOrThrow()
                repository.setLastCheckTimestamp(now)

                Log.i(TAG, "Version comparison: Remote versionCode=${manifest.versionCode} vs Installed versionCode=$currentVersionCode (Installed versionName=$currentVersionName)")
                if (manifest.versionCode > currentVersionCode) {
                    val isMandatory = manifest.mandatory || currentVersionCode < manifest.minSupportedVersionCode
                    val isDismissed = repository.getDismissedVersionCode() == manifest.versionCode
                    Log.i(TAG, "Newer version detected! isMandatory=$isMandatory, isDismissed=$isDismissed, force=$force")
                    if (force || isMandatory || !isDismissed) {
                        _updateStatus.value = UpdateStatus.UpdateAvailable(
                            manifest = manifest,
                            isMandatory = isMandatory,
                            isDismissed = isDismissed
                        )
                    } else {
                        Log.d(TAG, "Update available but suppressed because optional version ${manifest.versionCode} was previously dismissed")
                        _updateStatus.value = UpdateStatus.Idle
                    }
                } else {
                    Log.i(TAG, "Application is up to date (versionCode=$currentVersionCode)")
                    _updateStatus.value = UpdateStatus.UpToDate(currentVersionCode, currentVersionName)
                }
            } else {
                val error = result.exceptionOrNull()
                Log.w(TAG, "Update check failed: ${error?.message}", error)
                _updateStatus.value = UpdateStatus.Failed(
                    error = if (!repository.isNetworkAvailable()) UpdateError.NETWORK_UNAVAILABLE else UpdateError.INVALID_MANIFEST,
                    message = error?.message ?: "Failed to check for updates",
                    canRetry = true,
                    manifest = null
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
            is UpdateStatus.Failed -> s.manifest ?: repository.getCachedManifest()
            else -> null
        } ?: return

        activeJob?.cancel()
        activeJob = scope.launch {
            Log.i(TAG, "Starting APK download for v${manifest.versionName} (${manifest.versionCode}) from ${manifest.apkUrl}")
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
                Log.e(TAG, "APK download failed: ${error?.message}", error)
                _updateStatus.value = UpdateStatus.Failed(
                    error = UpdateError.DOWNLOAD_FAILED,
                    message = error?.message ?: "APK download failed",
                    canRetry = true,
                    manifest = manifest
                )
                return@launch
            }

            val apkFile = downloadResult.getOrThrow()
            Log.i(TAG, "APK download completed (${apkFile.length()} bytes). Verifying integrity and signatures...")

            // Step 2: Verification
            _updateStatus.value = UpdateStatus.Verifying(manifest)
            val verificationResult = integrityVerifier.verifyApk(apkFile, manifest)

            when (verificationResult) {
                is VerificationResult.Success -> {
                    Log.i(TAG, "APK integrity & signature verification SUCCEEDED for ${apkFile.absolutePath}")
                    _updateStatus.value = UpdateStatus.ReadyToInstall(manifest, apkFile)
                }
                is VerificationResult.ChecksumMismatch -> {
                    Log.e(TAG, "SHA-256 Checksum Mismatch! Expected=${manifest.sha256}, Actual=${verificationResult.actual}")
                    _updateStatus.value = UpdateStatus.Failed(
                        error = UpdateError.CHECKSUM_MISMATCH,
                        message = "Downloaded APK failed SHA-256 integrity verification",
                        canRetry = true,
                        manifest = manifest
                    )
                }
                is VerificationResult.PackageMismatch -> {
                    Log.e(TAG, "Package Mismatch! Expected=${verificationResult.expected}, Actual=${verificationResult.actual}")
                    _updateStatus.value = UpdateStatus.Failed(
                        error = UpdateError.PACKAGE_MISMATCH,
                        message = "APK package mismatch (${verificationResult.actual} vs ${verificationResult.expected})",
                        canRetry = false,
                        manifest = manifest
                    )
                }
                is VerificationResult.SignatureMismatch -> {
                    Log.e(TAG, "Signature Mismatch! APK signing certificate does not match installed app")
                    _updateStatus.value = UpdateStatus.Failed(
                        error = UpdateError.SIGNATURE_MISMATCH,
                        message = "APK signing certificate does not match the installed version",
                        canRetry = false,
                        manifest = manifest
                    )
                }
                is VerificationResult.VersionNotNewer -> {
                    Log.e(TAG, "Version not newer: archiveCode=${verificationResult.archiveCode} vs installed=${verificationResult.installedCode}")
                    _updateStatus.value = UpdateStatus.Failed(
                        error = UpdateError.VERSION_NOT_NEWER,
                        message = "APK version (${verificationResult.archiveCode}) is not newer than installed (${verificationResult.installedCode})",
                        canRetry = false,
                        manifest = manifest
                    )
                }
                is VerificationResult.InvalidApk -> {
                    Log.e(TAG, "Invalid APK file: ${verificationResult.reason}")
                    _updateStatus.value = UpdateStatus.Failed(
                        error = UpdateError.STORAGE_ERROR,
                        message = "Invalid APK file: ${verificationResult.reason}",
                        canRetry = true,
                        manifest = manifest
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
            Log.i(TAG, "Install permission required. Requesting user permission...")
            _updateStatus.value = UpdateStatus.PermissionRequired(manifest, apkFile)
            installer.openInstallPermissionSettings(activity)
            return
        }

        Log.i(TAG, "Launching Android system package installer for ${apkFile.absolutePath}")
        val result = installer.installApk(activity, apkFile)
        if (result.isFailure) {
            Log.e(TAG, "Failed to launch package installer: ${result.exceptionOrNull()?.message}")
            _updateStatus.value = UpdateStatus.Failed(
                error = UpdateError.INSTALLATION_LAUNCH_FAILED,
                message = result.exceptionOrNull()?.message ?: "Failed to open package installer",
                canRetry = true,
                manifest = manifest
            )
        }
    }

    /**
     * Dismisses an optional update dialog and records the dismissal to prevent repeated popups.
     */
    fun dismissUpdate() {
        val current = _updateStatus.value
        if (current is UpdateStatus.UpdateAvailable && !current.isMandatory) {
            Log.d(TAG, "User dismissed optional update v${current.manifest.versionName} (${current.manifest.versionCode})")
            repository.setDismissedVersionCode(current.manifest.versionCode)
        }
        _updateStatus.value = UpdateStatus.Idle
    }

    fun cancelActiveOperation() {
        Log.d(TAG, "Canceling active update operation")
        downloadManager.cancelDownload()
        activeJob?.cancel()
        _updateStatus.value = UpdateStatus.Idle
    }
}
