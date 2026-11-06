package fr.bebedlastreat.claudeReplay.format;

import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;
import fr.bebedlastreat.claudeReplay.format.ReplayFormat.SegmentInfo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes a replay file. Records are serialized on the calling thread (the main thread), segments are compressed
 * and written on the given IO executor, which must run tasks sequentially.
 */
public final class ReplayFileWriter implements ReplayOutput {

    private static final int MAX_SEGMENT_BYTES = 4 * 1024 * 1024;

    private final Path file;
    private final Executor io;
    private final int segmentTicks;
    private final RandomAccessFile output;

    private final Map<Server, Integer> typeIndexes = new EnumMap<>(Server.class);
    private final List<String> typeNames = new ArrayList<>();

    private ByteArrayOutputStream segmentBytes = new ByteArrayOutputStream();
    private DataOutputStream segmentOut = new DataOutputStream(segmentBytes);
    private int segmentStart = -1;
    private int segmentEnd = -1;

    // IO thread only
    private final List<SegmentInfo> segments = new ArrayList<>();
    private long position;
    private volatile IOException failure;
    private boolean closed;

    public ReplayFileWriter(Path file, Executor io, int segmentTicks, int protocolVersion) throws IOException {
        this.file = file;
        this.io = io;
        this.segmentTicks = segmentTicks;
        Files.createDirectories(file.getParent());
        this.output = new RandomAccessFile(file.toFile(), "rw");
        output.setLength(0);
        output.writeInt(ReplayFormat.MAGIC);
        output.writeInt(ReplayFormat.FORMAT_VERSION);
        output.writeInt(protocolVersion);
        position = output.getFilePointer();
    }

    public Path getFile() {
        return file;
    }

    private int typeIndex(Server type) {
        return typeIndexes.computeIfAbsent(type, t -> {
            typeNames.add(t.name());
            return typeNames.size() - 1;
        });
    }

    @Override
    public void write(ReplayRecord record) {
        if (segmentStart >= 0 && (record.tick() - segmentStart >= segmentTicks || segmentBytes.size() >= MAX_SEGMENT_BYTES)) {
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
        segmentBytes = new ByteArrayOutputStream();
        segmentOut = new DataOutputStream(segmentBytes);
        segmentStart = -1;
        segmentEnd = -1;
        io.execute(() -> {
            if (failure != null || closed) {
                return;
            }
            try {
                byte[] compressed = deflate(raw);
                output.seek(position);
                output.write(compressed);
                segments.add(new SegmentInfo(start, end, position, compressed.length, raw.length));
                position += compressed.length;
            } catch (IOException e) {
                failure = e;
            }
        });
    }

    private static byte[] deflate(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        Deflater deflater = new Deflater(6);
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes, deflater)) {
            out.write(raw);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    /** Writes the last segment and the footer, then closes the file. */
    @Override
    public CompletableFuture<Saved> finish(ReplayInfo info) {
        flushSegment();
        List<String> names = List.copyOf(typeNames);
        CompletableFuture<Saved> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                if (failure != null) {
                    throw failure;
                }
                ByteArrayOutputStream footerRaw = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(footerRaw);
                ReplayFormat.writeString(out, ReplayInfoJson.write(info));
                out.writeInt(names.size());
                for (String name : names) {
                    ReplayFormat.writeString(out, name);
                }
                out.writeInt(segments.size());
                for (SegmentInfo segment : segments) {
                    out.writeInt(segment.startTick());
                    out.writeInt(segment.endTick());
                    out.writeLong(segment.offset());
                    out.writeInt(segment.length());
                    out.writeInt(segment.rawLength());
                }
                out.flush();
                byte[] footer = deflate(footerRaw.toByteArray());
                output.seek(position);
                output.write(footer);
                output.writeLong(position);
                output.writeInt(footer.length);
                output.writeInt(ReplayFormat.TRAILER_MAGIC);
                output.setLength(output.getFilePointer());
                output.close();
                closed = true;
                future.complete(new Saved(file, info));
            } catch (Throwable t) {
                closeQuietly();
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public void abort() {
        io.execute(() -> {
            closeQuietly();
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        });
    }

    private void closeQuietly() {
        closed = true;
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }
}
