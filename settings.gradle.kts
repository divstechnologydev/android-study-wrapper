pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-study-wrapper"

// :app        — Android application (Compose UI, WebView shell)
// :studycore  — pure Kotlin JVM module: ALL contract logic, tested on JVM
//               (the analogue of the iOS StudyKit package; see docs/plan.md §0.1)
include(":app")
include(":studycore")
