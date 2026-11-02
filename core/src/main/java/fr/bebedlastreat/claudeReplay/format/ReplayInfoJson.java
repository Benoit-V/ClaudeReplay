package fr.bebedlastreat.claudeReplay.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayMarker;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayPlayer;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JSON form of {@link ReplayInfo}, written by hand: old servers ship a Gson that cannot handle records.
 * Only uses the Gson API available since 2.2.4. The field names are those of the records.
 */
public final class ReplayInfoJson {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ReplayInfoJson() {
    }

    public static String write(ReplayInfo info) {
        JsonObject json = new JsonObject();
        json.addProperty("id", info.id().toString());
        json.addProperty("name", info.name());
        json.addProperty("createdAt", info.createdAt());
        json.addProperty("durationTicks", info.durationTicks());
        json.addProperty("protocolVersion", info.protocolVersion());
        json.addProperty("serverVersion", info.serverVersion());

        JsonArray players = new JsonArray();
        for (ReplayPlayer player : info.players()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", player.uuid().toString());
            entry.addProperty("name", player.name());
            if (player.protocolVersion() != 0) {
                entry.addProperty("protocolVersion", player.protocolVersion());
            }
            if (player.clientVersion() != null) {
                entry.addProperty("clientVersion", player.clientVersion());
            }
            players.add(entry);
        }
        json.add("players", players);

        JsonArray worlds = new JsonArray();
        for (ReplayWorld world : info.worlds()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("startTick", world.startTick());
            entry.addProperty("worldName", world.worldName());
            entry.addProperty("mapId", world.mapId());
            entry.addProperty("environment", world.environment().name());
            entry.addProperty("x", world.x());
            entry.addProperty("y", world.y());
            entry.addProperty("z", world.z());
            entry.addProperty("yaw", world.yaw());
            entry.addProperty("pitch", world.pitch());
            worlds.add(entry);
        }
        json.add("worlds", worlds);

        JsonArray markers = new JsonArray();
        for (ReplayMarker marker : info.markers()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("tick", marker.tick());
            entry.addProperty("name", marker.name());
            if (marker.data() != null) {
                entry.addProperty("data", marker.data());
            }
            markers.add(entry);
        }
        json.add("markers", markers);

        JsonObject metadata = new JsonObject();
        info.metadata().forEach(metadata::addProperty);
        json.add("metadata", metadata);
        return GSON.toJson(json);
    }

    @SuppressWarnings("deprecation") // JsonParser.parseString does not exist in old Gson versions
    public static ReplayInfo read(String text) {
        JsonObject json = new JsonParser().parse(text).getAsJsonObject();

        List<ReplayPlayer> players = new ArrayList<>();
        for (JsonElement element : array(json, "players")) {
            JsonObject entry = element.getAsJsonObject();
            players.add(new ReplayPlayer(UUID.fromString(string(entry, "uuid")), string(entry, "name"),
                    entry.has("protocolVersion") ? entry.get("protocolVersion").getAsInt() : 0,
                    entry.has("clientVersion") && !entry.get("clientVersion").isJsonNull() ? string(entry, "clientVersion") : null));
        }

        List<ReplayWorld> worlds = new ArrayList<>();
        for (JsonElement element : array(json, "worlds")) {
            JsonObject entry = element.getAsJsonObject();
            World.Environment environment;
            try {
                environment = World.Environment.valueOf(string(entry, "environment"));
            } catch (IllegalArgumentException e) {
                environment = World.Environment.NORMAL;
            }
            worlds.add(new ReplayWorld(entry.get("startTick").getAsInt(), string(entry, "worldName"), string(entry, "mapId"),
                    environment, entry.get("x").getAsDouble(), entry.get("y").getAsDouble(), entry.get("z").getAsDouble(),
                    entry.get("yaw").getAsFloat(), entry.get("pitch").getAsFloat()));
        }

        List<ReplayMarker> markers = new ArrayList<>();
        for (JsonElement element : array(json, "markers")) {
            JsonObject entry = element.getAsJsonObject();
            markers.add(new ReplayMarker(entry.get("tick").getAsInt(), string(entry, "name"),
                    entry.has("data") && !entry.get("data").isJsonNull() ? string(entry, "data") : null));
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        if (json.has("metadata") && json.get("metadata").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("metadata").entrySet()) {
                if (entry.getValue() instanceof JsonPrimitive) {
                    metadata.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }

        return new ReplayInfo(UUID.fromString(string(json, "id")), string(json, "name"), json.get("createdAt").getAsLong(),
                json.get("durationTicks").getAsInt(), json.get("protocolVersion").getAsInt(), string(json, "serverVersion"),
                players, worlds, markers, metadata);
    }

    private static String string(JsonObject json, String key) {
        return json.get(key).getAsString();
    }

    private static JsonArray array(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
    }
}
