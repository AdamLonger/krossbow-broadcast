plugins {}

rootProject.name = "longbow"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

includeBuild("gradle/plugins")
include("krossbow-io")
include("krossbow-stomp-core")
include("krossbow-websocket-core")
include("krossbow-websocket-okhttp")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
