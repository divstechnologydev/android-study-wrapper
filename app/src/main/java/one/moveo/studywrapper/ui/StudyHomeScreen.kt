package one.moveo.studywrapper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.R
import one.moveo.studycore.ActiveStudy
import one.moveo.studycore.Codes
import one.moveo.studywrapper.isDemo

/// Root state while a study is active (← iOS StudyHomeView.swift): status +
/// the entry point to the study browser + leave. Mirrors the extension popup
/// (brand kit v3): wordmark header, status bar with the live dot, study
/// head, origin chips. Orange = live tracking, nothing else.
@Composable
fun StudyHomeScreen(model: AppViewModel, study: ActiveStudy) {
    var confirmingLeave by remember { mutableStateOf(false) }
    var confirmingFinish by remember { mutableStateOf(false) }
    // No demo entry point from here: a study is already running, so the only
    // full-screen destinations are the explainer and (for a demo) the sample
    // results.
    var showHowItWorks by remember { mutableStateOf(false) }
    var showResults by remember { mutableStateOf(false) }
    val isDemo = study.isDemo

    if (showHowItWorks) {
        HowItWorksScreen(onDismiss = { showHowItWorks = false })
        return
    }
    if (showResults) {
        DemoResultsScreen(onDismiss = { showResults = false })
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Brand.bg)) {
        HomeHeader(
            onLeaveRequested = { confirmingLeave = true },
            onHowItWorks = { showHowItWorks = true },
        )
        StatusBar(isDemo = isDemo)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .readingColumn(regular = 720.dp)
                    .padding(16.dp)
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (isDemo) {
                    BrandNotice(
                        text = "This is a demo. It works exactly like a real study, except that nothing is recorded and no data leaves your device. Finish it whenever you like.",
                        background = Brand.infoBg,
                        foreground = Brand.infoText,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        study.config.study.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.text,
                    )
                    // The demo has no invitation code to quote back and no
                    // enrollment — showing either would invent facts.
                    if (!isDemo) {
                        Text(
                            "Code ${Codes.group(study.code)}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Brand.textMuted,
                        )
                    }
                    study.enrolledAt?.let { enrolledAt ->
                        Text(
                            "${if (isDemo) "Started" else "Enrolled"} ${enrolledFormatter.format(enrolledAt)}",
                            fontSize = 12.sp,
                            color = Brand.textMuted,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // "Tracked" would be a false statement about a site the
                    // participant picked themselves.
                    BrandEyebrow(if (isDemo) "Demo website" else "Tracked websites")
                    OriginChips(origins = study.config.tracking.origins)
                }

                BrandPrimaryButton(
                    text = "Open study browser",
                    onClick = { model.openBrowser() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )

                Text(
                    "Browse the study websites naturally — that's the whole task. When you're finished, tap Done in the browser to complete the study.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Brand.textSecondary,
                )

                // Reachable without finishing first: the results are the part a
                // prospective customer came to see.
                if (isDemo) {
                    BrandGhostButton(
                        text = "See sample results",
                        onClick = { showResults = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Extension popup `.finish` block: hairline, hint, button.
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(color = Brand.border, thickness = 1.dp)
                    Text(
                        "Done with the study tasks? Tap here to finish the study.",
                        modifier = Modifier.padding(top = 6.dp),
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = Brand.textSecondary,
                    )
                    BrandGhostButton(
                        text = "Finish study",
                        onClick = { confirmingFinish = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (confirmingFinish) {
        FinishStudyDialog(
            hasLeadOut = study.config.flow.leadOutUrl != null,
            onConfirm = {
                confirmingFinish = false
                model.finishStudy()
            },
            onDismiss = { confirmingFinish = false },
        )
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text("Leave this study?") },
            text = {
                Text("Tracking stops, the study is removed from this device, and your logins on the study websites are cleared. You can rejoin later with the same code.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLeave = false
                    model.leaveStudy()
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
}

/// Extension popup header: wordmark left, study menu right.
@Composable
private fun HomeHeader(onLeaveRequested: () -> Unit, onHowItWorks: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column {
        Box(
            modifier = Modifier.fillMaxWidth().background(Brand.bgElevated),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .readingColumn(regular = 720.dp)
                    .padding(horizontal = 16.dp)
                    .padding(top = 14.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_wordmark),
                    contentDescription = "Moveo One",
                    modifier = Modifier.height(17.dp),
                )
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "Study menu",
                            tint = Brand.textSecondary,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = Brand.bgElevated,
                    ) {
                        DropdownMenuItem(
                            text = { Text("How it works", color = Brand.text) },
                            onClick = {
                                menuOpen = false
                                onHowItWorks()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Leave study", color = Brand.dangerSolid) },
                            onClick = {
                                menuOpen = false
                                onLeaveRequested()
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = Brand.border, thickness = 1.dp)
    }
}

@Composable
private fun StatusBar(isDemo: Boolean) {
    Column {
        Box(
            modifier = Modifier.fillMaxWidth().background(Brand.bgChrome),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .readingColumn(regular = 720.dp)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The live dot means one thing only (orange = tracking is on), so
                // the demo must never borrow it — neutral, and not blinking.
                StatusDot(mode = if (isDemo) StatusDotMode.NEUTRAL else StatusDotMode.ACTIVE)
                Text(
                    if (isDemo) {
                        "Demo mode — nothing is being recorded"
                    } else {
                        "Tracking active on the study websites"
                    },
                    fontSize = 12.5.sp,
                    color = Brand.textSecondary,
                )
            }
        }
        HorizontalDivider(color = Brand.border, thickness = 1.dp)
    }
}

private val enrolledFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
