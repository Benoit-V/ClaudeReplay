// Example plugin using the ClaudeReplay API. Not part of the ClaudeReplay jar: it builds its own,
// build/libs/ClaudeReplay-example.jar, to install next to ClaudeReplay on a Paper 26.3 server.

base {
    archivesName = "ClaudeReplay-example"
}

dependencies {
    compileOnly(project(":api"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")

    // Downloaded by the server at startup (see "libraries" in plugin.yml).
    compileOnly("com.zaxxer:HikariCP:6.2.1")
    compileOnly("org.mongodb:mongodb-driver-sync:5.2.1")
}
