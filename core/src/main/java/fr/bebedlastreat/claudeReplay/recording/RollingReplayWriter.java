package fr.bebedlastreat.claudeReplay.recording;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.User;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayMarker;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayWorld;
import fr.bebedlastreat.claudeReplay.format.ReplayFileWriter;
import fr.bebedlastreat.claudeReplay.format.ReplayFormat;
import fr.bebedlastreat.claudeReplay.format.ReplayOutput;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord;
import fr.bebedlastreat.claudeReplay.playback.PacketSink;
import fr.bebedlastreat.claudeReplay.playback.StateCompactor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Writes a replay limited to its last {@code keepTicks} ticks.
 * <p>
 * Segments are kept compressed in memory. When a segment gets older than the window, its records are folded into
 * a starting state (the same compaction as a timeline jump) and dropped. When the recording stops, the replay file
 * starts with that state, followed by the kept records, all moved so the replay begins at tick 0.
 * <p>
 * Records are serialized on the calling thread (the main thread); folding and writing happen on the IO executor,
 * which must run tasks sequentially.
 */
final class RollingReplayWriter implements ReplayOutput {

    private final Path file;
    private final Executor io;
    private final int segmentTicks;
    private final int protocolVersion;
    private final int keepTicks;
    private final Supplier<User> users;

    // calling thread
    private final Map<Server, Integer> typeIndexes = new EnumMap<>(Server.class);
    private final List<Server> types = new ArrayList<>();
    private ByteArrayOutputStream segmentBytes = new ByteArrayOutputStream();
    private DataOutputStream segmentOut = new DataOutputStream(segmentBytes);
    private int segmentStart = -1;
    private int segmentEnd = -1;

    // IO thread only
    private final Deque<Segment> kept = new ArrayDeque<>();
    private Folder folder;
    private boolean closed;

    private record Segment(int start, int end, byte[] compressed, Server[] types) {
    }

    RollingReplayWriter(Path file, Executor io, int segmentTicks, int protocolVersion, int keepTicks, Supplier<User> users) {
        this.file = file;
        this.io = io;
        this.segmentTicks = segmentTicks;
        this.protocolVersion = protocolVersion;
        this.keepTicks = keepTicks;
        this.users = users;
    }

    private int typeIndex(Server type) {
        return typeIndexes.computeIfAbsent(type, t -> {
            types.add(t);
            return types.size() - 1;
        });
    }

    @Override
    public void write(ReplayRecord record) {
        if (segmentStart >= 0 && record.tick() - segmentStart >= segmentTicks) {
            flushSegment();
        }
        if (segmentStart < 0) {
            segmentStart = record.tick();
        }
        segmentEnd = Math.max(segmentEnd, record.tick());
        try {
            ReplayFormat.writeRecord(segmentOut, record, this::typeIndex);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // in-memory stream
        }
    }

    private void flushSegment() {
        if (segmentStart < 0) {
            return;
        }
        byte[] raw = segmentBytes.toByteArray();
        int start = segmentStart;
        int end = segmentEnd;
        Server[] known = types.toArray(new Server[0]);
        segmentBytes = new ByteArrayOutputStream();
        segmentOut = new DataOutputStream(segmentBytes);
        segmentStart = -1;
        segmentEnd = -1;
        io.execute(() -> {
            if (closed) {
                return;
            }
            kept.addLast(new Segment(start, end, deflate(raw), known));
            // Fold the segments entirely out of the window; the newest one is always kept.
            while (kept.size() > 1 && kept.peekFirst().end < end - keepTicks) {
                forEachRecord(kept.pollFirst(), folder()::accept);
            }
        });
    }

    private Folder folder() {
        if (folder == null) {
            folder = new Folder(users);
        }
        return folder;
    }

    @Override
    public CompletableFuture<Saved> finish(ReplayInfo info) {
        flushSegment();
        CompletableFuture<Saved> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                closed = true;
                int end = info.durationTicks();
                int cut = Math.max(0, end - keepTicks);
                Folder state = folder();
                List<ReplayRecord> after = new ArrayList<>();
                for (Segment segment : kept) {
                    forEachRecord(segment, record -> {
                        if (record.tick() < cut) {
                            state.accept(record);
                        } else {
                            after.add(record);
                        }
                    });
                }
                kept.clear();

                // Writes synchronously on this thread.
                ReplayFileWriter out = new ReplayFileWriter(file, Runnable::run, segmentTicks, protocolVersion);
                int baseWorld = cut == 0 ? 0 : state.world;
                if (cut > 0) {
                    state.writeTo(out);
                }
                for (ReplayRecord record : after) {
                    out.write(shift(record, cut, baseWorld));
                }
                out.finish(trim(info, cut, baseWorld)).whenComplete((saved, error) -> {
                    if (error != null) {
                        future.completeExceptionally(error);
                    } else {
                        future.complete(saved);
                    }
                });
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public void abort() {
        io.execute(() -> {
            closed = true;
            kept.clear();
        });
    }

    /** The description of the kept part: it starts at {@code cut}, in the world {@code baseWorld}. */
    private static ReplayInfo trim(ReplayInfo info, int cut, int baseWorld) {
        if (cut == 0) {
            return info;
        }
        List<ReplayWorld> worlds = new ArrayList<>();
        for (int i = baseWorld; i < info.worlds().size(); i++) {
            ReplayWorld world = info.worlds().get(i);
            worlds.add(new ReplayWorld(Math.max(0, world.startTick() - cut), world.worldName(), world.mapId(), world.environment(),
                    world.x(), world.y(), world.z(), world.yaw(), world.pitch()));
        }
        List<ReplayMarker> markers = new ArrayList<>();
        for (ReplayMarker marker : info.markers()) {
            if (marker.tick() >= cut) {
                markers.add(new ReplayMarker(marker.tick() - cut, marker.name(), marker.data()));
            }
        }
        return new ReplayInfo(info.id(), info.name(), info.createdAt() + cut * 50L, Math.max(1, info.durationTicks() - cut),
                info.protocolVersion(), info.serverVersion(), info.players(), worlds, markers, info.metadata());
    }

    private static ReplayRecord shift(ReplayRecord record, int cut, int baseWorld) {
        if (cut == 0) {
            return record;
        }
        int tick = record.tick() - cut;
        if (record instanceof ReplayRecord.Packet packet) {
            return new ReplayRecord.Packet(tick, packet.type(), packet.recipients(), packet.payload());
        } else if (record instanceof ReplayRecord.Self self) {
            return new ReplayRecord.Self(tick, self.subject(), self.type(), self.payload());
        } else if (record instanceof ReplayRecord.Blocks blocks) {
            return new ReplayRecord.Blocks(tick, blocks.positions(), blocks.states());
        }
        ReplayRecord.ReplayEvent event = ((ReplayRecord.Event) record).event();
        if (event instanceof ReplayRecord.WorldChange change) {
            event = new ReplayRecord.WorldChange(change.worldIndex() - baseWorld);
        }
        return new ReplayRecord.Event(tick, event);
    }

    private static byte[] deflate(byte[] raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        Deflater deflater = new Deflater(6);
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes, deflater)) {
            out.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // in-memory stream
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    private static void forEachRecord(Segment segment, java.util.function.Consumer<ReplayRecord> consumer) {
        Server[] types = segment.types;
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(segment.compressed)))) {
            while (true) {
                ReplayRecord record;
                try {
                    record = ReplayFormat.readRecord(in, i -> i < types.length ? types[i] : null);
                } catch (EOFException e) {
                    return;
                }
                if (record != null) {
                    consumer.accept(record);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // in-memory stream
        }
    }

    /**
     * State of the recording at the start of the kept window: registered players, current world, world
     * modifications, and for each captured player what they see and their own entity.
     */
    private static final class Folder {
        final Supplier<User> users;
        final Map<Integer, ReplayRecord.PlayerRegistered> players = new TreeMap<>();
        final TreeSet<Integer> captured = new TreeSet<>();
        final Map<Long, Integer> blocks = new LinkedHashMap<>();
        final Map<Integer, StateCompactor> views = new TreeMap<>();
        final Map<Integer, StateCompactor> selves = new TreeMap<>();
        int world;

        Folder(Supplier<User> users) {
            this.users = users;
        }

        void accept(ReplayRecord record) {
            if (record instanceof ReplayRecord.Packet packet) {
                for (int recipient : packet.recipients()) {
                    StateCompactor view = views.computeIfAbsent(recipient, i -> new StateCompactor(users.get()));
                    if (packet.type() == Server.RESPAWN) {
                        view.destroyEntities(-1); // the client cleared its entities
                    } else {
                        view.send(packet.type(), packet.payload());
                    }
                }
            } else if (record instanceof ReplayRecord.Self self) {
                selves.computeIfAbsent(self.subject(), i -> new StateCompactor(users.get())).send(self.type(), self.payload());
            } else if (record instanceof ReplayRecord.Blocks change) {
                for (int i = 0; i < change.positions().length; i++) {
                    blocks.put(change.positions()[i], change.states()[i]);
                }
            } else if (record instanceof ReplayRecord.Event event) {
                ReplayRecord.ReplayEvent value = event.event();
                if (value instanceof ReplayRecord.PlayerRegistered registered) {
                    players.put(registered.index(), registered);
                } else if (value instanceof ReplayRecord.CaptureStart start) {
                    captured.add(start.index());
                } else if (value instanceof ReplayRecord.CaptureStop stop) {
                    // A new capture starts with a full resynchronisation: nothing to keep.
                    captured.remove(stop.index());
                    views.remove(stop.index());
                    selves.remove(stop.index());
                } else if (value instanceof ReplayRecord.WorldChange change) {
                    world = change.worldIndex();
                    blocks.clear();
                    views.values().forEach(StateCompactor::onWorldChange);
                    selves.values().forEach(StateCompactor::onWorldChange);
                }
            }
        }

        /** Writes the state as records at tick 0, like a capture starting with the replay. */
        void writeTo(ReplayOutput out) {
            players.values().forEach(registered -> out.write(new ReplayRecord.Event(0, registered)));
            out.write(new ReplayRecord.Event(0, new ReplayRecord.WorldChange(0)));
            if (!blocks.isEmpty()) {
                long[] positions = new long[blocks.size()];
                int[] states = new int[positions.length];
                int i = 0;
                for (Map.Entry<Long, Integer> entry : blocks.entrySet()) {
                    positions[i] = entry.getKey();
                    states[i++] = entry.getValue();
                }
                out.write(new ReplayRecord.Blocks(0, positions, states));
            }
            for (int index : captured) {
                out.write(new ReplayRecord.Event(0, new ReplayRecord.CaptureStart(index)));
                StateCompactor self = selves.get(index);
                if (self != null) {
                    flush(self, (type, payload) -> out.write(new ReplayRecord.Self(0, index, type, payload)));
                }
                StateCompactor view = views.get(index);
                if (view != null) {
                    int[] recipients = {index};
                    flush(view, (type, payload) -> out.write(new ReplayRecord.Packet(0, type, recipients, payload)));
                }
            }
        }

        private static void flush(StateCompactor compactor, java.util.function.BiConsumer<Server, byte[]> output) {
            try {
                compactor.flushTo(new PacketSink() {
                    @Override
                    public void send(Server type, byte[] payload) {
                        output.accept(type, payload);
                    }

                    @Override
                    public void destroyEntities(int keep) {
                    }
                });
            } catch (RuntimeException ignored) {
                // a part of the state that cannot be encoded is lost, the rest of the replay stays readable
            }
        }
    }
}
