package fr.bebedlastreat.claudeReplay.translate;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Converts the records of a replay made on an older server to the format of the running server. A translator
 * follows one read of the timeline from its start (it remembers the entities it saw).
 */
public interface Translator {

    /** Emits the converted records (none to drop the record). */
    void translate(ReplayRecord record, Consumer<ReplayRecord> out);

    /** Translators reading a replay recorded with the given protocol. */
    static Supplier<Translator> forProtocol(int recordedProtocol, Supplier<User> user) {
        ServerVersion server = PacketCodec.serverVersion();
        if (recordedProtocol == server.getProtocolVersion()) {
            return () -> (record, out) -> out.accept(record);
        }
        ServerVersion recorded = PacketCodec.versionOf(recordedProtocol);
        if (recorded == null || recorded.isNewerThan(server)) {
            throw new IllegalStateException("Cannot read a replay of protocol " + recordedProtocol + " on this server");
        }
        if (recorded.isOlderThan(ServerVersion.V_1_9) && server.isNewerThanOrEquals(ServerVersion.V_26_1)) {
            return () -> new LegacyToModernTranslator(recorded, user);
        }
        // Same generation of packets: only the block state ids need converting.
        return () -> new SameGenerationTranslator(recorded, user);
    }
}
