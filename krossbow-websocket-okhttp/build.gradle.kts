plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "A Krossbow adapter for OkHttp's WebSocket client"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        optIn.add("org.hildan.krossbow.io.InternalKrossbowIoApi")
    }
}

dependencies {
    api(projects.krossbowWebsocketCore)
    api(libs.okhttp)
    implementation(projects.krossbowIo)
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