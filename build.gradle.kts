plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.ysmsync"
version = "2.0.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("io.netty:netty-all:4.1.118.Final")
    implementation("com.github.luben:zstd-jni:1.5.7-5")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("YSMSync")
    relocate("com.github.luben.zstd", "com.ysmsync.lib.zstd")
}
