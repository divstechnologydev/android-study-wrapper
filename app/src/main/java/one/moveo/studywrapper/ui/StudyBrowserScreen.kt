package one.moveo.studywrapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.DebugHooks
import one.moveo.studywrapper.StoreSupport
import one.moveo.studywrapper.browser.LeadSurveyLauncher
import one.moveo.studywrapper.browser.StudyWebViewController
import one.moveo.studywrapper.browser.isWebViewSupported
import one.moveo.studycore.ActiveStudy

/// Screen 3 of 3: the study browser (← iOS StudyBrowserView.swift) — one
/// WebView with minimal chrome (Done, back, reload, study menu). Not a
/// general browser: no address bar, no tabs; it opens on the study's start
/// origin (a0.1).
@Composable
fun StudyBrowserScreen(model: AppViewModel, study: ActiveStudy) {
    if (!isWebViewSupported()) {
        WebViewUpdateGate(model)
        return
    }

    val context = LocalContext.current
    var confirmingLeave by remember { mutableStateOf(false) }
    var showingGoTo by remember { mutableStateOf(false) }
    val controller = remember { StudyWebViewController(context, model) }

    DisposableEffect(Unit) {
        onDispose { controller.destroy() }
    }

    // System back: page history first, else close the browser screen.
    BackHandler {
        if (!controller.goBackIfPossible()) model.closeBrowser()
    }

    Column(modifier = Modifier.fillMaxSize().background(Brand.bg)) {
        // Chrome mirrors iOS: Done, back | title | reload, menu. No address bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brand.bgElevated)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { model.closeBrowser() }) {
                Text("Done", color = Brand.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            IconButton(onClick = { controller.goBackIfPossible() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = Brand.textSecondary,
                )
            }
            Text(
                text = study.config.study.name,
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { controller.reload() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Reload", tint = Brand.textSecondary)
            }
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Study menu", tint = Brand.textSecondary)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = Brand.bgElevated,
                ) {
                    DropdownMenuItem(
                        text = { Text("Leave study", color = Brand.dangerSolid) },
                        onClick = {
                            menuOpen = false
                            confirmingLeave = true
                        },
                    )
                    if (DebugHooks.isDebugBuild) {
                        DropdownMenuItem(
                            text = { Text("Go to URL (debug)", color = Brand.textSecondary) },
                            onClick = {
                                menuOpen = false
                                showingGoTo = true
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = Brand.border, thickness = 1.dp)

        AndroidView(
            factory = { controller.createWebView() },
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text("Leave this study?") },
            text = {
                Text("Tracking stops, the study is removed from this device, and your logins on the study websites are cleared.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLeave = false
                    model.leaveStudyAndClearData()
                }) { Text("Leave", color = Brand.dangerSolid) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) {
                    Text("Cancel", color = Brand.textSecondary)
                }
            },
            containerColor = Brand.bgElevated,
            titleContentColor = Brand.text,
            textContentColor = Brand.textSecondary,
        )
    }

    if (showingGoTo) {
        var goToText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showingGoTo = false },
            title = { Text("Go to URL") },
            text = {
                OutlinedTextField(
                    value = goToText,
                    onValueChange = { goToText = it },
                    placeholder = { Text("https://…", fontSize = 13.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    ),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showingGoTo = false
                    if (goToText.isNotBlank()) controller.load(goToText.trim())
                }) { Text("Go", color = Brand.text) }
            },
            dismissButton = {
                TextButton(onClick = { showingGoTo = false }) {
                    Text("Cancel", color = Brand.textSecondary)
                }
            },
            containerColor = Brand.bgElevated,
            titleContentColor = Brand.text,
        )
    }
}

/// §a0.3: no fallback injection path. evaluateJavascript-at-onPageStarted is
/// racy (misses the tag's synchronous early events) and would silently
/// produce non-parity data — worse than refusing. Wording and the optional
/// store link come from the per-flavor StoreSupport (§h1) — the huawei
/// binary must not link to Google Play.
@Composable
private fun WebViewUpdateGate(model: AppViewModel) {
    val context = LocalContext.current
    AuthPage {
        Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                StoreSupport.webViewUpdateTitle,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.text,
            )
            BrandNotice(
                text = StoreSupport.webViewUpdateNotice,
                background = Brand.infoBg,
                foreground = Brand.infoText,
            )
            val actionLabel = StoreSupport.webViewUpdateActionLabel
            val actionUrl = StoreSupport.webViewUpdateActionUrl
            if (actionLabel != null && actionUrl != null) {
                BrandPrimaryButton(
                    text = actionLabel,
                    onClick = { LeadSurveyLauncher.launch(context, actionUrl) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BrandGhostButton(
                text = "Close",
                onClick = { model.closeBrowser() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
