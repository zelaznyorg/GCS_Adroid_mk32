pluginManagement {
    repositories {
        google()
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

rootProject.name = "dron15-cockpit"
include(":cockpit")
// APK na kontroler DJI (dawny modul `:zrzut`) przeniesiony 2026-09-03 do repozytorium
// SmartGCS, katalog `dji/` — to soft dla innego urzadzenia niz MK32.
