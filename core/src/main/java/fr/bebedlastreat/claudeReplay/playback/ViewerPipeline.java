package fr.bebedlastreat.claudeReplay.playback;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;
import fr.bebedlastreat.claudeReplay.packet.PacketFilters;
import fr.bebedlastreat.claudeReplay.util.VarInts;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses, for one viewer, which recorded packets to show and how:
 * <ul>
 *     <li>point of view: the packets received by one recorded player, plus that player's own entity;</li>
 *     <li>free camera: the union of every recorded player's view. Entities seen by several players are
 *     spawned once and destroyed when nobody sees them anymore; recorded players are driven by their own
 *     track only.</li>
 * </ul>
 */
final class ViewerPipeline {

    /** CHANGE_GAME_STATE reasons that only change visuals (rain, thunder, sounds, guardian effect). */
    private static final Set<Integer> VISUAL_GAME_STATES = Set.of(1, 2, 6, 7, 8, 9, 10);

    private final User user;
    private Integer pointOfView;

    /** Free camera: original entity id -> recorded players currently seeing it. */
    private final Map<Integer, BitSet> holders = new HashMap<>();
    /** Original entity id of each recorded player's own entity. */
    private final Map<Integer, Integer> subjectEntities = new HashMap<>();
    private final Map<Integer, Integer> entitySubjects = new HashMap<>();

    ViewerPipeline(User user) {
        this.user = user;
    }

    Integer getPointOfView() {
        return pointOfView;
    }

    void setPointOfView(Integer subject) {
        this.pointOfView = subject;
    }

    /** Original entity id of a recorded player, if their entity was seen. */
    Integer subjectEntity(int subject) {
        return subjectEntities.get(subject);
    }

    void reset() {
        holders.clear();
    }

    void onWorldChange() {
        holders.clear();
    }

    /** A recorded player stopped being captured: in free camera, they no longer keep entities visible. */
    void onCaptureStop(int subject, PacketSink sink) {
        if (pointOfView == null) {
            release(subject, sink);
        }
    }

    void accept(ReplayRecord record, PacketSink sink, boolean fast) {
        if (record instanceof ReplayRecord.Self self) {
            acceptSelf(self, sink, fast);
        } else if (record instanceof ReplayRecord.Packet packet) {
            acceptPacket(packet, sink, fast);
        }
    }

    private void acceptSelf(ReplayRecord.Self self, PacketSink sink, boolean fast) {
        if (self.type() == Server.SPAWN_ENTITY || self.type() == Server.SPAWN_PLAYER) {
            int entity = VarInts.leadingValue(self.payload());
            subjectEntities.put(self.subject(), entity);
            entitySubjects.put(entity, self.subject());
        }
        if (pointOfView != null && pointOfView != self.subject()) {
            return;
        }
        emit(self.type(), self.payload(), sink, fast);
    }

    private void acceptPacket(ReplayRecord.Packet packet, PacketSink sink, boolean fast) {
        Server type = packet.type();
        if (pointOfView != null) {
            if (!packet.isReceivedBy(pointOfView)) {
                return;
            }
            if (type == Server.RESPAWN) {
                // The recorded client cleared its entities; the viewer did not receive the respawn.
                Integer own = subjectEntities.get(pointOfView);
                sink.destroyEntities(own == null ? -1 : EntityRemapper.id(own));
                return;
            }
            emit(type, packet.payload(), sink, fast);
            return;
        }

        // Free camera
        if (type == Server.RESPAWN) {
            for (int recipient : packet.recipients()) {
                release(recipient, sink);
            }
            return;
        }
        byte[] payload = packet.payload();
        if (PacketFilters.RECORDED_PLAYER_STATE.contains(type) && entitySubjects.containsKey(VarInts.leadingValue(payload))) {
            return;
        }
        if (PacketFilters.SPAWNS.contains(type) && type != Server.SPAWN_WEATHER_ENTITY) {
            BitSet seenBy = holders.computeIfAbsent(VarInts.leadingValue(payload), id -> new BitSet());
            boolean alreadyVisible = !seenBy.isEmpty();
            for (int recipient : packet.recipients()) {
                seenBy.set(recipient);
            }
            if (alreadyVisible) {
                return;
            }
        } else if (type == Server.DESTROY_ENTITIES) {
            int[] ids = PacketCodec.decode(WrapperPlayServerDestroyEntities::new, type, payload, user).getEntityIds();
            List<Integer> gone = new ArrayList<>();
            for (int id : ids) {
                if (entitySubjects.containsKey(id)) {
                    continue;
                }
                BitSet seenBy = holders.get(id);
                if (seenBy != null) {
                    for (int recipient : packet.recipients()) {
                        seenBy.clear(recipient);
                    }
                    if (!seenBy.isEmpty()) {
                        continue;
                    }
                    holders.remove(id);
                }
                gone.add(id);
            }
            if (gone.isEmpty()) {
                return;
            }
            if (gone.size() != ids.length) {
                payload = destroyPayload(gone);
            }
        }
        emit(type, payload, sink, fast);
    }

    private void release(int subject, PacketSink sink) {
        List<Integer> gone = new ArrayList<>();
        for (Iterator<Map.Entry<Integer, BitSet>> it = holders.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, BitSet> entry = it.next();
            if (entry.getValue().get(subject)) {
                entry.getValue().clear(subject);
                if (entry.getValue().isEmpty()) {
                    it.remove();
                    gone.add(entry.getKey());
                }
            }
        }
        if (!gone.isEmpty()) {
            emit(Server.DESTROY_ENTITIES, destroyPayload(gone), sink, false);
        }
    }

    private static byte[] destroyPayload(List<Integer> ids) {
        WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(ids.stream().mapToInt(Integer::intValue).toArray());
        return PacketCodec.encodeNew(destroy);
    }

    private void emit(Server type, byte[] payload, PacketSink sink, boolean fast) {
        if (PacketFilters.NOT_REPLAYED.contains(type) || (fast && PacketFilters.TRANSIENT.contains(type))) {
            return;
        }
        if (type == Server.CHANGE_GAME_STATE && (payload.length == 0 || !VISUAL_GAME_STATES.contains(payload[0] & 0xFF))) {
            return;
        }
        EntityRemapper.Out out;
        try {
            out = EntityRemapper.transform(type, payload, user);
        } catch (RuntimeException e) {
            return; // undecodable packet: skip it rather than break the client
        }
        if (out != null) {
            sink.send(out.type(), out.payload());
        }
    }
}
