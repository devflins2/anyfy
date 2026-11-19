package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsTheme
import kotlinx.coroutines.launch

private const val GOOGLE_LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F%26feature%3D__FEATURE__&hl=en"

private const val CHROME_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLoginSheet(
    onDismiss: () -> Unit,
    onCookieCaptured: (cookie: String, onFinished: (Boolean, String) -> Unit) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var isVerifying by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Sign in with Google to sync your YouTube Music library") }
    var useManualInput by remember { mutableStateOf(false) }
    var manualCookieText by remember { mutableStateOf("") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141416),
        contentColor = Color(0xFFF0F0F0),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YouTube Music Authentication",
                        style = FlinsTheme.typography.title.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        color = Color(0xFFF0F0F0)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = statusMessage,
                        style = FlinsTheme.typography.metadata,
                        color = if (isVerifying) FlinsCyan else FlinsTheme.colors.textSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { useManualInput = !useManualInput },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (useManualInput) Icons.Default.Language else Icons.Default.ContentPaste,
                            contentDescription = "Toggle Manual Cookie Input",
                            tint = Color(0xFFBDBDBD)
                        )
                    }

                    if (!useManualInput) {
                        IconButton(
                            onClick = { webViewInstance?.reload() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload Page",
                                tint = Color(0xFFBDBDBD)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFFBDBDBD)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isVerifying) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFFE0E0E0), modifier = Modifier.size(44.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Verifying session with YouTube Music...",
                            style = FlinsTheme.typography.bodyMedium,
                            color = Color(0xFFF0F0F0)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Retrieving account profile, liked songs, and playlists",
                            style = FlinsTheme.typography.metadata,
                            color = FlinsTheme.colors.textMuted
                        )
                    }
                }
            } else if (useManualInput) {
                // Manual Cookie String Import (Equivalent to SimpMusic DevLogInBottomSheet)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 16.dp)
                ) {
                    Text(
                        text = "Paste Session Cookie",
                        style = FlinsTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFF0F0F0)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Paste cookies from music.youtube.com (containing SAPISID, SSID, HSID, SID).",
                        style = FlinsTheme.typography.metadata,
                        color = FlinsTheme.colors.textMuted
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = manualCookieText,
                        onValueChange = { manualCookieText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        placeholder = {
                            Text(
                                "SAPISID=...; __Secure-3PAPISID=...; SID=...",
                                style = FlinsTheme.typography.metadata,
                                color = Color(0xFF666666)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFBDBDBD),
                            unfocusedBorderColor = Color(0xFF383838),
                            focusedContainerColor = Color(0xFF1E1E22),
                            unfocusedContainerColor = Color(0xFF18181A),
                            focusedTextColor = Color(0xFFF0F0F0),
                            unfocusedTextColor = Color(0xFFE0E0E0)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    GlassButton(
                        onClick = {
                            if (manualCookieText.isNotBlank()) {
                                isVerifying = true
                                statusMessage = "Validating session cookies..."
                                onCookieCaptured(manualCookieText) { success, name ->
                                    isVerifying = false
                                    if (success) {
                                        coroutineScope.launch {
                                            sheetState.hide()
                                            onDismiss()
                                        }
                                    } else {
                                        statusMessage = "Validation failed: $name"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        variant = GlassButtonVariant.PRIMARY
                    ) {
                        Text(
                            text = "Import & Verify Session",
                            style = FlinsTheme.typography.button,
                            color = FlinsDarkBg
                        )
                    }
                }
            } else {
                // In-App Modern Android WebView
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF282828), RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                webViewInstance = this

                                @SuppressLint("SetJavaScriptEnabled")
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.userAgentString = CHROME_MOBILE_USER_AGENT

                                webChromeClient = WebChromeClient()

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        checkCookie(url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        checkCookie(url)
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val targetUrl = request?.url?.toString()
                                        checkCookie(targetUrl)
                                        return false
                                    }

                                    private fun checkCookie(currentUrl: String?) {
                                        if (currentUrl == null || isVerifying) return
                                        if (currentUrl.contains("music.youtube.com") || currentUrl.contains("youtube.com")) {
                                            val musicCookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                                            val ytCookie = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
                                            val mergedCookie = (musicCookie.split(";") + ytCookie.split(";"))
                                                .map { it.trim() }
                                                .filter { it.contains("=") }
                                                .distinctBy { it.substringBefore("=").trim() }
                                                .joinToString("; ")

                                            if (mergedCookie.contains("SAPISID") || mergedCookie.contains("__Secure-3PAPISID") || mergedCookie.contains("SID")) {
                                                isVerifying = true
                                                statusMessage = "Authenticating with YouTube Music..."
                                                onCookieCaptured(mergedCookie) { success, name ->
                                                    isVerifying = false
                                                    if (success) {
                                                        coroutineScope.launch {
                                                            sheetState.hide()
                                                            onDismiss()
                                                        }
                                                    } else {
                                                        statusMessage = "Authentication failed: $name"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                loadUrl(GOOGLE_LOGIN_URL)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

