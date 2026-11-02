plugins {
    id("java-library")
    id("maven-publish")
}

base {
    archivesName = "ClaudeReplay-api"
}

dependencies {
    // Oldest supported server API: everything here also exists on newer servers.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks {
    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "claudereplay-api"
            from(components["java"])
        }
    }
}
