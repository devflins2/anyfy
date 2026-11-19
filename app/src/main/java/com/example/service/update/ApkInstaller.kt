package com.example.service.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

interface ApkInstaller {
    fun canInstallPackages(): Boolean
    fun openInstallPermissionSettings(activity: Activity)
    fun installApk(activity: Activity, apkFile: File): Result<Unit>
    fun createInstallIntent(context: Context, apkFile: File): Intent
}

class DefaultApkInstaller(
    private val context: Context
) : ApkInstaller {

    override fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    override fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                activity.startActivity(intent)
            } catch (_: Exception) {
                // Fallback to general settings
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    override fun createInstallIntent(context: Context, apkFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = try {
            FileProvider.getUriForFile(context, authority, apkFile)
        } catch (_: Exception) {
            Uri.fromFile(apkFile)
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun installApk(activity: Activity, apkFile: File): Result<Unit> {
        if (!canInstallPackages()) {
            openInstallPermissionSettings(activity)
            return Result.failure(
                SecurityException("Permission to install unknown apps is required")
            )
        }

        return try {
            val intent = createInstallIntent(activity, apkFile)
            activity.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
