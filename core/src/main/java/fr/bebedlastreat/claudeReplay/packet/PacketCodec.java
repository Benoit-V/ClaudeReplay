package fr.bebedlastreat.claudeReplay.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.exception.PacketProcessException;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import fr.bebedlastreat.claudeReplay.util.VarInts;
import org.bukkit.entity.Player;

import java.util.function.Function;

/**
 * Converts packets between raw payloads (what replays store: the packet body without its id)
 * and PacketEvents wrappers.
 */
public final class PacketCodec {

    private PacketCodec() {
    }

    public static ServerVersion serverVersion() {
        return PacketEvents.getAPI().getServerManager().getVersion();
    }

    /** Servers before 1.9 (1.8.8): their packets differ a lot from the modern ones. */
    public static boolean isLegacy() {
        return serverVersion().isOlderThan(ServerVersion.V_1_9);
    }

    /** Version used for packet ids: our raw packets are written before ViaVersion-like translators. */
    public static ClientVersion nativeVersion() {
        return serverVersion().toClientVersion();
    }

    /** Version of a player's client (through ViaVersion, it can differ from the server's), null if unknown. */
    public static ClientVersion clientVersion(Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        ClientVersion version = user == null ? null : user.getClientVersion();
        return version == null || version == ClientVersion.UNKNOWN ? null : version;
    }

    /** Server version of a recorded protocol (the replay's), or null if PacketEvents does not know it. */
    public static ServerVersion versionOf(int protocol) {
        for (ServerVersion version : ServerVersion.reversedValues()) {
            if (version.getProtocolVersion() == protocol) {
                return version;
            }
        }
        return null;
    }

    /** True if the packet type exists in the server's version. */
    public static boolean exists(PacketTypeCommon type) {
        return type instanceof PacketType.Play.Server server && server.getId(nativeVersion()) >= 0;
    }

    /**
     * Copies the body of a packet being sent. Must be called from the listener of the event.
     */
    public static byte[] copyPayload(PacketSendEvent event) {
        PacketWrapper<?> last = event.getLastUsedWrapper();
        if (last != null) {
            // Another listener decoded (and maybe modified) the packet: its buffer was consumed and the
            // wrapper is what will really be sent.
            return encode(last);
        }
        Object buffer = event.getByteBuf();
        byte[] payload = new byte[ByteBufHelper.readableBytes(buffer)];
        ByteBufHelper.getBytes(buffer, ByteBufHelper.readerIndex(buffer), payload);
        return payload;
    }

    /** Serializes the body of a wrapper. */
    public static byte[] encode(PacketWrapper<?> wrapper) {
        synchronized (wrapper.bufferLock) {
            Object previous = wrapper.getBuffer();
            Object buffer = UnpooledByteBufAllocationHelper.buffer();
            try {
                wrapper.setBuffer(buffer);
                wrapper.write();
                byte[] payload = new byte[ByteBufHelper.readableBytes(buffer)];
                ByteBufHelper.getBytes(buffer, ByteBufHelper.readerIndex(buffer), payload);
                return payload;
            } finally {
                wrapper.setBuffer(previous);
                ByteBufHelper.release(buffer);
            }
        }
    }

    /** Serializes a wrapper built by this plugin, for the current server version. */
    public static byte[] encodeNew(PacketWrapper<?> wrapper) {
        return encodeFor(wrapper, serverVersion());
    }

    /** Serializes a wrapper for the given version. */
    public static byte[] encodeFor(PacketWrapper<?> wrapper, ServerVersion version) {
        wrapper.setServerVersion(version);
        wrapper.setClientVersion(version.toClientVersion());
        return encode(wrapper);
    }

    /**
     * Decodes a stored payload with a wrapper constructor such as {@code WrapperPlayServerSpawnEntity::new}.
     *
     * @param user the connection whose registries are used to decode registry-dependent data
     */
    public static <W extends PacketWrapper<?>> W decode(Function<PacketSendEvent, W> constructor,
                                                        PacketType.Play.Server type, byte[] payload, User user) {
        return decode(constructor, type, payload, user, serverVersion());
    }

    /** Decodes a payload written by a server of the given version. */
    public static <W extends PacketWrapper<?>> W decode(Function<PacketSendEvent, W> constructor,
                                                        PacketType.Play.Server type, byte[] payload, User user,
                                                        ServerVersion version) {
        Object buffer = UnpooledByteBufAllocationHelper.wrappedBuffer(payload);
        try {
            ReplayPacketEvent event = new ReplayPacketEvent(type.getId(version.toClientVersion()), type, version, user, buffer);
            return constructor.apply(event);
        } catch (PacketProcessException e) {
            throw new IllegalStateException("Cannot decode " + type, e);
        }
    }

    /**
     * Queues a raw packet to a connection, bypassing the PacketEvents listeners. Call {@link User#flushPackets()} after.
     */
    public static void write(User user, PacketType.Play.Server type, byte[] payload) {
        int id = type.getId(nativeVersion());
        if (id < 0) {
            return;
        }
        user.writePacketSilently(UnpooledByteBufAllocationHelper.wrappedBuffer(VarInts.prepend(id, payload)));
    }

    /** Queues a wrapper to a connection, bypassing the PacketEvents listeners. Ignored if the packet does not exist. */
    public static void write(User user, PacketWrapper<?> wrapper) {
        if (exists(wrapper.getPacketTypeData().getPacketType())) {
            user.writePacketSilently(wrapper);
        }
    }
}
