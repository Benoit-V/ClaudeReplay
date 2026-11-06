package fr.bebedlastreat.claudeReplay.format;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord.CaptureStart;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord.CaptureStop;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord.Marker;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord.PlayerRegistered;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord.ReplayEvent;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord.WorldChange;
import fr.bebedlastreat.claudeReplay.util.VarInts;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * Layout of a {@code .crp} replay file:
 * <pre>
 * header   : int MAGIC, int FORMAT_VERSION, int protocolVersion
 * segments : deflated blocks of records, each covering a range of ticks
 * footer   : deflated { string infoJson, int typeCount, string[] typeNames,
 *                       int segmentCount, (int startTick, int endTick, long offset, int length, int rawLength)[] }
 * trailer  : long footerOffset, int footerLength, int TRAILER_MAGIC
 * </pre>
 * A record is {@code VarInt tick, byte kind, ...} (see {@link #writeRecord}).
 */
public final class ReplayFormat {

    public static final int MAGIC = 0x43525031; // "CRP1"
    public static final int TRAILER_MAGIC = 0x43525045; // "CRPE"
    public static final int FORMAT_VERSION = 1;
    public static final int TRAILER_SIZE = 8 + 4 + 4;
    public static final String EXTENSION = ".crp";


    private static final byte KIND_PACKET = 0;
    private static final byte KIND_SELF = 1;
    private static final byte KIND_BLOCKS = 2;
    private static final byte KIND_EVENT = 3;

    private static final byte EVENT_PLAYER = 0;
    private static final byte EVENT_CAPTURE_START = 1;
    private static final byte EVENT_CAPTURE_STOP = 2;
    private static final byte EVENT_WORLD = 3;
    private static final byte EVENT_MARKER = 4;

    private ReplayFormat() {
    }

    public record SegmentInfo(int startTick, int endTick, long offset, int length, int rawLength) {
    }

    public static void writeString(DataOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        VarInts.write(out, bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInput in) throws IOException {
        byte[] bytes = new byte[VarInts.read(in)];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutput out, byte[] bytes) throws IOException {
        VarInts.write(out, bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInput in) throws IOException {
        byte[] bytes = new byte[VarInts.read(in)];
        in.readFully(bytes);
        return bytes;
    }

    public static void writeRecord(DataOutput out, ReplayRecord record, ToIntFunction<Server> typeIndex) throws IOException {
        VarInts.write(out, record.tick());
        if (record instanceof ReplayRecord.Packet packet) {
            out.writeByte(KIND_PACKET);
            VarInts.write(out, typeIndex.applyAsInt(packet.type()));
            VarInts.write(out, packet.recipients().length);
            for (int recipient : packet.recipients()) {
                VarInts.write(out, recipient);
            }
            writeBytes(out, packet.payload());
        } else if (record instanceof ReplayRecord.Self self) {
            out.writeByte(KIND_SELF);
            VarInts.write(out, self.subject());
            VarInts.write(out, typeIndex.applyAsInt(self.type()));
            writeBytes(out, self.payload());
        } else if (record instanceof ReplayRecord.Blocks blocks) {
            out.writeByte(KIND_BLOCKS);
            VarInts.write(out, blocks.positions().length);
            for (int i = 0; i < blocks.positions().length; i++) {
                out.writeLong(blocks.positions()[i]);
                VarInts.write(out, blocks.states()[i]);
            }
        } else if (record instanceof ReplayRecord.Event event) {
            out.writeByte(KIND_EVENT);
            writeEvent(out, event.event());
        }
    }

    private static void writeEvent(DataOutput out, ReplayEvent event) throws IOException {
        if (event instanceof PlayerRegistered registered) {
            out.writeByte(EVENT_PLAYER);
            VarInts.write(out, registered.index());
            out.writeLong(registered.uuid().getMostSignificantBits());
            out.writeLong(registered.uuid().getLeastSignificantBits());
            writeString(out, registered.name());
        } else if (event instanceof CaptureStart start) {
            out.writeByte(EVENT_CAPTURE_START);
            VarInts.write(out, start.index());
        } else if (event instanceof CaptureStop stop) {
            out.writeByte(EVENT_CAPTURE_STOP);
            VarInts.write(out, stop.index());
        } else if (event instanceof WorldChange change) {
            out.writeByte(EVENT_WORLD);
            VarInts.write(out, change.worldIndex());
        } else if (event instanceof Marker marker) {
            out.writeByte(EVENT_MARKER);
            writeString(out, marker.name());
            out.writeBoolean(marker.data() != null);
            if (marker.data() != null) {
                writeString(out, marker.data());
            }
        }
    }

    /**
     * Reads a record. Returns null for packets whose type is unknown to this server (kept readable for
     * forward compatibility).
     */
    public static ReplayRecord readRecord(DataInput in, IntFunction<Server> types) throws IOException {
        int tick = VarInts.read(in);
        byte kind = in.readByte();
        switch (kind) {
            case KIND_PACKET -> {
                Server type = types.apply(VarInts.read(in));
                int[] recipients = new int[VarInts.read(in)];
                for (int i = 0; i < recipients.length; i++) {
                    recipients[i] = VarInts.read(in);
                }
                byte[] payload = readBytes(in);
                return type == null ? null : new ReplayRecord.Packet(tick, type, recipients, payload);
            }
            case KIND_SELF -> {
                int subject = VarInts.read(in);
                Server type = types.apply(VarInts.read(in));
                byte[] payload = readBytes(in);
                return type == null ? null : new ReplayRecord.Self(tick, subject, type, payload);
            }
            case KIND_BLOCKS -> {
                int count = VarInts.read(in);
                long[] positions = new long[count];
                int[] states = new int[count];
                for (int i = 0; i < count; i++) {
                    positions[i] = in.readLong();
                    states[i] = VarInts.read(in);
                }
                return new ReplayRecord.Blocks(tick, positions, states);
            }
            case KIND_EVENT -> {
                return new ReplayRecord.Event(tick, readEvent(in));
            }
            default -> throw new IOException("Unknown record kind " + kind);
        }
    }

    private static ReplayEvent readEvent(DataInput in) throws IOException {
        byte type = in.readByte();
        return switch (type) {
            case EVENT_PLAYER -> new PlayerRegistered(VarInts.read(in), new UUID(in.readLong(), in.readLong()), readString(in));
            case EVENT_CAPTURE_START -> new CaptureStart(VarInts.read(in));
            case EVENT_CAPTURE_STOP -> new CaptureStop(VarInts.read(in));
            case EVENT_WORLD -> new WorldChange(VarInts.read(in));
            case EVENT_MARKER -> new Marker(readString(in), in.readBoolean() ? readString(in) : null);
            default -> throw new IOException("Unknown event " + type);
        };
    }
}
