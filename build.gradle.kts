plugins {
    id("java")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val packetEventsVersion = "2.14.0"

allprojects {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // The common code runs on 1.8.8 forks (Java 17+); only the modern platform may use newer bytecode.
        options.release = if (project.name == "platform-modern") 25 else 17
    }

    dependencies {
        "compileOnly"("org.jetbrains:annotations:26.0.2-1")
    }
}

// The plugin jar: api + core + every platform, loaded by version at runtime.
val bundled = configurations.create("bundled") {
    isTransitive = false
}

dependencies {
    bundled(project(":api"))
    bundled(project(":core"))
    bundled(project(":platform-legacy"))
    bundled(project(":platform-modern"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    jar {
        dependsOn(bundled)
        from(provider { bundled.files.map { zipTree(it) } })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    runServer {
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G")
        downloadPlugins {
            url("https://github.com/retrooper/packetevents/releases/download/v$packetEventsVersion/packetevents-spigot-$packetEventsVersion.jar")
        }
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
