package fr.bebedlastreat.claudeReplay.recording;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.platform.Platform;
import fr.bebedlastreat.claudeReplay.platform.Platforms;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the packets describing a live player, in the format of the server version (1.8.8 or modern).
 */
final class PlayerPackets {

    /** A packet ready to be stored. */
    record Built(Server type, byte[] payload) {
    }

    private static boolean metadataReflectionBroken;

    private PlayerPackets() {
    }

    private static Built built(Server type, PacketWrapper<?> wrapper) {
        return new Built(type, PacketCodec.encodeNew(wrapper));
    }

    static int ping(Player player) {
        return PacketEvents.getAPI().getPlayerManager().getPing(player);
    }

    /** Adds entries to the tab list (and gives the client the skins). */
    static Built tabAdd(List<Platform.TabEntry> entries) {
        if (PacketCodec.isLegacy()) {
            List<WrapperPlayServerPlayerInfo.PlayerData> data = new ArrayList<>();
            for (Platform.TabEntry entry : entries) {
                data.add(new WrapperPlayServerPlayerInfo.PlayerData(entry.displayName(), entry.profile(),
                        SpigotConversionUtil.fromBukkitGameMode(entry.gameMode()), entry.ping()));
            }
            return built(Server.PLAYER_INFO, new WrapperPlayServerPlayerInfo(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER, data));
        }
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infos = new ArrayList<>();
        for (Platform.TabEntry entry : entries) {
            infos.add(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(entry.profile(), entry.listed(), entry.ping(),
                    SpigotConversionUtil.fromBukkitGameMode(entry.gameMode()), entry.displayName(), null, entry.listOrder()));
        }
        return built(Server.PLAYER_INFO_UPDATE, new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(
                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LIST_ORDER), infos));
    }

    /**
     * Gives the client the profile (skin) of a player before spawning them. On modern versions the entry is not
     * listed and ADD_PLAYER never overrides an existing one, so it does not conflict with the real tab list.
     */
    static Built profile(Player player) {
        UserProfile profile = Platforms.get().profile(player);
        GameMode gameMode = SpigotConversionUtil.fromBukkitGameMode(player.getGameMode());
        if (PacketCodec.isLegacy()) {
            return built(Server.PLAYER_INFO, new WrapperPlayServerPlayerInfo(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER,
                    new WrapperPlayServerPlayerInfo.PlayerData((Component) null, profile, gameMode, ping(player))));
        }
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile, false, ping(player), gameMode, null, null);
        return built(Server.PLAYER_INFO_UPDATE, new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE), info));
    }

    static Built gameMode(Player player, org.bukkit.GameMode bukkitMode) {
        UserProfile profile = Platforms.get().profile(player);
        GameMode gameMode = SpigotConversionUtil.fromBukkitGameMode(bukkitMode);
        if (PacketCodec.isLegacy()) {
            return built(Server.PLAYER_INFO, new WrapperPlayServerPlayerInfo(WrapperPlayServerPlayerInfo.Action.UPDATE_GAME_MODE,
                    new WrapperPlayServerPlayerInfo.PlayerData((Component) null, profile, gameMode, ping(player))));
        }
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile, true, ping(player), gameMode, null, null);
        return built(Server.PLAYER_INFO_UPDATE, new WrapperPlayServerPlayerInfoUpdate(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE, info));
    }

    static Built spawn(int entityId, UUID uuid, Vector3d position, float yaw, float pitch, List<EntityData<?>> metadata) {
        if (PacketCodec.isLegacy()) {
            return built(Server.SPAWN_PLAYER, new WrapperPlayServerSpawnPlayer(entityId, uuid, position, yaw, pitch, metadata));
        }
        return built(Server.SPAWN_ENTITY, new WrapperPlayServerSpawnEntity(entityId, Optional.of(uuid),
                EntityTypes.PLAYER, position, pitch, yaw, yaw, 0, Optional.empty()));
    }

    /** One packet on modern versions, one per slot on 1.8. */
    static List<Built> equipment(int entityId, List<Equipment> equipment) {
        if (!PacketCodec.isLegacy()) {
            return List.of(built(Server.ENTITY_EQUIPMENT, new WrapperPlayServerEntityEquipment(entityId, equipment)));
        }
        List<Built> packets = new ArrayList<>();
        for (Equipment slot : equipment) {
            if (slot.getSlot() != EquipmentSlot.OFF_HAND) {
                packets.add(built(Server.ENTITY_EQUIPMENT, new WrapperPlayServerEntityEquipment(entityId, List.of(slot))));
            }
        }
        return packets;
    }

    static Built hurt(int entityId) {
        if (PacketCodec.isLegacy()) {
            return built(Server.ENTITY_STATUS, new WrapperPlayServerEntityStatus(entityId, 2)); // hurt animation
        }
        return built(Server.HURT_ANIMATION, new WrapperPlayServerHurtAnimation(entityId, 0));
    }

    static ItemStack item(org.bukkit.inventory.ItemStack item) {
        return Platforms.get().isEmpty(item) ? ItemStack.EMPTY : SpigotConversionUtil.fromBukkitItemStack(item);
    }

    /**
     * The entity data other players see for this player. Uses the real server data when possible and falls back
     * to the most visible fields.
     */
    static List<EntityData<?>> metadata(Player player) {
        if (!metadataReflectionBroken) {
            try {
                return SpigotConversionUtil.getEntityMetadata(player);
            } catch (Throwable t) {
                metadataReflectionBroken = true;
            }
        }
        return Platforms.get().fallbackMetadata(player);
    }
}
