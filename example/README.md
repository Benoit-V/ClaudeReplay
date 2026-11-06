# ClaudeReplay examples

A small Paper 26.3 plugin showing real uses of the [ClaudeReplay API](../README.md#api). Each class is
independent: copy the one you need into your own plugin.

| Use case | Class | What it shows |
|----------|-------|---------------|
| Record mini-game games | [`GameRecorder`](src/main/java/fr/bebedlastreat/claudeReplay/example/minigame/GameRecorder.java) | One recording per game, markers on kills and broken beds, metadata (mode, game id, winner), clickable "Watch" message at the end |
| Reports with evidence | [`ReportBuffer`](src/main/java/fr/bebedlastreat/claudeReplay/example/report/ReportBuffer.java) | Worlds always recorded with `keepLastTicks`; `/report` saves the last minutes and starts a new buffer; staff are notified |
| Staff review | [`PlayerReplays`](src/main/java/fr/bebedlastreat/claudeReplay/example/review/PlayerReplays.java) | Finds the replays of a player, opens one through their eyes, a few seconds before the report |
| Recording rules | [`RecordingRules`](src/main/java/fr/bebedlastreat/claudeReplay/example/rules/RecordingRules.java) | Never records vanished staff (cancels `RecordingPlayerAddEvent`), logs recordings and playbacks |
| Maps shared by a network | [`SharedFolderMapResolver`](src/main/java/fr/bebedlastreat/claudeReplay/example/map/SharedFolderMapResolver.java) | A `MapResolver` reading `<mapId>.zip` files from a shared folder, with a local cache |
| Replays in MySQL / MariaDB | [`SqlReplayStorage`](src/main/java/fr/bebedlastreat/claudeReplay/example/storage/SqlReplayStorage.java) | A `ReplayStorage` keeping the description as JSON and the file as a BLOB |
| Replays in MongoDB | [`MongoReplayStorage`](src/main/java/fr/bebedlastreat/claudeReplay/example/storage/MongoReplayStorage.java) | A `ReplayStorage` keeping one document per replay and the file in GridFS |

[`ExamplePlugin`](src/main/java/fr/bebedlastreat/claudeReplay/example/ExamplePlugin.java) wires them together.

## Trying it

```bash
./gradlew :example:build
```

Put `example/build/libs/ClaudeReplay-example-1.0.jar` next to ClaudeReplay on a Paper 26.3 server. The server
downloads HikariCP, the MariaDB driver and the MongoDB driver at startup (`libraries` in `plugin.yml`).

| Command | Permission | Description |
|---------|------------|-------------|
| `/game start <mode>` | `claudereplay.example.game` | Starts recording a game in your world |
| `/game end [winner]` | `claudereplay.example.game` | Ends it and broadcasts a link to the replay |
| `/report <player> <reason>` | everyone | Saves the last minutes of the reported player's world |
| `/replaysof <player>` | `claudereplay.example.staff` | Lists the replays of a player |

Players with `claudereplay.example.hidden` are never recorded.

## Storing replays in a database

`config.yml` chooses where replays go:

```yaml
storage:
  type: mysql        # file, mysql or mongodb
  mysql:
    url: jdbc:mariadb://localhost:3306/minecraft
    driver: org.mariadb.jdbc.Driver
    user: minecraft
    password: change-me
  mongodb:
    uri: mongodb://localhost:27017
    database: minecraft
```

The plugin then calls `api.setStorage(...)` with one of the storages. Every server of a network using the same
database sees the same replays (add `maps.shared-folder` so they also share the maps).

Things to keep in mind when writing your own storage:

- Methods run off the main thread: use your own executor, never block the server.
- `save` receives a file that ClaudeReplay deletes once the future completes: read it before completing.
- `fetch` must give a local file. Download it to a temporary file and delete it in `release`.
- The `ReplayInfo` record serializes directly with Gson (shipped with Paper).
- MySQL: replay files are usually a few hundred kilobytes to a few megabytes; raise `max_allowed_packet` if needed.
  Drivers loaded as plugin libraries are not visible to `DriverManager`, so the driver class is given to HikariCP.
- MongoDB: files go to GridFS since they can exceed the 16 MB document limit. Documents also hold the recorded
  players, so a web panel can find the replays of a player with `{"players.uuid": "..."}`.
