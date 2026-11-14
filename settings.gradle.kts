pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
include(":app")
include(":rootd")
include(":sharedd")
include(":hayulcore", ":hayulstub", ":setup")
project(":hayulcore").projectDir = file("vendor/HayulGUI/core")
project(":hayulcore").buildFileName = "starlight.gradle.kts"
project(":hayulstub").projectDir = file("vendor/HayulGUI/stub")
project(":hayulstub").buildFileName = "starlight.gradle.kts"
rootProject.name = "StarlightKakaoDB"
