plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "Internal IO utilities for kotlinx-io conversions"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.io.bytestring)
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
