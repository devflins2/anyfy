package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.example.service.update.ApkDownloadManager
import com.example.service.update.ApkInstaller
import com.example.service.update.ApkIntegrityVerifier
import com.example.service.update.AppUpdateManager
import com.example.service.update.DefaultUpdateRepository
import com.example.service.update.UpdateError
import com.example.service.update.UpdateRepository
import com.example.service.update.UpdateStatus
import com.example.service.update.VerificationResult
import com.example.service.update.VersionManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateDetectionProductionVerificationTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    private val productionManifest101 = VersionManifest(
        versionCode = 101,
        versionName = "1.0.1",
        apkUrl = "https://github.com/devflins2/anyfy/releases/download/v1.0.1/Anyfy-release.apk",
        sha256 = "da3f34452b1571914ba051dd78a21d3796ab5a2224c28285b67469e9cf8b66ec",
        releaseNotes = listOf(
            "YouTube Music two-way playlist synchronization & remote write-back",
            "Fixed Together Listening playlist import overlay navigation bug",
            "Optimized storage footprint: ~50% APK size reduction (~37.9 MB) with R8 shrinking",
            "Fixed duplicate YouTube Music integration entry in Settings screen",
            "Fixed notification timeline scrubber freezing & instant replay for completed songs"
        ),
        mandatory = false
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("anyfy_update_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Installed Build 100 -> Remote Build 101: Popup MUST appear
    // =========================================================================
    @Test
    fun test01_installedBuild100_remoteBuild101_popupMustAppear() = runTest(testDispatcher) {
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.success(productionManifest101)
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = null
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(
            context = context,
            repository = repo,
            scope = this,
            installedVersionCodeOverride = 100,
            installedVersionNameOverride = "1.0.0"
        )

        manager.checkForUpdates(force = true)
        advanceUntilIdle()

        val status = manager.updateStatus.value
        assertTrue("Installed Build 100 must detect remote Build 101 as UpdateAvailable", status is UpdateStatus.UpdateAvailable)
        val available = status as UpdateStatus.UpdateAvailable
        assertEquals(101, available.manifest.versionCode)
        assertEquals("1.0.1", available.manifest.versionName)
        assertEquals("da3f34452b1571914ba051dd78a21d3796ab5a2224c28285b67469e9cf8b66ec", available.manifest.sha256)
        assertFalse("Update must not be dismissed", available.isDismissed)
    }

    // =========================================================================
    // 2. Installed Build 101 -> Remote Build 101: No popup (UpToDate)
    // =========================================================================
    @Test
    fun test02_installedBuild101_remoteBuild101_noPopup() = runTest(testDispatcher) {
        val manifestSame = productionManifest101.copy(versionCode = 101)
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.success(manifestSame)
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = null
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(
            context = context,
            repository = repo,
            scope = this,
            installedVersionCodeOverride = 101,
            installedVersionNameOverride = "1.0.1"
        )
        manager.checkForUpdates(force = true)
        advanceUntilIdle()

        val status = manager.updateStatus.value
        assertTrue("When installed version >= remote, status must be UpToDate", status is UpdateStatus.UpToDate)
        assertFalse("Must never show update popup when already up to date", status is UpdateStatus.UpdateAvailable)
    }

    // =========================================================================
    // 3. Installed Build 100 with no internet: App continues working safely
    // =========================================================================
    @Test
    fun test03_installedBuild100_noInternet_appContinuesWorkingSafely() = runTest(testDispatcher) {
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) =
                Result.failure<VersionManifest>(IllegalStateException("Network offline"))
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = null
            override fun isNetworkAvailable() = false
        }

        val manager = AppUpdateManager(context, repository = repo, scope = this)
        manager.checkForUpdates(force = false)
        advanceUntilIdle()

        val status = manager.updateStatus.value
        assertTrue("When network is unavailable, status should transition safely to Failed without crashing", status is UpdateStatus.Failed)
        val failed = status as UpdateStatus.Failed
        assertEquals(UpdateError.NETWORK_UNAVAILABLE, failed.error)
        assertNull("Failed status must not carry a valid manifest when network is offline", failed.manifest)
    }

    // =========================================================================
    // 4. Manifest unavailable (HTTP 500 / 404): No false update notification
    // =========================================================================
    @Test
    fun test04_manifestUnavailable_noFalseUpdateNotification() = runTest(testDispatcher) {
        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) =
                Result.failure<VersionManifest>(IllegalStateException("Server returned HTTP 404 from $manifestUrl"))
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
        assertFalse("Must NEVER show UpdateAvailable when manifest is unavailable", status is UpdateStatus.UpdateAvailable)
        assertTrue(status is UpdateStatus.Failed)
        assertNull("Manifest must be null when manifest fetch fails", (status as UpdateStatus.Failed).manifest)
    }

    // =========================================================================
    // 5. Download failure: Provides retry and preserves manifest
    // =========================================================================
    @Test
    fun test05_downloadFailure_providesRetryAndPreservesManifest() = runTest(testDispatcher) {
        var downloadAttempts = 0
        val downloadManager = object : ApkDownloadManager {
            override suspend fun downloadApk(manifest: VersionManifest, onProgress: (Long, Long, Int, Long) -> Unit): Result<File> {
                downloadAttempts++
                return if (downloadAttempts == 1) {
                    Result.failure(java.io.IOException("Connection reset by peer"))
                } else {
                    val dummyFile = File(context.cacheDir, "test_success.apk").apply { writeText("dummy content") }
                    Result.success(dummyFile)
                }
            }
            override fun cancelDownload() {}
            override fun getExistingDownloadedApk(manifest: VersionManifest): File? = null
            override fun deleteDownloadedApk(manifest: VersionManifest) {}
        }

        val verifier = object : ApkIntegrityVerifier {
            override suspend fun verifyApk(apkFile: File, manifest: VersionManifest): VerificationResult {
                val dummyInfo = PackageInfo().apply { packageName = context.packageName }
                return VerificationResult.Success(apkFile, dummyInfo)
            }
            override fun calculateFileSha256(file: File) = productionManifest101.sha256
        }

        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.success(productionManifest101)
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = productionManifest101
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(
            context = context,
            repository = repo,
            downloadManager = downloadManager,
            integrityVerifier = verifier,
            scope = this
        )
        manager.startDownload(productionManifest101)
        advanceUntilIdle()

        // First attempt fails
        val failedStatus = manager.updateStatus.value
        assertTrue("Must transition to Failed on download error", failedStatus is UpdateStatus.Failed)
        val failed = failedStatus as UpdateStatus.Failed
        assertEquals(UpdateError.DOWNLOAD_FAILED, failed.error)
        assertTrue("Download failure must allow user retry", failed.canRetry)
        assertNotNull("Manifest must be preserved in Failed state so dialog can offer retry", failed.manifest)

        // Retry attempt
        manager.startDownload()
        advanceUntilIdle()

        assertEquals("DownloadManager should have received 2 attempts", 2, downloadAttempts)
        assertTrue("Retry should succeed and transition to ReadyToInstall",
            manager.updateStatus.value is UpdateStatus.ReadyToInstall)
    }

    // =========================================================================
    // 6. Valid APK download: Verifies SHA-256 and installation flow
    // =========================================================================
    @Test
    fun test06_validApkDownload_verifiesSha256AndInstallationFlow() = runTest(testDispatcher) {
        val dummyApk = File(context.cacheDir, "verified_release.apk").apply {
            writeBytes("Production APK Binary Content".toByteArray())
        }
        val expectedHash = productionManifest101.sha256

        val verifier = object : ApkIntegrityVerifier {
            override suspend fun verifyApk(apkFile: File, manifest: VersionManifest): VerificationResult {
                return if (manifest.sha256 == expectedHash) {
                    val dummyInfo = PackageInfo().apply { packageName = context.packageName }
                    VerificationResult.Success(apkFile, dummyInfo)
                } else {
                    VerificationResult.ChecksumMismatch(expected = expectedHash, actual = "bad_hash")
                }
            }
            override fun calculateFileSha256(file: File) = expectedHash
        }

        val downloadManager = object : ApkDownloadManager {
            override suspend fun downloadApk(manifest: VersionManifest, onProgress: (Long, Long, Int, Long) -> Unit): Result<File> =
                Result.success(dummyApk)
            override fun cancelDownload() {}
            override fun getExistingDownloadedApk(manifest: VersionManifest): File? = null
            override fun deleteDownloadedApk(manifest: VersionManifest) {}
        }

        var installLaunched = false
        val installer = object : ApkInstaller {
            override fun canInstallPackages() = true
            override fun openInstallPermissionSettings(activity: Activity) {}
            override fun installApk(activity: Activity, apkFile: File): Result<Unit> {
                installLaunched = true
                return Result.success(Unit)
            }
            override fun createInstallIntent(context: Context, apkFile: File) = Intent(Intent.ACTION_VIEW)
        }

        val repo = object : UpdateRepository {
            override suspend fun fetchManifest(manifestUrl: String) = Result.success(productionManifest101)
            override fun getDismissedVersionCode() = -1
            override fun setDismissedVersionCode(versionCode: Int) {}
            override fun getLastCheckTimestamp() = 0L
            override fun setLastCheckTimestamp(timestamp: Long) {}
            override fun getCachedManifest(): VersionManifest? = productionManifest101
            override fun isNetworkAvailable() = true
        }

        val manager = AppUpdateManager(
            context = context,
            repository = repo,
            downloadManager = downloadManager,
            integrityVerifier = verifier,
            installer = installer,
            scope = this
        )

        manager.startDownload(productionManifest101)
        advanceUntilIdle()

        val readyStatus = manager.updateStatus.value
        assertTrue("Valid download with matching SHA-256 must transition to ReadyToInstall", readyStatus is UpdateStatus.ReadyToInstall)
        val ready = readyStatus as UpdateStatus.ReadyToInstall
        assertEquals(productionManifest101.versionCode, ready.manifest.versionCode)
        assertEquals(dummyApk.absolutePath, ready.apkFile.absolutePath)

        // Trigger installation
        val mockActivity = org.robolectric.Robolectric.buildActivity(Activity::class.java).setup().get()
        manager.installUpdate(mockActivity)
        assertTrue("Package installation flow must be launched with user consent", installLaunched)
    }

    // =========================================================================
    // 7. Resilient Fallback URLs: Primary fails, secondary succeeds
    // =========================================================================
    @Test
    fun test07_defaultUpdateRepository_fallbackUrls_resilientToPrimaryFailure() = runTest(testDispatcher) {
        val mockHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            if (url.contains("raw.githubusercontent.com")) {
                // Primary fails with HTTP 503 Service Unavailable
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                // Fallback GitHub Releases URL succeeds with 101 manifest
                val manifestJson = """
                {
                  "versionCode": 101,
                  "versionName": "1.0.1",
                  "apkUrl": "https://github.com/devflins2/anyfy/releases/download/v1.0.1/Anyfy-release.apk",
                  "sha256": "da3f34452b1571914ba051dd78a21d3796ab5a2224c28285b67469e9cf8b66ec",
                  "releaseNotes": ["Test release notes"],
                  "mandatory": false
                }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(manifestJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }.build()

        val repository = DefaultUpdateRepository(context = null, client = mockHttpClient)
        val result = repository.fetchManifest("https://raw.githubusercontent.com/devflins2/anyfy/main/version.json")
        if (result.isFailure) {
            println("TEST07 EXCEPTION: " + result.exceptionOrNull()?.message)
            result.exceptionOrNull()?.printStackTrace()
        }
        assertTrue("Fallback endpoint must recover from primary endpoint failure: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val manifest = result.getOrThrow()
        assertEquals(101, manifest.versionCode)
        assertEquals("1.0.1", manifest.versionName)
    }
}
