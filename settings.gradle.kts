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
        // ffmpeg-kit Maven repo — enable when integrating ffmpeg-kit
        // maven { url = uri("https://maven.pkg.github.com/arthenica/ffmpeg-kit") }
    }
}

rootProject.name = "UpCap"
include(":app")
