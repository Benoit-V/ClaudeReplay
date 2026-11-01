package fr.bebedlastreat.claudeReplay.recording;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.packet.PacketFilters;

import java.util.UUID;

/**
 * Captures the packets really sent to the recorded players (MONITOR: after every other plugin).
 * Runs on the network threads.
 */
public final class RecordingPacketListener extends PacketListenerAbstract {

    private final RecordingManager manager;
    private final PlayerWorldTracker worlds;

    public RecordingPacketListener(RecordingManager manager, PlayerWorldTracker worlds) {
        super(PacketListenerPriority.MONITOR);
        this.manager = manager;
        this.worlds = worlds;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || manager.isEmpty()) {
            return;
        }
        if (!(event.getPacketType() instanceof PacketType.Play.Server type) || PacketFilters.NOT_RECORDED.contains(type)) {
            return;
        }
        User user = event.getUser();
        UUID uuid = user.getUUID();
        if (uuid == null) {
            return;
        }
        UUID world = worlds.getWorld(uuid);
        if (world == null) {
            return;
        }
        boolean blockUpdate = PacketFilters.isBlockUpdate(type);
        byte[] payload = null;
        for (RecordingImpl recording : manager.active()) {
            if (!world.equals(recording.worldId())) {
                continue;
            }
            int index = recording.capturingIndex(uuid);
            // Block updates sent to anyone in the world are world modifications, even for players not recorded.
            if (index < 0 && !blockUpdate) {
                continue;
            }
            if (payload == null) {
                payload = PacketCodec.copyPayload(event);
            }
            recording.capture(index, type, payload, user);
        }
    }
}
