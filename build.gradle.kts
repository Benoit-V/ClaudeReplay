plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val packetEventsVersion = "2.14.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    // PacketEvents is installed as a separate plugin on the server (see plugin.yml "depend").
    compileOnly("com.github.retrooper:packetevents-spigot:$packetEventsVersion")
    // The API classes are bundled inside the plugin jar.
    implementation(project(":api"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    jar {
        // Bundle the API module (the only runtime dependency) into the plugin jar.
        dependsOn(configurations.runtimeClasspath)
        from(configurations.runtimeClasspath.map { classpath -> classpath.map { zipTree(it) } })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G")
        downloadPlugins {
            url("https://github.com/retrooper/packetevents/releases/download/v$packetEventsVersion/packetevents-spigot-$packetEventsVersion.jar")
        }
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
