package one.moveo.studywrapper

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.moveo.studywrapper.ui.Brand
import one.moveo.studywrapper.ui.BrandEyebrow
import one.moveo.studycore.BackendConstants

/// DEBUG-only settings (← iOS DebugSettingsView.swift) — the extension's
/// `apiBaseOverride` equivalent plus QA info. The whole file lives in the
/// debug source set and is absent from release builds. Sections for the
/// event spy (M5) and tag-health ping (M4) are added in their milestones.
@Composable
fun DebugSettingsScreen(model: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    var overrideText by remember { mutableStateOf(model.store.apiBaseOverride ?: "") }
    val mockBase = "http://10.0.2.2:8787/api/v1/extension-config"

    fun apply() {
        val trimmed = overrideText.trim()
        model.store.apiBaseOverride = trimmed.ifEmpty { null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brand.bg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Debug", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                apply()
                onDone()
            }) { Text("Done", color = Brand.text, fontWeight = FontWeight.SemiBold) }
        }

        BrandEyebrow("Config API base override")
        OutlinedTextField(
            value = overrideText,
            onValueChange = { overrideText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://…/api/v1/extension-config", fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { overrideText = mockBase }) {
                Text("Use local mock", fontSize = 13.sp, color = Brand.link)
            }
            TextButton(onClick = { overrideText = "" }) {
                Text("Use production", fontSize = 13.sp, color = Brand.dangerSolid)
            }
        }
        Text(
            "Empty = production (${BackendConstants.API_BASE}). Emulator reaches the host's mock-backend.sh via 10.0.2.2; physical device: use your Mac's LAN IP.",
            fontSize = 12.sp,
            color = Brand.textSecondary,
        )

        HorizontalDivider(color = Brand.border)

        BrandEyebrow("Effective API base")
        MonoValue(model.apiBase.toString())

        BrandEyebrow("Participant ID (backed-up prefs)")
        MonoValue(model.store.participantId())

        HorizontalDivider(color = Brand.border)

        BrandEyebrow("Tag health")
        val tagHost = model.tagInitializedHost.collectAsState().value
        MonoValue(tagHost?.let { "last init ping: $it" } ?: "— (open the browser on a study page)")

        HorizontalDivider(color = Brand.border)

        BrandEyebrow("Vendored tag")
        val vendor = remember { VendorInfo.load(context) }
        if (vendor != null) {
            MonoValue("source ${vendor.commitShort}${if (vendor.dirty) " (dirty)" else ""}")
            MonoValue("sha256 ${vendor.shaShort}")
        } else {
            Text(
                "moveo-one.js missing — run scripts/vendor-tag.sh",
                fontSize = 13.sp,
                color = Brand.dangerSolid,
            )
        }
    }
}

@Composable
private fun MonoValue(value: String) {
    Text(
        text = value,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = Brand.textSecondary,
    )
}

/// Reads VENDOR.json out of the app assets (written by scripts/vendor-tag.sh).
data class VendorInfo(val commitShort: String, val shaShort: String, val dirty: Boolean) {
    companion object {
        fun load(context: Context): VendorInfo? = try {
            context.assets.open("moveo-one.js").close()
            val json = context.assets.open("VENDOR.json").bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(json).jsonObject
            val commit = root["sourceCommit"]!!.jsonPrimitive.content
            val sha = root["files"]!!.jsonObject["moveo-one.js"]!!.jsonObject["sha256"]!!.jsonPrimitive.content
            VendorInfo(
                commitShort = commit.take(8),
                shaShort = sha.take(8),
                dirty = root["sourceDirty"]?.jsonPrimitive?.content == "true",
            )
        } catch (_: Exception) {
            null
        }
    }
}
