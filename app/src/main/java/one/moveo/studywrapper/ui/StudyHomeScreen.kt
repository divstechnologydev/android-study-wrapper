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

/// Root state while a study is active (← iOS StudyHomeView.swift): status +
/// the entry point to the study browser + leave. Mirrors the extension popup
/// (brand kit v3): wordmark header, status bar with the live dot, study
/// head, origin chips. Orange = live tracking, nothing else.
@Composable
fun StudyHomeScreen(model: AppViewModel, study: ActiveStudy) {
    var confirmingLeave by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Brand.bg)) {
        HomeHeader(onLeaveRequested = { confirmingLeave = true })
        StatusBar()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    study.config.study.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.text,
                )
                Text(
                    "Code ${Codes.group(study.code)}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Brand.textMuted,
                )
                study.enrolledAt?.let { enrolledAt ->
                    Text(
                        "Enrolled ${enrolledFormatter.format(enrolledAt)}",
                        fontSize = 12.sp,
                        color = Brand.textMuted,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BrandEyebrow("Tracked websites")
                OriginChips(origins = study.config.tracking.origins)
            }

            BrandPrimaryButton(
                text = "Open study browser",
                onClick = { model.openBrowser() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            Text(
                "Browse the study websites naturally — that's the whole task. You can come back here any time with Done.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Brand.textSecondary,
            )
        }
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
private fun HomeHeader(onLeaveRequested: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brand.bgElevated)
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
                        text = { Text("Leave study", color = Brand.dangerSolid) },
                        onClick = {
                            menuOpen = false
                            onLeaveRequested()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = Brand.border, thickness = 1.dp)
    }
}

@Composable
private fun StatusBar() {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brand.bgChrome)
                .padding(horizontal = 16.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(mode = StatusDotMode.ACTIVE)
            Text(
                "Tracking active on the study websites",
                fontSize = 12.5.sp,
                color = Brand.textSecondary,
            )
        }
        HorizontalDivider(color = Brand.border, thickness = 1.dp)
    }
}

private val enrolledFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
