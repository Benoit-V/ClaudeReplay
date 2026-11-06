package fr.bebedlastreat.claudeReplay.example.storage;

import com.google.gson.Gson;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;

/**
 * Stores a {@link ReplayInfo} as JSON. Gson (shipped with Paper) handles the API records directly.
 */
final class ReplayInfoJson {

    private static final Gson GSON = new Gson();

    private ReplayInfoJson() {
    }

    static String write(ReplayInfo info) {
        return GSON.toJson(info);
    }

    static ReplayInfo read(String json) {
        return GSON.fromJson(json, ReplayInfo.class);
    }
}
