# Anyfy Production Release Automation Script
param(
    [string]$VersionName = "1.0.5",
    [int]$VersionCode = 105,
    [bool]$Mandatory = $false,
    [string]$Repo = "devflins2/anyfy"
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Anyfy Production Release Builder (v$VersionName, Build $VersionCode)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Check keystore
$KeystorePath = Join-Path $PSScriptRoot "..\my-upload-key.jks"
if (-not (Test-Path $KeystorePath)) {
    Write-Error "Production keystore not found at $KeystorePath. Aborting release build."
}

# Run Gradle assembleRelease
Write-Host "Building signed release APK..." -ForegroundColor Yellow
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Set-Location (Join-Path $PSScriptRoot "..")
& .\gradlew.bat assembleRelease --no-configuration-cache

$ReleaseApk = "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $ReleaseApk)) {
    Write-Error "Release APK build failed or output not found at $ReleaseApk"
}

# Calculate SHA-256
$HashResult = Get-FileHash -Path $ReleaseApk -Algorithm SHA256
$Sha256 = $HashResult.Hash.ToLower()

Write-Host "Release APK successfully built and signed!" -ForegroundColor Green
Write-Host "SHA-256: $Sha256" -ForegroundColor Green

# Copy to root Anyfy-release.apk
Copy-Item -Path $ReleaseApk -Destination "Anyfy-release.apk" -Force

# Generate version.json
$ManifestContent = @"
{
  "versionCode": $VersionCode,
  "versionName": "$VersionName",
  "apkUrl": "https://github.com/$Repo/releases/download/v$VersionName/Anyfy-release.apk",
  "sha256": "$Sha256",
  "releaseNotes": [
    "Automatic in-app update with SHA-256 & signature integrity verification",
    "Enhanced player banner resolution and dynamic wide album artwork",
    "Personalized Home greeting with persistent preferences",
    "Streamlined YouTube Music authentication"
  ],
  "mandatory": $($Mandatory.ToString().ToLower())
}
"@

$ManifestPath = "version.json"
Set-Content -Path $ManifestPath -Value $ManifestContent -Encoding UTF8
Write-Host "Generated $ManifestPath successfully." -ForegroundColor Green

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "GitHub Release Command:" -ForegroundColor Yellow
Write-Host "gh release create v$VersionName Anyfy-release.apk version.json --title `"v$VersionName`" --notes `"Release v$VersionName`"" -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor Cyan
