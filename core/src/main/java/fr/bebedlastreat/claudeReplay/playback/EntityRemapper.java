package fr.bebedlastreat.claudeReplay.playback;

import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_19_3;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCollectItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.packet.PacketFilters;
import fr.bebedlastreat.claudeReplay.util.VarInts;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes recorded packets safe to replay to a viewer:
 * <ul>
 *     <li>entity ids are moved to a range the playback server never uses ({@code 0x40000000+}), so they cannot
 *     collide with the viewer's own entity;</li>
 *     <li>player UUIDs are scrambled, so a viewer watching themselves does not clash with their own profile;</li>
 *     <li>signed chat is converted to system chat (signatures cannot be verified by another client);</li>
 *     <li>explosion knockback, which would push the viewer, is removed.</li>
 * </ul>
 */
final class EntityRemapper {

    private static final int REPLAY_ID_FLAG = 0x40000000;
    private static final long UUID_MASK_MOST = 0x5265706C61795F43L;
    private static final long UUID_MASK_LEAST = 0x6C61756465526570L;

    private EntityRemapper() {
    }

    record Out(Server type, byte[] payload) {
    }

    static int id(int original) {
        return (original & 0x3FFFFFFF) | REPLAY_ID_FLAG;
    }

    static boolean isReplayId(int id) {
        return (id & REPLAY_ID_FLAG) != 0 && id > 0;
    }

    /** Reversible: applying it twice gives back the original UUID. */
    static UUID uuid(UUID original) {
        return new UUID(original.getMostSignificantBits() ^ UUID_MASK_MOST, original.getLeastSignificantBits() ^ UUID_MASK_LEAST);
    }

    /** @return the transformed packet, or null to drop it */
    static Out transform(Server type, byte[] payload, User user) {
        if (PacketFilters.LEADING_ENTITY_ID.contains(type)) {
            return new Out(type, VarInts.replaceLeading(payload, id(VarInts.leadingValue(payload))));
        }
        switch (type) {
            case SPAWN_ENTITY -> {
                WrapperPlayServerSpawnEntity spawn = PacketCodec.decode(WrapperPlayServerSpawnEntity::new, type, payload, user);
                spawn.setEntityId(id(spawn.getEntityId()));
                spawn.getUUID().ifPresent(uuid -> spawn.setUUID(Optional.of(uuid(uuid))));
                if (spawn.getEntityType() == EntityTypes.FISHING_BOBBER && spawn.getData() != 0) {
                    spawn.setData(id(spawn.getData())); // owner, draws the fishing line
                }
                return new Out(type, PacketCodec.encode(spawn));
            }
            case SPAWN_PLAYER -> { // 1.8
                WrapperPlayServerSpawnPlayer spawn = PacketCodec.decode(WrapperPlayServerSpawnPlayer::new, type, payload, user);
                spawn.setEntityId(id(spawn.getEntityId()));
                spawn.setUUID(uuid(spawn.getUUID()));
                return new Out(type, PacketCodec.encode(spawn));
            }
            case PLAYER_INFO -> { // 1.8
                return legacyPlayerInfo(type, payload, user);
            }
            case DESTROY_ENTITIES -> {
                WrapperPlayServerDestroyEntities destroy = PacketCodec.decode(WrapperPlayServerDestroyEntities::new, type, payload, user);
                int[] ids = destroy.getEntityIds();
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = id(ids[i]);
                }
                destroy.setEntityIds(ids);
                return new Out(type, PacketCodec.encode(destroy));
            }
            case SET_PASSENGERS -> {
                WrapperPlayServerSetPassengers passengers = PacketCodec.decode(WrapperPlayServerSetPassengers::new, type, payload, user);
                passengers.setEntityId(id(passengers.getEntityId()));
                int[] ids = passengers.getPassengers();
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = id(ids[i]);
                }
                passengers.setPassengers(ids);
                return new Out(type, PacketCodec.encode(passengers));
            }
            case ATTACH_ENTITY -> {
                WrapperPlayServerAttachEntity attach = PacketCodec.decode(WrapperPlayServerAttachEntity::new, type, payload, user);
                attach.setAttachedId(id(attach.getAttachedId()));
                if (attach.getHoldingId() > 0) {
                    attach.setHoldingId(id(attach.getHoldingId()));
                }
                return new Out(type, PacketCodec.encode(attach));
            }
            case ENTITY_STATUS -> {
                WrapperPlayServerEntityStatus status = PacketCodec.decode(WrapperPlayServerEntityStatus::new, type, payload, user);
                status.setEntityId(id(status.getEntityId()));
                return new Out(type, PacketCodec.encode(status));
            }
            case COLLECT_ITEM -> {
                WrapperPlayServerCollectItem collect = PacketCodec.decode(WrapperPlayServerCollectItem::new, type, payload, user);
                collect.setCollectedEntityId(id(collect.getCollectedEntityId()));
                collect.setCollectorEntityId(id(collect.getCollectorEntityId()));
                return new Out(type, PacketCodec.encode(collect));
            }
            case DAMAGE_EVENT -> {
                WrapperPlayServerDamageEvent damage = PacketCodec.decode(WrapperPlayServerDamageEvent::new, type, payload, user);
                damage.setEntityId(id(damage.getEntityId()));
                // cause and direct source are encoded as id + 1 (0 = none)
                if (damage.getSourceCauseId() > 0) {
                    damage.setSourceCauseId(id(damage.getSourceCauseId() - 1) + 1);
                }
                if (damage.getSourceDirectId() > 0) {
                    damage.setSourceDirectId(id(damage.getSourceDirectId() - 1) + 1);
                }
                return new Out(type, PacketCodec.encode(damage));
            }
            case ENTITY_SOUND_EFFECT -> {
                WrapperPlayServerEntitySoundEffect sound = PacketCodec.decode(WrapperPlayServerEntitySoundEffect::new, type, payload, user);
                sound.setEntityId(id(sound.getEntityId()));
                return new Out(type, PacketCodec.encode(sound));
            }
            case PLAYER_INFO_UPDATE -> {
                return playerInfo(type, payload, user);
            }
            case PLAYER_INFO_REMOVE -> {
                WrapperPlayServerPlayerInfoRemove remove = PacketCodec.decode(WrapperPlayServerPlayerInfoRemove::new, type, payload, user);
                List<UUID> ids = new ArrayList<>();
                for (UUID uuid : remove.getProfileIds()) {
                    ids.add(uuid(uuid));
                }
                remove.setProfileIds(ids);
                return new Out(type, PacketCodec.encode(remove));
            }
            case CHAT_MESSAGE -> {
                // Before 1.19 chat is not signed: it can be replayed as it is.
                return PacketCodec.isLegacy() ? new Out(type, payload) : chat(type, payload, user);
            }
            case EXPLOSION -> {
                WrapperPlayServerExplosion explosion = PacketCodec.decode(WrapperPlayServerExplosion::new, type, payload, user);
                if (PacketCodec.isLegacy()) {
                    explosion.setPlayerMotion(new Vector3f(0, 0, 0)); // always written on 1.8
                } else {
                    explosion.setKnockback(null);
                    explosion.setPlayerMotion(null);
                }
                return new Out(type, PacketCodec.encode(explosion));
            }
            default -> {
                return new Out(type, payload);
            }
        }
    }

    private static Out playerInfo(Server type, byte[] payload, User user) {
        WrapperPlayServerPlayerInfoUpdate update = PacketCodec.decode(WrapperPlayServerPlayerInfoUpdate::new, type, payload, user);
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = EnumSet.copyOf(update.getActions());
        actions.remove(WrapperPlayServerPlayerInfoUpdate.Action.INITIALIZE_CHAT);
        if (actions.isEmpty()) {
            return null;
        }
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>();
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo info : update.getEntries()) {
            UserProfile profile = info.getGameProfile();
            UserProfile remapped = new UserProfile(uuid(info.getProfileId()), profile == null ? null : profile.getName(),
                    profile == null ? new ArrayList<>() : profile.getTextureProperties());
            entries.add(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(remapped, info.isListed(), info.getLatency(),
                    info.getGameMode(), info.getDisplayName(), null, info.getListOrder(), info.isShowHat()));
        }
        update.setActions(actions);
        update.setEntries(entries);
        return new Out(type, PacketCodec.encode(update));
    }

    private static Out legacyPlayerInfo(Server type, byte[] payload, User user) {
        WrapperPlayServerPlayerInfo info = PacketCodec.decode(WrapperPlayServerPlayerInfo::new, type, payload, user);
        List<WrapperPlayServerPlayerInfo.PlayerData> entries = new ArrayList<>();
        for (WrapperPlayServerPlayerInfo.PlayerData data : info.getPlayerDataList()) {
            UserProfile profile = data.getUserProfile();
            UserProfile remapped = new UserProfile(uuid(profile.getUUID()), profile.getName(),
                    profile.getTextureProperties() == null ? new ArrayList<>() : profile.getTextureProperties());
            entries.add(new WrapperPlayServerPlayerInfo.PlayerData(data.getDisplayName(), remapped, data.getGameMode(), data.getPing()));
        }
        return new Out(type, PacketCodec.encodeNew(new WrapperPlayServerPlayerInfo(info.getAction(), entries)));
    }

    private static Out chat(Server type, byte[] payload, User user) {
        WrapperPlayServerChatMessage chat = PacketCodec.decode(WrapperPlayServerChatMessage::new, type, payload, user);
        ChatMessage message = chat.getMessage();
        Component text;
        if (message instanceof ChatMessage_v1_19_3 signed) {
            Component content = signed.getUnsignedChatContent().orElseGet(() -> Component.text(signed.getPlainContent()));
            Component sender = signed.getChatFormatting() == null ? Component.empty() : signed.getChatFormatting().getName();
            text = Component.translatable("chat.type.text", sender, content);
        } else {
            text = message.getChatContent();
        }
        WrapperPlayServerSystemChatMessage system = new WrapperPlayServerSystemChatMessage(false, text);
        system.setRegistryHolder(user);
        return new Out(Server.SYSTEM_CHAT_MESSAGE, PacketCodec.encodeNew(system));
    }
}
