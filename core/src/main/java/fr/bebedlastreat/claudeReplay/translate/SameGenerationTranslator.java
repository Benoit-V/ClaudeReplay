package fr.bebedlastreat.claudeReplay.translate;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import fr.bebedlastreat.claudeReplay.format.ReplayRecord;
import fr.bebedlastreat.claudeReplay.packet.PacketCodec;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reads a replay recorded on an older server of the same generation (both modern): the packets keep their
 * layout for the most part, only block states change ids. Packets are passed as they are.
 */
final class SameGenerationTranslator implements Translator {

    private final BlockStates blocks;

    SameGenerationTranslator(ServerVersion recorded, Supplier<User> users) {
        this.blocks = new BlockStates(recorded.toClientVersion(), PacketCodec.serverVersion().toClientVersion());
    }

    @Override
    public void translate(ReplayRecord record, Consumer<ReplayRecord> out) {
        if (record instanceof ReplayRecord.Blocks recordBlocks) {
            int[] states = new int[recordBlocks.states().length];
            for (int i = 0; i < states.length; i++) {
                states[i] = blocks.convert(recordBlocks.states()[i]);
            }
            out.accept(new ReplayRecord.Blocks(recordBlocks.tick(), recordBlocks.positions(), states));
        } else {
            out.accept(record);
        }
    }
}
