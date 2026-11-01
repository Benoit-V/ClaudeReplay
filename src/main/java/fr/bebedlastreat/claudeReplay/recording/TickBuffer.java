package fr.bebedlastreat.claudeReplay.recording;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Holds the records of the last few ticks before they are written, so that:
 * <ul>
 *     <li>packets arriving late from the network threads still land in the right tick;</li>
 *     <li>identical packets received by several players are stored once with all their recipients.</li>
 * </ul>
 * Main thread only.
 */
final class TickBuffer {

    /** A packet may be merged with an identical one recorded up to this many ticks before. */
    private static final int DEDUPLICATION_WINDOW = 1;

    private final TreeMap<Integer, Bucket> buckets = new TreeMap<>();
    private final Map<PayloadKey, List<PendingPacket>> recentPackets = new HashMap<>();
    private final Map<Long, int[]> recentBlocks = new HashMap<>(); // pos -> {state, tick}
    private final Map<Integer, Long> lastSequence = new HashMap<>(); // recipient -> seq of their last packet
    private long sequence;
    private int sealedUntil = -1;

    private Bucket bucket(int tick) {
        return buckets.computeIfAbsent(tick, Bucket::new);
    }

    private int clampTick(int tick) {
        return Math.max(tick, sealedUntil + 1);
    }

    void addPacket(int tick, int recipient, Server type, byte[] payload) {
        tick = clampTick(tick);
        PayloadKey key = new PayloadKey(type, payload);
        List<PendingPacket> candidates = recentPackets.get(key);
        long last = lastSequence.getOrDefault(recipient, -1L);
        if (candidates != null) {
            for (PendingPacket candidate : candidates) {
                // Only merge when it keeps the order of the packets received by this recipient.
                if (candidate.tick >= tick - DEDUPLICATION_WINDOW && candidate.tick <= tick
                        && !candidate.recipients.get(recipient) && candidate.sequence > last) {
                    candidate.recipients.set(recipient);
                    lastSequence.put(recipient, candidate.sequence);
                    return;
                }
            }
        }
        PendingPacket packet = new PendingPacket(sequence++, tick, key);
        packet.recipients.set(recipient);
        bucket(tick).entries.add(packet);
        recentPackets.computeIfAbsent(key, k -> new ArrayList<>(2)).add(packet);
        lastSequence.put(recipient, packet.sequence);
    }

    void addSelf(int tick, int subject, Server type, byte[] payload) {
        tick = clampTick(tick);
        bucket(tick).entries.add(new ReplayRecord.Self(tick, subject, type, payload));
    }

    void addEvent(int tick, ReplayRecord.ReplayEvent event) {
        tick = clampTick(tick);
        bucket(tick).entries.add(new ReplayRecord.Event(tick, event));
    }

    /** Adds a world modification, ignoring it if the same state was just recorded at this position. */
    void addBlock(int tick, long position, int state) {
        tick = clampTick(tick);
        int[] recent = recentBlocks.get(position);
        if (recent != null && recent[0] == state && recent[1] >= tick - DEDUPLICATION_WINDOW) {
            return;
        }
        recentBlocks.put(position, new int[]{state, tick});
        bucket(tick).blocks.put(position, state);
    }

    /** Emits, in order, every record whose tick is lower or equal to {@code tick}. */
    void seal(int tick, Consumer<ReplayRecord> output) {
        while (!buckets.isEmpty() && buckets.firstKey() <= tick) {
            Bucket bucket = buckets.pollFirstEntry().getValue();
            sealedUntil = Math.max(sealedUntil, bucket.tick);
            if (!bucket.blocks.isEmpty()) {
                long[] positions = new long[bucket.blocks.size()];
                int[] states = new int[positions.length];
                int i = 0;
                for (Map.Entry<Long, Integer> entry : bucket.blocks.entrySet()) {
                    positions[i] = entry.getKey();
                    states[i++] = entry.getValue();
                }
                output.accept(new ReplayRecord.Blocks(bucket.tick, positions, states));
            }
            for (Object entry : bucket.entries) {
                if (entry instanceof PendingPacket packet) {
                    output.accept(new ReplayRecord.Packet(bucket.tick, packet.key.type, packet.recipients.stream().toArray(), packet.key.payload));
                    List<PendingPacket> list = recentPackets.get(packet.key);
                    if (list != null) {
                        list.remove(packet);
                        if (list.isEmpty()) {
                            recentPackets.remove(packet.key);
                        }
                    }
                } else {
                    output.accept((ReplayRecord) entry);
                }
            }
        }
        sealedUntil = Math.max(sealedUntil, tick);
        int limit = sealedUntil - DEDUPLICATION_WINDOW;
        for (Iterator<int[]> it = recentBlocks.values().iterator(); it.hasNext(); ) {
            if (it.next()[1] < limit) {
                it.remove();
            }
        }
    }

    private static final class Bucket {
        final int tick;
        final List<Object> entries = new ArrayList<>();
        final Map<Long, Integer> blocks = new LinkedHashMap<>();

        Bucket(int tick) {
            this.tick = tick;
        }
    }

    private static final class PendingPacket {
        final long sequence;
        final int tick;
        final PayloadKey key;
        final BitSet recipients = new BitSet();

        PendingPacket(long sequence, int tick, PayloadKey key) {
            this.sequence = sequence;
            this.tick = tick;
            this.key = key;
        }
    }

    private static final class PayloadKey {
        final Server type;
        final byte[] payload;
        final int hash;

        PayloadKey(Server type, byte[] payload) {
            this.type = type;
            this.payload = payload;
            this.hash = 31 * type.hashCode() + Arrays.hashCode(payload);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PayloadKey other && other.hash == hash && other.type == type && Arrays.equals(other.payload, payload);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
