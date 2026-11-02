package fr.bebedlastreat.claudeReplay.format;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.format.ReplayFormat.SegmentInfo;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.InflaterInputStream;

/**
 * Reads a replay file. Thread safe; decoded segments are cached.
 */
public final class ReplayFileReader implements Closeable {

    private static final int CACHED_SEGMENTS = 12;

    private final Path file;
    private final RandomAccessFile input;
    private final int protocolVersion;
    private final ReplayInfo info;
    private final Server[] types;
    private final List<String> unknownTypes = new ArrayList<>();
    private final List<SegmentInfo> segments;
    private final Map<Integer, List<ReplayRecord>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, List<ReplayRecord>> eldest) {
            return size() > CACHED_SEGMENTS;
        }
    };

    private ReplayFileReader(Path file) throws IOException {
        this.file = file;
        this.input = new RandomAccessFile(file.toFile(), "r");
        try {
            if (input.readInt() != ReplayFormat.MAGIC) {
                throw new IOException("Not a ClaudeReplay file: " + file);
            }
            int formatVersion = input.readInt();
            if (formatVersion > ReplayFormat.FORMAT_VERSION) {
                throw new IOException("Replay format " + formatVersion + " is newer than supported (" + ReplayFormat.FORMAT_VERSION + ")");
            }
            this.protocolVersion = input.readInt();

            long length = input.length();
            if (length < 12 + ReplayFormat.TRAILER_SIZE) {
                throw new IOException("Truncated replay file: " + file);
            }
            input.seek(length - ReplayFormat.TRAILER_SIZE);
            long footerOffset = input.readLong();
            int footerLength = input.readInt();
            if (input.readInt() != ReplayFormat.TRAILER_MAGIC) {
                throw new IOException("Unfinished replay file (no footer): " + file);
            }
            DataInputStream footer = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(read(footerOffset, footerLength))));
            this.info = ReplayInfoJson.read(ReplayFormat.readString(footer));
            this.types = new Server[footer.readInt()];
            for (int i = 0; i < types.length; i++) {
                String name = ReplayFormat.readString(footer);
                try {
                    types[i] = Server.valueOf(name);
                } catch (IllegalArgumentException e) {
                    unknownTypes.add(name);
                }
            }
            int segmentCount = footer.readInt();
            List<SegmentInfo> list = new ArrayList<>(segmentCount);
            for (int i = 0; i < segmentCount; i++) {
                list.add(new SegmentInfo(footer.readInt(), footer.readInt(), footer.readLong(), footer.readInt(), footer.readInt()));
            }
            this.segments = Collections.unmodifiableList(list);
        } catch (IOException | RuntimeException e) {
            input.close();
            throw e;
        }
    }

    public static ReplayFileReader open(Path file) throws IOException {
        return new ReplayFileReader(file);
    }

    public Path getFile() {
        return file;
    }

    public ReplayInfo getInfo() {
        return info;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    /** Packet types of the replay that this server does not know (their packets are skipped). */
    public List<String> getUnknownTypes() {
        return unknownTypes;
    }

    private synchronized byte[] read(long offset, int length) throws IOException {
        byte[] bytes = new byte[length];
        input.seek(offset);
        input.readFully(bytes);
        return bytes;
    }

    public List<ReplayRecord> segment(int index) throws IOException {
        synchronized (cache) {
            List<ReplayRecord> cached = cache.get(index);
            if (cached != null) {
                return cached;
            }
        }
        SegmentInfo segment = segments.get(index);
        DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(read(segment.offset(), segment.length())), new java.util.zip.Inflater(), 8192));
        List<ReplayRecord> records = new ArrayList<>();
        try {
            while (true) {
                ReplayRecord record;
                try {
                    record = ReplayFormat.readRecord(in, i -> i < types.length ? types[i] : null);
                } catch (EOFException e) {
                    break;
                }
                if (record != null) {
                    records.add(record);
                }
            }
        } finally {
            in.close();
        }
        List<ReplayRecord> result = Collections.unmodifiableList(records);
        synchronized (cache) {
            cache.put(index, result);
        }
        return result;
    }

    public Cursor cursor() {
        return new Cursor();
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    /**
     * Sequential reader over the timeline.
     */
    public final class Cursor {

        private int segmentIndex;
        private List<ReplayRecord> current;
        private int position;

        /** Feeds every not yet read record whose tick is lower or equal to {@code tick}. */
        public void forEachUntil(int tick, Consumer<ReplayRecord> consumer) throws IOException {
            while (true) {
                if (current == null) {
                    if (segmentIndex >= segments.size()) {
                        return;
                    }
                    if (segments.get(segmentIndex).startTick() > tick) {
                        return;
                    }
                    current = segment(segmentIndex);
                    position = 0;
                }
                while (position < current.size()) {
                    ReplayRecord record = current.get(position);
                    if (record.tick() > tick) {
                        return;
                    }
                    position++;
                    consumer.accept(record);
                }
                current = null;
                segmentIndex++;
            }
        }
    }
}
