$ErrorActionPreference = "Stop"

# Get GitHub token from git credential helper
$cred = echo "protocol=https`nhost=github.com" | git credential fill
$token = ($cred | Where-Object { $_ -match "^password=" }) -replace "^password=",""
if (-not $token) {
    throw "GitHub token could not be retrieved from git credential helper."
}

$repo = "devflins2/anyfy"
$tag = "v1.0.0"
$releaseName = "Anyfy v1.0.0 — First Official Android Release"

$body = @"
# Anyfy v1.0.0 — First Official Android Release

Welcome to the first official production release of **Anyfy**, an open-source Android music streaming client integrated with YouTube Music!

---

### 🚀 Key Features & Highlights

- **YouTube Music Streaming & Search:** Direct search and streaming for tracks, albums, artists, and playlists using public YouTube Music InnerTube endpoints.
- **YouTube Music as Single Source of Truth:** Direct playlist creation, song addition, and library synchronization with your YouTube Music account.
- **Rich Artist & Album Views:** Complete discographies, top tracks, related artists, and full album tracklists with dynamic wide artwork.
- **On-Device Stream Extraction:** Powered by \`yt-dlp\` running directly on-device without third-party proxy dependencies.
- **AndroidX Media3 / ExoPlayer Audio Engine:** High-fidelity background audio, notification controls, lock-screen artwork, audio focus handling, and seamless queue management.
- **Together Listening Rooms:** Synchronized real-time group listening sessions powered by a Node.js + Socket.IO backend.
- **Personalized Home Feed:** Dynamic time-of-day greeting ("Good morning", "Good afternoon", "Good evening") with persistent user preferences.
- **Smart Cache Layer:** Room DB cache-first architecture with background refresh for instantaneous UI loads.
- **Automatic In-App Updates:** Integrated APK download and verification system that fetches version manifests from GitHub Releases with SHA-256 integrity checks.
- **Monochrome Grey UI:** Minimalist, elegant dark aesthetic styled with Jetpack Compose Material 3.

---

### 📦 Release Asset Specifications & Integrity

| Property | Details |
| :--- | :--- |
| **APK Filename** | \`Anyfy-release.apk\` |
| **Package ID** | \`com.aistudio.flinsmusic.zrqv\` |
| **Version Name** | \`1.0.0\` |
| **Version Code** | \`100\` |
| **Target SDK / Compile SDK** | \`36\` (Android 16) |
| **Minimum SDK** | \`26\` (Android 8.0 Oreo) |
| **File Size** | \`74,655,108 bytes\` (~71.20 MB) |
| **Signing Scheme** | \`APK Signature Scheme v2 (RSA 2048-bit)\` |
| **Certificate Subject** | \`CN=Anyfy Production, OU=Mobile, O=Anyfy Audio, L=San Francisco, ST=California, C=US\` |
| **Certificate SHA-256 Digest** | \`7384511661e97d452a003dca9806564560a7428e79af126399f7c92203a38ed5\` |
| **Verified APK SHA-256** | \`c22f20174fb1efb1c379b8abbc6954bb902542d4167b3956397576eee8b5c94c\` |

---

### 📲 Installation Instructions

1. Download **\`Anyfy-release.apk\`** below.
2. Tap the downloaded APK to install.
3. If prompted by Android, grant **"Install unknown apps"** permission for your browser or file manager.
4. Launch Anyfy and enjoy distraction-free music streaming!
"@

$headers = @{
    "Authorization" = "Bearer $token"
    "Accept" = "application/vnd.github+json"
    "User-Agent" = "Anyfy-Release-Agent"
}

# 1. Check if release already exists
Write-Host "Checking if release for tag $tag exists..." -ForegroundColor Cyan
$release = $null
try {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$tag" -Headers $headers -Method Get
    Write-Host "Found existing release: $($release.id)" -ForegroundColor Yellow
} catch {
    Write-Host "Release for $tag does not exist. Creating new release..." -ForegroundColor Cyan
}

if (-not $release) {
    $releasePayload = @{
        tag_name = $tag
        target_commitish = "main"
        name = $releaseName
        body = $body
        draft = $false
        prerelease = $false
        make_latest = "true"
    } | ConvertTo-Json

    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Headers $headers -Method Post -Body $releasePayload
    Write-Host "Created release ID: $($release.id)" -ForegroundColor Green
    Write-Host "Release URL: $($release.html_url)" -ForegroundColor Green
}

$releaseId = $release.id
$uploadBaseUrl = "https://uploads.github.com/repos/$repo/releases/$releaseId/assets"

# 2. Upload Anyfy-release.apk
$apkPath = "app/build/outputs/apk/release/app-release.apk"
if (-not (Test-Path $apkPath)) {
    $apkPath = "Anyfy-release.apk"
}

$apkName = "Anyfy-release.apk"
Write-Host "Uploading $apkName ($(Get-Item $apkPath | Select-Object -ExpandProperty Length) bytes)..." -ForegroundColor Cyan

# Check if asset already exists in release
$existingAssets = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/$releaseId/assets" -Headers $headers -Method Get
$existingApk = $existingAssets | Where-Object { $_.name -eq $apkName }
if ($existingApk) {
    Write-Host "Deleting existing asset $($existingApk.id)..." -ForegroundColor Yellow
    Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/assets/$($existingApk.id)" -Headers $headers -Method Delete
}

$uploadApkUri = "$uploadBaseUrl?name=$apkName"
$apkBytes = [System.IO.File]::ReadAllBytes((Resolve-Path $apkPath).Path)
$apkUploadHeaders = @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/vnd.android.package-archive"
    "User-Agent" = "Anyfy-Release-Agent"
}
$apkAsset = Invoke-RestMethod -Uri $uploadApkUri -Headers $apkUploadHeaders -Method Post -Body $apkBytes
Write-Host "Uploaded $apkName successfully! ID: $($apkAsset.id), Download URL: $($apkAsset.browser_download_url)" -ForegroundColor Green

# 3. Upload version.json
$manifestPath = "version.json"
$manifestName = "version.json"
Write-Host "Uploading $manifestName..." -ForegroundColor Cyan

$existingManifest = $existingAssets | Where-Object { $_.name -eq $manifestName }
if ($existingManifest) {
    Write-Host "Deleting existing manifest asset $($existingManifest.id)..." -ForegroundColor Yellow
    Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/assets/$($existingManifest.id)" -Headers $headers -Method Delete
}

$uploadManifestUri = "$uploadBaseUrl?name=$manifestName"
$manifestBytes = [System.IO.File]::ReadAllBytes((Resolve-Path $manifestPath).Path)
$manifestUploadHeaders = @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/json"
    "User-Agent" = "Anyfy-Release-Agent"
}
$manifestAsset = Invoke-RestMethod -Uri $uploadManifestUri -Headers $manifestUploadHeaders -Method Post -Body $manifestBytes
Write-Host "Uploaded $manifestName successfully! ID: $($manifestAsset.id), Download URL: $($manifestAsset.browser_download_url)" -ForegroundColor Green

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "GitHub Release successfully published!" -ForegroundColor Green
Write-Host "Release Page: $($release.html_url)" -ForegroundColor Green
Write-Host "APK Download URL: $($apkAsset.browser_download_url)" -ForegroundColor Green
Write-Host "Manifest URL: $($manifestAsset.browser_download_url)" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
