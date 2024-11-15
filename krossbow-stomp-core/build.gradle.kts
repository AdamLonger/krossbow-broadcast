plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "A Kotlin multiplatform STOMP client based on a generic web socket API"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        optIn.addAll(
            "org.hildan.krossbow.io.InternalKrossbowIoApi",
            "org.hildan.krossbow.stomp.headers.ExperimentalHeadersApi"
        )
    }
}

dependencies {
    api(projects.krossbowWebsocketCore)
    api(libs.kotlinx.io.bytestring)
    implementation(projects.krossbowIo)
    implementation(libs.kotlinx.coroutines.core)
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