// Windows app: its own build (it doesn't need the Android SDK); the engine code is shared with ../app
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "netino-desktop"
