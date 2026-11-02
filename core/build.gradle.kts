dependencies {
    compileOnly(project(":api"))
    // Compiled against the oldest supported server: version specific code lives in the platform modules.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    compileOnly("net.kyori:adventure-api:4.26.1")
    compileOnly("com.google.code.gson:gson:2.11.0")
}
