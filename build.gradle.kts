plugins {
    java
}

group = "cn.frostcraft"
version = "1.0.0"

repositories {
    mavenCentral()

    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }

    maven("https://repo.codemc.io/repository/maven-releases/") {
        name = "codemc-releases"
    }

    maven("https://repo.opencollab.dev/main/") {
        name = "opencollab"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    compileOnly("org.geysermc.geyser:api:2.11.2-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("BedrockSecureChat")
    archiveVersion.set(project.version.toString())
}
