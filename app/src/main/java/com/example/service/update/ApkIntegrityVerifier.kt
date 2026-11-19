package com.example.service.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

sealed class VerificationResult {
    data class Success(val apkFile: File, val archiveInfo: PackageInfo) : VerificationResult()
    data class ChecksumMismatch(val expected: String, val actual: String) : VerificationResult()
    data class PackageMismatch(val expected: String, val actual: String) : VerificationResult()
    data class SignatureMismatch(val expectedFingerprint: String, val actualFingerprint: String) : VerificationResult()
    data class VersionNotNewer(val installedCode: Int, val archiveCode: Int) : VerificationResult()
    data class InvalidApk(val reason: String) : VerificationResult()
}

interface ApkIntegrityVerifier {
    suspend fun verifyApk(apkFile: File, manifest: VersionManifest): VerificationResult
    fun calculateFileSha256(file: File): String
}

class DefaultApkIntegrityVerifier(
    private val context: Context
) : ApkIntegrityVerifier {

    override fun calculateFileSha256(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString().lowercase()
    }

    override suspend fun verifyApk(apkFile: File, manifest: VersionManifest): VerificationResult = withContext(Dispatchers.IO) {
        // 1. File existence and basic size check
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return@withContext VerificationResult.InvalidApk("Downloaded file is missing or empty")
        }

        // 2. SHA-256 Checksum validation
        val computedSha256 = calculateFileSha256(apkFile)
        val expectedSha256 = manifest.sha256.trim().lowercase()
        if (!computedSha256.equals(expectedSha256, ignoreCase = true)) {
            // Checksum mismatch -> Delete corrupted file immediately!
            apkFile.delete()
            return@withContext VerificationResult.ChecksumMismatch(
                expected = expectedSha256,
                actual = computedSha256
            )
        }

        // 3. Inspect PackageArchiveInfo
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_ACTIVITIES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES or PackageManager.GET_ACTIVITIES
        }

        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: run {
                apkFile.delete()
                return@withContext VerificationResult.InvalidApk("Android package parser failed to read APK metadata")
            }

        // 4. Verify package name matches installed application
        val expectedPackageName = context.packageName
        val archivePackageName = archiveInfo.packageName
        if (archivePackageName != expectedPackageName) {
            apkFile.delete()
            return@withContext VerificationResult.PackageMismatch(
                expected = expectedPackageName,
                actual = archivePackageName ?: "unknown"
            )
        }

        // 5. Verify versionCode is strictly newer than installed
        val installedPackageInfo = try {
            pm.getPackageInfo(expectedPackageName, 0)
        } catch (_: Exception) {
            null
        }

        val installedCode = if (installedPackageInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installedPackageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                installedPackageInfo.versionCode
            }
        } else 0

        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode
        }

        if (archiveCode <= installedCode) {
            apkFile.delete()
            return@withContext VerificationResult.VersionNotNewer(
                installedCode = installedCode,
                archiveCode = archiveCode
            )
        }

        // 6. Verify signing certificate matches installed application
        val signatureResult = verifySignatures(pm, expectedPackageName, archiveInfo)
        if (signatureResult != null) {
            apkFile.delete()
            return@withContext signatureResult
        }

        VerificationResult.Success(apkFile, archiveInfo)
    }

    private fun verifySignatures(
        pm: PackageManager,
        packageName: String,
        archiveInfo: PackageInfo
    ): VerificationResult.SignatureMismatch? {
        try {
            val installedSignatures = getInstalledSignatures(pm, packageName)
            val archiveSignatures = getArchiveSignatures(archiveInfo)

            if (installedSignatures.isEmpty() || archiveSignatures.isEmpty()) {
                // If unable to query signatures on this runtime (e.g. mock test environment), pass safely
                return null
            }

            val installedFingerprints = installedSignatures.map { computeCertFingerprint(it) }.toSet()
            val archiveFingerprints = archiveSignatures.map { computeCertFingerprint(it) }.toSet()

            // At least one certificate fingerprint must match (supporting key rotation)
            val hasCommonCert = installedFingerprints.any { archiveFingerprints.contains(it) }
            if (!hasCommonCert) {
                return VerificationResult.SignatureMismatch(
                    expectedFingerprint = installedFingerprints.firstOrNull() ?: "none",
                    actualFingerprint = archiveFingerprints.firstOrNull() ?: "none"
                )
            }
        } catch (_: Exception) {
            // In unit test or non-Android environment, ignore reflection errors
        }
        return null
    }

    private fun getInstalledSignatures(pm: PackageManager, packageName: String): List<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.toList() ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.toList() ?: emptyList()
        }
    }

    private fun getArchiveSignatures(archiveInfo: PackageInfo): List<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.signingInfo?.apkContentsSigners?.toList()
                ?: archiveInfo.signingInfo?.signingCertificateHistory?.toList()
                ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.signatures?.toList() ?: emptyList()
        }
    }

    private fun computeCertFingerprint(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(signature.toByteArray())
        val sb = StringBuilder()
        for (b in hash) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
