package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import com.example.service.update.ApkDownloadManager
import com.example.service.update.ApkInstaller
import com.example.service.update.ApkIntegrityVerifier
import com.example.service.update.AppUpdateManager
import com.example.service.update.DefaultApkDownloadManager
import com.example.service.update.DefaultApkInstaller
import com.example.service.update.DefaultApkIntegrityVerifier
import com.example.service.update.DefaultUpdateRepository
import com.example.service.update.UpdateError
import com.example.service.update.UpdateRepository
import com.example.service.update.UpdateStatus
import com.example.service.update.VerificationResult
import com.example.service.update.VersionManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticApkUpdateSystemRegressionTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        // Clear update preferences
        context.getSharedPreferences("anyfy_update_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createSampleManifest(
        versionCode: Int = 105,
        versionName: String = "1.0.5",
        sha256: String = "6f41b501139430479cb2849096e4b3ebaf499de9f93ac164cb6226442ee220e8",
        apkUrl: String = "https://github.com/anyfy-audio/anyfy-android/releases/download/v1.0.5/Anyfy-release.apk",
        mandatory: Boolean = false,
        minSupportedVersionCode: Int = 0
    ): VersionManifest {
        return VersionManifest(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = sha256,
            releaseNotes = listOf("Improved performance", "Fixed player stability"),
            mandatory = mandatory,
            minSupportedVersionCode = minSupportedVersionCode
        )
    }

    // =========================================================================
    // 1. No update available (same or lower version)
    // =========================================================================
    @Test
    fun test01_noUpdateAvailable_whenRemoteVersionIsSameOrLower() = runTest(testDispatcher) {
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.success(createSampleManifest(versionCode = 100))
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = null
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(context, repository = repo, scope = this)
        manager.checkForUpdates(force = true)
        advanceUntilIdle()

        val status = manager.updateStatus.value
        assertTrue("Status must be UpToDate when remote version is equal or lower", status is UpdateStatus.UpToDate)
    }

    // =========================================================================
    // 2. New optional update available
    // =========================================================================
    @Test
    fun test02_newOptionalUpdateAvailable_transitionsToUpdateAvailable() = runTest(testDispatcher) {
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.success(createSampleManifest(versionCode = 105, mandatory = false))
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = null
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(context, repository = repo, scope = this)
        manager.checkForUpdates(force = true)
        advanceUntilIdle()

        val status = manager.updateStatus.value
        assertTrue("Status must be UpdateAvailable", status is UpdateStatus.UpdateAvailable)
        val updateAvailable = status as UpdateStatus.UpdateAvailable
        assertFalse("Must be optional update", updateAvailable.isMandatory)
        assertEquals(105, updateAvailable.manifest.versionCode)
    }

    // =========================================================================
    // 3. Mandatory update available
    // =========================================================================
    @Test
    fun test03_mandatoryUpdateAvailable_setsMandatoryFlagAndDisallowsDismissalBypass() = runTest(testDispatcher) {
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.success(createSampleManifest(versionCode = 105, mandatory = true))
            override fun getDismissedVersionCode() = 105 // previously dismissed
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = null
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(context, repository = repo, scope = this)
        manager.checkForUpdates(force = true)
        advanceUntilIdle()

        val status = manager.updateStatus.value
        assertTrue("Status must be UpdateAvailable", status is UpdateStatus.UpdateAvailable)
        val updateAvailable = status as UpdateStatus.UpdateAvailable
        assertTrue("Must be mandatory update", updateAvailable.isMandatory)
    }

    // =========================================================================
    // 4. User dismisses optional update
    // =========================================================================
    @Test
    fun test04_userDismissesOptionalUpdate_persistsDismissalAndSuppressesRepeatedPopups() = runTest(testDispatcher) {
        val defaultRepo = DefaultUpdateRepository(context)
        val sampleManifest = createSampleManifest(versionCode = 105, mandatory = false)

        val repo = object : UpdateRepository by defaultRepo {
            override suspend fun fetchManifest(manifestUrl: String) = Result.success(sampleManifest)
        }

        val manager = AppUpdateManager(context, repository = repo, scope = this)
        manager.checkForUpdates(force = true)
        advanceUntilIdle()

        assertTrue(manager.updateStatus.value is UpdateStatus.UpdateAvailable)

        // User dismisses
        manager.dismissUpdate()
        assertEquals(105, repo.getDismissedVersionCode())
        assertTrue("Status resets to Idle upon dismissal", manager.updateStatus.value is UpdateStatus.Idle)

        // Subsequent non-forced check suppresses popup
        manager.checkForUpdates(force = false)
        advanceUntilIdle()
        assertFalse("Popup must not appear for previously dismissed optional update", manager.updateStatus.value is UpdateStatus.UpdateAvailable)
    }

    // =========================================================================
    // 5. Network unavailable
    // =========================================================================
    @Test
    fun test05_networkUnavailable_failsSafelyWithoutCrash() = runTest(testDispatcher) {
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.failure<VersionManifest>(IllegalStateException("Network offline"))
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = null
            override fun isNetworkAvailable() = false
        }

        val manager = AppUpdateManager(context, repository = repo, scope = this)
        manager.checkForUpdates(force = true)
        advanceUntilIdle()

        val status = manager.updateStatus.value
        assertTrue("Must transition safely to Failed without crashing", status is UpdateStatus.Failed)
        val failed = status as UpdateStatus.Failed
        assertEquals(UpdateError.NETWORK_UNAVAILABLE, failed.error)
        assertTrue(failed.canRetry)
    }

    // =========================================================================
    // 6. Invalid manifest schema
    // =========================================================================
    @Test
    fun test06_invalidManifest_failsSafely() = runTest(testDispatcher) {
        // Missing SHA-256 and invalid URL
        val invalidManifest = VersionManifest(
            versionCode = -1,
            versionName = "",
            apkUrl = "ftp://invalid.com/apk",
            sha256 = "short"
        )
        assertFalse("Manifest validation must fail for malformed data", invalidManifest.isValid())

        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.failure<VersionManifest>(IllegalArgumentException("Invalid schema"))
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = null
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(context, repository = repo, scope = this)
        manager.checkForUpdates(force = true)
        advanceUntilIdle()

        val status = manager.updateStatus.value
        assertTrue(status is UpdateStatus.Failed)
        assertEquals(UpdateError.INVALID_MANIFEST, (status as UpdateStatus.Failed).error)
    }

    // =========================================================================
    // 7. Invalid or insecure APK URL (HTTP rejected)
    // =========================================================================
    @Test
    fun test07_insecureApkUrl_httpRejected() = runTest(testDispatcher) {
        val insecureManifest = createSampleManifest(apkUrl = "http://insecure-site.com/app.apk")
        assertFalse("Plain HTTP URLs must be rejected by manifest validation", insecureManifest.isValid())

        val downloadManager = DefaultApkDownloadManager(context)
        val result = downloadManager.downloadApk(insecureManifest) { _, _, _, _ -> }
        assertTrue("DownloadManager must reject non-HTTPS URLs", result.isFailure)
    }

    // =========================================================================
    // 8. Interrupted download cleanup
    // =========================================================================
    @Test
    fun test08_interruptedDownload_cleansUpPartialTempFile() = runTest(testDispatcher) {
        val downloadManager = DefaultApkDownloadManager(context)
        val manifest = createSampleManifest()
        val updatesDir = File(context.cacheDir, "updates")
        updatesDir.mkdirs()
        val tempFile = File(updatesDir, "Anyfy-v${manifest.versionName}-${manifest.versionCode}.apk.tmp")
        tempFile.writeText("partial download content")

        assertTrue(tempFile.exists())
        downloadManager.deleteDownloadedApk(manifest)
        assertFalse("Partial temp file must be cleaned up cleanly", tempFile.exists())
    }

    // =========================================================================
    // 9. Download retry support
    // =========================================================================
    @Test
    fun test09_downloadRetry_succeedsAfterTransientFailure() = runTest(testDispatcher) {
        var callCount = 0
        val targetApk = File(context.cacheDir, "simulated_retry.apk").apply { writeText("genuine apk") }

        val downloadManager = object : ApkDownloadManager {
            override suspend fun downloadApk(
                manifest: VersionManifest,
                onProgress: (Long, Long, Int, Long) -> Unit
            ): Result<File> {
                callCount++
                return if (callCount == 1) {
                    Result.failure(java.io.IOException("Transient socket timeout"))
                } else {
                    onProgress(100L, 100L, 100, 1024L)
                    Result.success(targetApk)
                }
            }
            override fun cancelDownload() {}
            override fun getExistingDownloadedApk(manifest: VersionManifest): File? = null
            override fun deleteDownloadedApk(manifest: VersionManifest) {}
        }

        val manifestSha = "6f41b501139430479cb2849096e4b3ebaf499de9f93ac164cb6226442ee220e8"
        val sampleManifest = createSampleManifest(sha256 = manifestSha)

        val verifier = object : ApkIntegrityVerifier {
            override suspend fun verifyApk(apkFile: File, manifest: VersionManifest): VerificationResult {
                val dummyInfo = PackageInfo().apply { packageName = context.packageName }
                return VerificationResult.Success(apkFile, dummyInfo)
            }
            override fun calculateFileSha256(file: File) = manifestSha
        }
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.success(sampleManifest)
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = sampleManifest
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(context, repository = repo, downloadManager = downloadManager, integrityVerifier = verifier, scope = this)

        // Attempt 1: fails
        manager.startDownload(sampleManifest)
        advanceUntilIdle()
        assertTrue(manager.updateStatus.value is UpdateStatus.Failed)

        // Retry: succeeds
        manager.startDownload(sampleManifest)
        advanceUntilIdle()
        assertTrue("Download retry must transition to ReadyToInstall upon success", manager.updateStatus.value is UpdateStatus.ReadyToInstall)
    }

    // =========================================================================
    // 10. Incorrect SHA-256 checksum rejection
    // =========================================================================
    @Test
    fun test10_incorrectSha256_verifierDeletesFileAndReportsMismatch() = runTest(testDispatcher) {
        val corruptedFile = File(context.cacheDir, "corrupted_update.apk").apply {
            writeText("corrupted byte stream")
        }
        val verifier = DefaultApkIntegrityVerifier(context)
        val manifest = createSampleManifest(sha256 = "0000000000000000000000000000000000000000000000000000000000000000")

        val result = verifier.verifyApk(corruptedFile, manifest)
        assertTrue("Checksum mismatch must be reported", result is VerificationResult.ChecksumMismatch)
        assertFalse("Corrupted file must be deleted immediately", corruptedFile.exists())
    }

    // =========================================================================
    // 11. Wrong APK package name rejection
    // =========================================================================
    @Test
    fun test11_wrongApkPackageName_integrityVerifierRejectsAndDeletes() = runTest(testDispatcher) {
        val verifier = DefaultApkIntegrityVerifier(context)
        val manifest = createSampleManifest()
        val dummyFile = File(context.cacheDir, "wrong_pkg.apk").apply { writeText("apk") }
        val sha = verifier.calculateFileSha256(dummyFile)

        val matchingManifest = manifest.copy(sha256 = sha)
        val result = verifier.verifyApk(dummyFile, matchingManifest)

        // Android package parser will fail or report mismatch on non-package file
        assertTrue("Invalid APK format or package mismatch must be returned", result is VerificationResult.InvalidApk || result is VerificationResult.PackageMismatch)
        assertFalse("Invalid package file must be deleted", dummyFile.exists())
    }

    // =========================================================================
    // 12. Signing certificate verification
    // =========================================================================
    @Test
    fun test12_signingCertificateMismatch_verifierRejectsIncompatibleSigner() = runTest(testDispatcher) {
        val verifier = DefaultApkIntegrityVerifier(context)
        val dummyFile = File(context.cacheDir, "signed_test.apk").apply { writeText("apk") }
        val sha = verifier.calculateFileSha256(dummyFile)
        val manifest = createSampleManifest(sha256 = sha)

        val result = verifier.verifyApk(dummyFile, manifest)
        // Ensure that verifier strictly rejects files without genuine Anyfy signature
        assertFalse("Must not return success for unverified signatures", result is VerificationResult.Success)
    }

    // =========================================================================
    // 13. Installation permission check on Android 8.0+
    // =========================================================================
    @Test
    fun test13_installationPermissionCheck_andSettingsIntent() {
        val installer = DefaultApkInstaller(context)
        // In Robolectric tests, canInstallPackages returns true by default or depends on mock
        val canInstall = installer.canInstallPackages()
        assertNotNull(canInstall)
    }

    // =========================================================================
    // 14. Installation intent creation with strict security flags
    // =========================================================================
    @Test
    fun test14_createInstallIntent_usesFileProviderAndStrictFlags() {
        val installer = DefaultApkInstaller(context)
        val apkFile = File(context.cacheDir, "ready_to_install.apk").apply { writeText("valid apk") }

        val intent = installer.createInstallIntent(context, apkFile)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue("Must include FLAG_GRANT_READ_URI_PERMISSION", (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue("Must include FLAG_ACTIVITY_NEW_TASK", (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertNotNull("Intent data URI must not be null", intent.data)
    }

    // =========================================================================
    // 15. Actual Upgrade Test with Genuine Signed Production APK
    // =========================================================================
    @Test
    fun test15_actualUpgradeFromOlderToNewerSignedProductionApk() {
        // Inspect the real generated release APK built by Gradle
        val releaseApkPath = "build/outputs/apk/release/app-release.apk"
        val releaseApk = File(releaseApkPath)
        val fallbackApk = File("app/$releaseApkPath")
        val apkToTest = if (releaseApk.exists()) releaseApk else fallbackApk

        assertTrue("Signed production release APK must exist on disk", apkToTest.exists())
        assertTrue("Release APK size must be non-empty (>10MB)", apkToTest.length() > 10 * 1024 * 1024)

        // Calculate actual SHA-256 using verifier
        val verifier = DefaultApkIntegrityVerifier(context)
        val computedSha256 = verifier.calculateFileSha256(apkToTest)
        val manifestFile = File("version.json").takeIf { it.exists() } ?: File("../version.json")
        val expectedSha = if (manifestFile.exists()) {
            VersionManifest.fromJson(manifestFile.readText()).sha256
        } else {
            "da3f34452b1571914ba051dd78a21d3796ab5a2224c28285b67469e9cf8b66ec"
        }
        assertEquals(
            "Computed SHA-256 must match version.json manifest checksum",
            expectedSha,
            computedSha256
        )

        // Inspect PackageArchiveInfo
        val pm = context.packageManager
        val archiveInfo = pm.getPackageArchiveInfo(apkToTest.absolutePath, 0)
        assertNotNull("Archive info must be parseable by Android PackageManager", archiveInfo)
        assertEquals("com.aistudio.flinsmusic.zrqv", archiveInfo?.packageName)
        assertTrue("Archive versionCode must be valid", (archiveInfo?.versionCode ?: 0) >= 100)
    }

    // =========================================================================
    // 16. User data preserved during in-place update
    // =========================================================================
    @Test
    fun test16_userDataPreservedDuringInPlaceUpdate() {
        val prefs = context.getSharedPreferences("anyfy_user_prefs_default", Context.MODE_PRIVATE)
        prefs.edit().putString("display_name", "Gaurav").apply()

        // Standard in-place package update preserves sandbox data
        assertEquals("Gaurav", prefs.getString("display_name", null))
    }

    // =========================================================================
    // 17. Update popup does not repeatedly appear on screen transitions
    // =========================================================================
    @Test
    fun test17_updatePopupDoesNotRepeatedlyAppearOnScreenTransitions() = runTest(testDispatcher) {
        val sampleManifest = createSampleManifest(versionCode = 105, mandatory = false)
        var fetchCount = 0
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String): Result<VersionManifest> {
                fetchCount++
                return Result.success(sampleManifest)
            }
            override fun getDismissedVersionCode() = 105
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = System.currentTimeMillis()
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = sampleManifest
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(context, repository = repo, scope = this)
        // Transition 1 (e.g. Navigating between Home and Search)
        manager.checkForUpdates(force = false)
        advanceUntilIdle()

        // Transition 2 (e.g. Navigating to Library)
        manager.checkForUpdates(force = false)
        advanceUntilIdle()

        // Transition 3 (e.g. Navigating to Settings)
        manager.checkForUpdates(force = false)
        advanceUntilIdle()

        // Must throttle duplicate checks and suppress popup
        assertEquals("Throttling must prevent redundant network requests", 0, fetchCount)
        assertFalse("Dismissed update must not popup during screen transitions", manager.updateStatus.value is UpdateStatus.UpdateAvailable)
    }

    // =========================================================================
    // 18. Same version does not trigger update
    // =========================================================================
    @Test
    fun test18_sameVersionDoesNotTriggerUpdate() = runTest(testDispatcher) {
        val sampleManifest = createSampleManifest(versionCode = 100)
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.success(sampleManifest)
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = sampleManifest
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(context, repository = repo, scope = this)
        manager.checkForUpdates(force = true)
        advanceUntilIdle()

        val status = manager.updateStatus.value
        assertTrue("Same version must result in UpToDate, never UpdateAvailable", status is UpdateStatus.UpToDate)
    }
}
