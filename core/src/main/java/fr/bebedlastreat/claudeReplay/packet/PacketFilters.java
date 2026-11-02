package fr.bebedlastreat.claudeReplay.packet;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;

import java.util.EnumSet;
import java.util.Set;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.*;

/**
 * The packet classifications used by the recorder and the player.
 */
public final class PacketFilters {

    private PacketFilters() {
    }

    /**
     * Never stored: the world itself (chunks, light), connection plumbing and data unrelated to what the
     * player sees.
     */
    public static final Set<Server> NOT_RECORDED = EnumSet.of(
            CHUNK_DATA, MAP_CHUNK_BULK, CHUNK_BIOMES, UNLOAD_CHUNK, CHUNK_BATCH_BEGIN, CHUNK_BATCH_END, UPDATE_LIGHT,
            UPDATE_VIEW_POSITION, UPDATE_VIEW_DISTANCE, UPDATE_SIMULATION_DISTANCE,
            KEEP_ALIVE, PING, BUNDLE, SET_COMPRESSION, DISCONNECT, CONFIGURATION_START, TRANSFER,
            COOKIE_REQUEST, STORE_COOKIE, PLUGIN_MESSAGE, RESOURCE_PACK_SEND, RESOURCE_PACK_REMOVE,
            DECLARE_COMMANDS, DECLARE_RECIPES, UNLOCK_RECIPES, RECIPE_BOOK_ADD, RECIPE_BOOK_REMOVE, RECIPE_BOOK_SETTINGS,
            TAGS, UPDATE_ENABLED_FEATURES, SERVER_DATA, SERVER_LINKS, CUSTOM_REPORT_DETAILS, TAB_COMPLETE,
            CUSTOM_CHAT_COMPLETIONS, CHAT_PREVIEW_PACKET, DISPLAY_CHAT_PREVIEW, PLAYER_CHAT_HEADER,
            ACKNOWLEDGE_BLOCK_CHANGES, ACKNOWLEDGE_PLAYER_DIGGING, WINDOW_CONFIRMATION, STATISTICS, NBT_QUERY_RESPONSE,
            DEBUG_PONG, DEBUG_SAMPLE, DEBUG_BLOCK_VALUE, DEBUG_CHUNK_VALUE, DEBUG_ENTITY_VALUE, DEBUG_EVENT,
            GAME_TEST_HIGHLIGHT_POS, TEST_INSTANCE_BLOCK_STATUS, LOW_DISK_SPACE_WARNING,
            SHOW_DIALOG, CLEAR_DIALOG, CRAFT_RECIPE_RESPONSE, SELECT_ADVANCEMENTS_TAB, GAME_RULE_VALUES
    );

    /**
     * Stored but never replayed to a viewer: they would act on the viewer's own player, inventory or connection.
     */
    public static final Set<Server> NOT_REPLAYED = EnumSet.of(
            JOIN_GAME, RESPAWN, PLAYER_POSITION_AND_LOOK, PLAYER_ROTATION, VEHICLE_MOVE, PLAYER_ABILITIES,
            UPDATE_HEALTH, SET_EXPERIENCE, DEATH_COMBAT_EVENT, ENTER_COMBAT_EVENT, END_COMBAT_EVENT, COMBAT_EVENT,
            CAMERA, FACE_PLAYER, SPAWN_POSITION, HELD_ITEM_CHANGE, SET_COOLDOWN,
            OPEN_WINDOW, CLOSE_WINDOW, WINDOW_ITEMS, SET_SLOT, SET_CURSOR_ITEM, SET_PLAYER_INVENTORY, WINDOW_PROPERTY,
            OPEN_BOOK, OPEN_SIGN_EDITOR, OPEN_HORSE_WINDOW, MERCHANT_OFFERS,
            UPDATE_ADVANCEMENTS, DELETE_CHAT, TICKING_STATE, TICKING_STEP, WAYPOINT, SERVER_DIFFICULTY
    );

    /** Skipped when the timeline is moved forward quickly (pure visual or audio effects). */
    public static final Set<Server> TRANSIENT = EnumSet.of(
            SOUND_EFFECT, NAMED_SOUND_EFFECT, ENTITY_SOUND_EFFECT, STOP_SOUND, PARTICLE, EFFECT, EXPLOSION,
            ENTITY_ANIMATION, SWING_ANIMATION, HURT_ANIMATION, DAMAGE_EVENT, BLOCK_BREAK_ANIMATION, BLOCK_ACTION,
            COLLECT_ITEM, ENTITY_VELOCITY, SYSTEM_CHAT_MESSAGE, CHAT_MESSAGE, DISGUISED_CHAT, ACTION_BAR, TITLE,
            SET_TITLE_TEXT, SET_TITLE_SUBTITLE, SET_TITLE_TIMES, CLEAR_TITLES, TITLE, SPAWN_WEATHER_ENTITY
    );

    /** Packets creating an entity on the client (several kinds before 1.20.2, only SPAWN_ENTITY after). */
    public static final Set<Server> SPAWNS = EnumSet.of(
            SPAWN_ENTITY, SPAWN_LIVING_ENTITY, SPAWN_PLAYER, SPAWN_PAINTING, SPAWN_EXPERIENCE_ORB, SPAWN_WEATHER_ENTITY
    );

    /** Packets whose body starts with the VarInt id of the entity they act on. */
    public static final Set<Server> LEADING_ENTITY_ID = EnumSet.of(
            ENTITY_RELATIVE_MOVE, ENTITY_RELATIVE_MOVE_AND_ROTATION, ENTITY_ROTATION, ENTITY_MOVEMENT, ENTITY_HEAD_LOOK,
            ENTITY_TELEPORT, ENTITY_POSITION_SYNC, ENTITY_VELOCITY, ENTITY_METADATA, ENTITY_EQUIPMENT, ENTITY_ANIMATION,
            SWING_ANIMATION, ENTITY_EFFECT, REMOVE_ENTITY_EFFECT, UPDATE_ATTRIBUTES, HURT_ANIMATION, BLOCK_BREAK_ANIMATION,
            UPDATE_ENTITY_NBT, PROJECTILE_POWER, MOVE_MINECART,
            // 1.8 only: these spawn packets start with the entity id and hold no UUID
            SPAWN_LIVING_ENTITY, SPAWN_PAINTING, SPAWN_EXPERIENCE_ORB, SPAWN_WEATHER_ENTITY, USE_BED
    );

    /**
     * In free camera mode, recorded players are driven by their own track: these packets about them coming from
     * the other players' views are dropped to avoid applying movements twice.
     */
    public static final Set<Server> RECORDED_PLAYER_STATE = EnumSet.of(
            SPAWN_ENTITY, SPAWN_PLAYER, ENTITY_RELATIVE_MOVE, ENTITY_RELATIVE_MOVE_AND_ROTATION, ENTITY_ROTATION,
            ENTITY_MOVEMENT, ENTITY_HEAD_LOOK, ENTITY_TELEPORT, ENTITY_POSITION_SYNC, ENTITY_VELOCITY, ENTITY_METADATA,
            ENTITY_EQUIPMENT, ENTITY_ANIMATION, SWING_ANIMATION, HURT_ANIMATION
    );

    /** Block updates: stored as world modifications rather than as packets. */
    public static boolean isBlockUpdate(Server type) {
        return type == BLOCK_CHANGE || type == MULTI_BLOCK_CHANGE;
    }
}
