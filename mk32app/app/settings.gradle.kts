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
// APK na kontroler DJI — osobny moduł, bo to inne urzadzenie i inne zadanie.
include(":zrzut")
