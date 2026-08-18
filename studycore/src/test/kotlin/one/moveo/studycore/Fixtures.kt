package one.moveo.studycore

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/// Shared fixture access for the test suite — the vendored extension fixture
/// suite lives on the test classpath (populated by scripts/vendor-tag.sh).
object Fixtures {
    fun dir(): File {
        val url = checkNotNull(Fixtures::class.java.getResource("/fixtures")) {
            "fixtures directory missing — run scripts/vendor-tag.sh"
        }
        return File(url.toURI())
    }

    fun text(name: String): String = File(dir(), name).readText()

    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject

    /// Repo root, located from the test working directory (the module dir) —
    /// used by tests that verify on-disk vendored artifacts.
    fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("repo root (settings.gradle.kts) not found above ${System.getProperty("user.dir")}")
    }
}
