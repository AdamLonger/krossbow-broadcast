plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "WebSocket client API used by the Krossbow STOMP client"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {

}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.io.bytestring)
    implementation(libs.kotlinx.io.core)
}

publishing {
    publications {
        create<MavenPublication>(name.removePrefix("krossbow-")) {
            groupId = extra.get("publishGroupId") as String
            artifactId = name.removePrefix("krossbow-")
            version = extra.get("publishVersion") as String

            from(components["java"])
        }
    }
}