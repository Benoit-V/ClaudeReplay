package fr.bebedlastreat.claudeReplay.playback;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;

/**
 * Receives the packets a viewer must see, already filtered and remapped.
 */
interface PacketSink {

    void send(Server type, byte[] payload);

    /** Removes every replay entity, except {@code keep} (a remapped id, -1 for none). */
    void destroyEntities(int keep);
}
