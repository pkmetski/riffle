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
        maven {
            url = uri("https://jitpack.io")
            // readium-adapter-pdfium-document pulls its PDF engine from this JitPack fork.
            content { includeGroup("com.github.marain87") }
        }
    }
}

rootProject.name = "riffle"

include(":app")
include(":core:domain")
include(":core:network")
include(":core:database")
include(":core:data")
include(":core:pdfium-text")
