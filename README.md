# ClaudeReplay

ClaudeReplay records what players see on a Minecraft server and plays it back in game.

It works at the packet level: while a recording runs, every packet received by each recorded player is saved,
together with the block changes of the recorded world. At playback time the world is rebuilt from a base map and
the packets are replayed to the viewers, who can move freely or watch through the eyes of any recorded player.

- **Packet based**: a replay shows exactly what the players saw, including other plugins' scoreboards, holograms,
  NPCs, titles, boss bars and fake blocks.
- **Light files**: worlds are never saved inside replays, only the changes made to them. The map is copied from a
  template when the replay is watched.
- **Point of view or free camera**: follow one player's view (with their HUD, inventory and fake blocks) or fly
  around in a merged view of every recorded player.
- **Shared timeline**: several viewers can watch together, with pause, speed (×0.1 to ×10), seeking and markers.
- **Multi-version**: one jar runs on Spigot 1.8.8 and Paper 26.3. Replays recorded on 1.8.8 can be watched on 26.3.
- **API**: start and stop recordings from your own plugins (mini-games, moderation…), add markers and metadata,
  plug your own storage or map source.

## Requirements

| Server         | Java | Notes                                     |
|----------------|------|-------------------------------------------|
| Paper 26.3     | 25   |                                           |
| Spigot 1.8.8   | 17+  | a fork able to run on Java 17 or newer    |

[packetevents](https://github.com/retrooper/packetevents) **2.14.0 or newer** must be installed as a plugin.

## Installation

1. Put `packetevents` and `ClaudeReplay.jar` in the `plugins` folder.
2. Start the server once to generate `plugins/ClaudeReplay/config.yml`.
3. Put the base maps of your recorded worlds in `plugins/ClaudeReplay/maps/<mapId>` (see [Maps](#maps)).

## Commands

The main command is `/replay` (aliases `/replays`, `/crp`).

| Command                                    | Permission            | Description                                   |
|--------------------------------------------|-----------------------|-----------------------------------------------|
| `/replay record start [name] [map]`        | `claudereplay.record` | Records your current world                    |
| `/replay record stop [name]`               | `claudereplay.record` | Stops and saves a recording                   |
| `/replay record list`                      | `claudereplay.record` | Lists running recordings                      |
| `/replay record marker <name>`             | `claudereplay.record` | Adds a marker to a recording                  |
| `/replay menu`                             | `claudereplay.play`   | Opens the replays menu                        |
| `/replay list`                             | `claudereplay.play`   | Lists saved replays in the chat               |
| `/replay play <replay>`                    | `claudereplay.play`   | Watches a replay                              |
| `/replay info <replay>`                    | `claudereplay.play`   | Shows a replay's details                      |
| `/replay delete <replay>`                  | `claudereplay.admin`  | Deletes a replay                              |

While watching a replay:

| Command                         | Description                                          |
|---------------------------------|------------------------------------------------------|
| `/replay toggle`                | Pause / resume                                       |
| `/replay speed <0.1-10>`        | Changes the speed                                    |
| `/replay seek <mm:ss>`          | Jumps to a time (also seconds, or `<ticks> t`)       |
| `/replay skip <±seconds>`       | Jumps forward or backward                            |
| `/replay pov <player>`          | Watches through a recorded player's eyes             |
| `/replay free`                  | Back to the free camera                              |
| `/replay invite <player>`       | Invites a player to watch with you                   |
| `/replay leave`                 | Leaves the replay                                    |

Viewers also get hotbar items for the same actions: points of view, markers, backward, play / pause, forward,
speed, controls and leave.

`claudereplay.admin` includes the two other permissions. All three default to operators.

### Replays menu

`/replay menu` lists the saved replays, newest first. Each one shows its date, duration, server version, maps,
markers and players (with their client version). Left click to watch it, right click to print its details.
Replays recorded on a newer server version than the current one are shown in red and cannot be watched.

## Versions

Each replay stores the version of the server that recorded it, and each recorded player's client version
(their real version when they join through ViaVersion).

A replay can be watched on a server of **the same version or a newer one**:

| Recorded on | Watched on 1.8.8 | Watched on 26.3 |
|-------------|------------------|-----------------|
| 1.8.8       | yes              | yes (converted) |
| 26.3        | no               | yes             |

1.8.8 replays are converted to the modern protocol while they play. Some 1.8 content has no modern
equivalent and is skipped: paintings, signs, maps, entity attributes, and particles that carry a block or an item.

## Maps

A replay never contains its world. When a replay is watched, its base map is copied into a temporary world, and
the recorded block changes are applied on top of it.

By default, the map of a recording is looked up in `plugins/ClaudeReplay/maps/<mapId>`, where `mapId` defaults
to the recorded world's name. A map folder is either a world folder (containing `region`) or a full level folder.

If no folder exists and `maps.use-loaded-worlds` is enabled, the loaded world with the same name is used. This is
only correct when that world did not change since the recording started (a lobby, a static map…).

A recording can switch worlds while it runs (a mini-game moving to its next map). Each world period is rebuilt
from its own map.

## Configuration

```yaml
storage:
  # Folder of the saved replays (relative to plugins/ClaudeReplay).
  folder: replays

maps:
  # Base maps: <folder>/<mapId>.
  folder: maps
  # Fall back to the loaded world named like the map id.
  use-loaded-worlds: true

recording:
  # Ticks per compressed segment of the replay file.
  segment-ticks: 200
  # Block updates that do not match the real world (fake blocks sent to one player) stay in that player's view.
  verify-block-changes: true

playback:
  # Forward jumps shorter than this are played quickly instead of rebuilding the whole state.
  fast-forward-limit-seconds: 15
```

Replays are saved as `<id>.crp` (packets) and `<id>.json` (description) in the storage folder.

## API

### Adding the dependency

The API is a separate artifact. Publish it to your local Maven repository:

```bash
./gradlew :api:publishToMavenLocal
```

Then depend on it, without shading it:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    compileOnly("fr.bebedlastreat:claudereplay-api:1.0")
}
```

And declare the dependency in your `plugin.yml`:

```yaml
depend: [ ClaudeReplay ]
```

The API targets Java 17 and the 1.8.8 Bukkit API, so it can be used from plugins on both supported versions.

### Getting the API

```java
ClaudeReplayAPI api = ClaudeReplayProvider.get();
```

It is also registered in Bukkit's `ServicesManager`:

```java
ClaudeReplayAPI api = Bukkit.getServicesManager().load(ClaudeReplayAPI.class);
```

Unless stated otherwise, every method must be called from the main thread.

### Recording a game

```java
Recording recording = api.startRecording(RecordingOptions.builder(arena.getWorld())
        .name("BedWars #" + gameId)
        .mapId("bedwars_lighthouse")              // base map in plugins/ClaudeReplay/maps
        .trackingMode(PlayerTrackingMode.WORLD)   // record everyone inside the world
        .metadata("game", "bedwars")
        .metadata("gameId", String.valueOf(gameId))
        .maxDurationTicks(20 * 60 * 30)           // stops by itself after 30 minutes
        .build());

// During the game
recording.addMarker("Red bed destroyed");
recording.addMarker("Kill", killer.getName() + " -> " + victim.getName());
recording.setMetadata("winner", "blue");

// At the end
recording.stop().thenAccept(replay ->
        Bukkit.broadcastMessage("Replay saved: " + replay.id()));
```

`stop()` completes on the main thread once the replay is saved. `cancel()` stops without saving.

#### Tracking modes

- `PlayerTrackingMode.WORLD` (default): players entering the recorded world are added, players leaving it are
  removed.
- `PlayerTrackingMode.MANUAL`: only the players you add are recorded.

```java
Recording recording = api.startRecording(RecordingOptions.builder(world)
        .trackingMode(PlayerTrackingMode.MANUAL)
        .players(team.getPlayers())
        .build());

recording.addPlayer(lateJoiner);
recording.removePlayer(leaver.getUniqueId());
```

Recorded players are only captured while they are inside the recorded world.

#### Switching worlds

```java
recording.setWorld(nextArena.getWorld(), "skywars_islands");
```

At playback time the new world is rebuilt from a fresh copy of its map.

#### Changes nobody sees

Block changes seen by recorded players are recorded automatically. If you change a block far from every
recorded player, record its new state yourself:

```java
block.setType(Material.AIR);
recording.recordBlockChange(block.getLocation());
```

### Watching a replay

```java
api.startPlayback(replayId, List.of(player)).whenComplete((session, error) -> {
    if (error != null) {
        player.sendMessage("Cannot watch this replay: " + error.getMessage());
        return;
    }
    session.seek(20 * 60);                              // go to 1:00
    session.setSpeed(2);
    session.setPointOfView(player, recordedPlayerUuid); // null = free camera
});
```

The future completes on the main thread once the viewers are inside the replay. It fails if the replay comes from
a newer server version; check it beforehand with `api.isPlayable(replay)`.

`PlaybackSession` controls the shared timeline:

| Method                                 | Description                                      |
|----------------------------------------|--------------------------------------------------|
| `getViewers()`, `addViewer(player)`    | Viewers of the session                           |
| `removeViewer(player)`                 | Sends a viewer back (the session stops when empty) |
| `isPaused()`, `setPaused(boolean)`     | Pause                                            |
| `getSpeed()`, `setSpeed(double)`       | Speed, from 0.1 to 10                            |
| `getTick()`, `seek(int)`               | Position in ticks                                |
| `getPointOfView(viewer)`, `setPointOfView(viewer, uuid)` | Point of view of one viewer    |
| `stop()`                               | Sends every viewer back and deletes the worlds   |

`api.getPlayback(player)` gives the session a player is watching, if any.

### Browsing replays

```java
api.listReplays().thenAccept(replays -> {
    for (ReplayInfo replay : replays) {
        String game = replay.getMetadata("game");
        int seconds = replay.durationTicks() / 20;
        boolean playable = api.isPlayable(replay);
        // replay.name(), replay.createdAt(), replay.serverVersion(), replay.markers()...
    }
});
```

`listReplays`, `getReplayInfo` and `deleteReplay` may complete off the main thread. Go back to it with the
scheduler before touching the world or players.

A `ReplayInfo` contains:

| Field                               | Description                                       |
|-------------------------------------|---------------------------------------------------|
| `id`, `name`, `createdAt`           | Identity and start date (epoch millis)            |
| `durationTicks`                     | Length                                            |
| `protocolVersion`, `serverVersion`  | Version of the recording server                   |
| `players`                           | `ReplayPlayer`: uuid, name, client protocol and version |
| `worlds`                            | `ReplayWorld`: start tick, world name, map id, environment, spawn point |
| `markers`                           | `ReplayMarker`: tick, name, optional data         |
| `metadata`                          | The metadata set by the recording plugin          |

### Events

All events are Bukkit events, called on the main thread.

| Event                         | When                                                       |
|-------------------------------|------------------------------------------------------------|
| `RecordingStartEvent`         | A recording started                                        |
| `RecordingPlayerAddEvent`     | A player is about to be added (cancellable, `isAutomatic()`) |
| `RecordingPlayerRemoveEvent`  | A player was removed                                       |
| `RecordingWorldChangeEvent`   | A recording switched worlds                                |
| `RecordingStopEvent`          | A recording stops, before saving (`isSaved()` is false when cancelled) |
| `PlaybackStartEvent`          | Viewers are inside a replay                                |
| `PlaybackStopEvent`           | A playback stopped and its viewers were sent back          |

```java
@EventHandler
public void onAdd(RecordingPlayerAddEvent event) {
    if (event.getPlayer().hasPermission("staff.hidden")) {
        event.setCancelled(true); // never record hidden staff
    }
}
```

### Custom storage

Replays are saved as local files by default. Implement `ReplayStorage` to keep them somewhere else (database,
S3, a shared folder…):

```java
public final class S3ReplayStorage implements ReplayStorage {

    @Override
    public CompletableFuture<Void> save(ReplayInfo info, Path replayFile) {
        // Upload the file: ClaudeReplay deletes it once this future completes.
    }

    @Override
    public CompletableFuture<Path> fetch(UUID replayId) {
        // Download into a local temporary file and return it.
    }

    @Override
    public void release(UUID replayId, Path file) {
        // The fetched file is no longer used: delete it.
    }

    @Override
    public CompletableFuture<List<ReplayInfo>> list() { ... }

    @Override
    public CompletableFuture<Optional<ReplayInfo>> info(UUID replayId) { ... }

    @Override
    public CompletableFuture<Boolean> delete(UUID replayId) { ... }
}

api.setStorage(new S3ReplayStorage(...));
```

Storage methods may be called and completed from any thread.

### Custom maps

Implement `MapResolver` to give the base map of a replay from somewhere else, for example a map manager plugin:

```java
api.setMapResolver(mapId -> CompletableFuture.supplyAsync(() -> mapManager.download(mapId)));
```

The returned folder must contain `level.dat` and the region files. It is only read, never modified.

## Building

```bash
./gradlew build
```

The plugin jar is written to `build/libs/`. The project is split into modules:

| Module            | Content                                                          | Compiled against          |
|-------------------|------------------------------------------------------------------|---------------------------|
| `api`             | Public API                                                       | Spigot 1.8.8 API, Java 17 |
| `core`            | Recording, playback, file format, menus, 1.8 → modern conversion | Spigot 1.8.8 API, Java 17 |
| `platform-legacy` | Spigot 1.8.8 specific code                                       | Spigot 1.8.8 API, Java 17 |
| `platform-modern` | Paper 26.x specific code                                         | Paper 26.3 API, Java 25   |

All modules are bundled into the single plugin jar; the right platform is picked when the server starts.

`./gradlew runServer` starts a Paper 26.3 test server in `run/`.
