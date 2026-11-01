package fr.bebedlastreat.claudeReplay.packet;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.exception.PacketProcessException;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;

/**
 * A detached send event wrapping a stored payload, so that any PacketEvents wrapper can decode it
 * through its {@code (PacketSendEvent)} constructor. It is never fired to listeners.
 */
final class ReplayPacketEvent extends PacketSendEvent {

    ReplayPacketEvent(int packetId, PacketTypeCommon type, ServerVersion version, User user, Object buffer)
            throws PacketProcessException {
        super(packetId, type, version, user.getChannel(), user, null, buffer);
    }
}
