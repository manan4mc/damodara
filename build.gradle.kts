import java.util.Properties

plugins {
    `java-library`
}

group = "com.damodar"
version = "1.0.0-SNAPSHOT"
description = "Damodara - Automated, safe, scheduled Minecraft server backups with Google Drive off-site cloud sync"

java {
    // Target Java 25 as required by Paper 26.2
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    // PaperMC maven repository
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Target Paper 26.2 using modern Paper dependency coordinate format
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")

    // Compile-time annotations
    compileOnly("org.jetbrains:annotations:24.1.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Paper API on test classpath for config-parsing tests (YamlConfiguration)
    testImplementation("io.papermc.paper:paper-api:26.2.build.135-stable")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// Load developer OAuth credentials from untracked oauth.properties if present
val oauthPropsFile = file("oauth.properties")
val oauthProps = Properties()
if (oauthPropsFile.exists()) {
    oauthPropsFile.inputStream().use { oauthProps.load(it) }
}

val oauthClientId: String = (project.findProperty("damodara.oauth.clientId") as? String)
    ?: oauthProps.getProperty("damodara.oauth.clientId")
    ?: System.getenv("DAMODARA_OAUTH_CLIENT_ID")
    ?: ""

val oauthClientSecret: String = (project.findProperty("damodara.oauth.clientSecret") as? String)
    ?: oauthProps.getProperty("damodara.oauth.clientSecret")
    ?: System.getenv("DAMODARA_OAUTH_CLIENT_SECRET")
    ?: ""

tasks.withType<ProcessResources>().configureEach {
    val props = mapOf(
        "version" to version,
        "description" to description,
        "apiVersion" to "26.2",
        "oauthClientId" to oauthClientId,
        "oauthClientSecret" to oauthClientSecret
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
    filesMatching("damodara-oauth.properties") {
        expand(props)
    }
}
