package fr.bebedlastreat.claudeReplay.recording;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers building packet data from a live Bukkit player.
 */
final class PlayerPackets {

    private static boolean metadataReflectionBroken;

    private PlayerPackets() {
    }

    static UserProfile profile(Player player) {
        List<TextureProperty> properties = new ArrayList<>();
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            properties.add(new TextureProperty(property.getName(), property.getValue(), property.getSignature()));
        }
        return new UserProfile(player.getUniqueId(), player.getName(), properties);
    }

    /**
     * The entity data other players see for this player. Uses the real server data when possible and falls back
     * to the most visible fields (flags, pose, skin layers).
     */
    static List<EntityData<?>> metadata(Player player) {
        if (!metadataReflectionBroken) {
            try {
                return SpigotConversionUtil.getEntityMetadata(player);
            } catch (Throwable t) {
                metadataReflectionBroken = true;
            }
        }
        List<EntityData<?>> data = new ArrayList<>();
        byte flags = 0;
        if (player.getFireTicks() > 0) flags |= 0x01;
        if (player.isSneaking()) flags |= 0x02;
        if (player.isSprinting()) flags |= 0x08;
        if (player.isSwimming()) flags |= 0x10;
        if (player.isInvisible()) flags |= 0x20;
        if (player.isGlowing()) flags |= 0x40;
        if (player.isGliding()) flags |= (byte) 0x80;
        data.add(new EntityData<>(0, EntityDataTypes.BYTE, flags));
        data.add(new EntityData<>(6, EntityDataTypes.ENTITY_POSE, SpigotConversionUtil.fromBukkitPose(player.getPose())));
        int skinIndex = PacketCodec.serverVersion().isNewerThanOrEquals(ServerVersion.V_1_21_9) ? 16 : 17;
        data.add(new EntityData<>(skinIndex, EntityDataTypes.BYTE, (byte) player.getClientOption(ClientOption.SKIN_PARTS).getRaw()));
        return data;
    }
}
