rootProject.name = "smartfollow"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(
    ":app",
    ":core-model",
    ":alarm-engine",
    ":data-share",
    ":monitor",
    ":server",
    ":sync",
    ":tools:share-probe",
)
