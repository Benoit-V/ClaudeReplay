package fr.bebedlastreat.claudeReplay.translate;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts global block state ids from a recorded version to the server's version, through the block state
 * names known by PacketEvents.
 */
final class BlockStates {

    private final ClientVersion from;
    private final ClientVersion to;
    private final Map<Integer, Integer> cache = new HashMap<>();

    BlockStates(ClientVersion from, ClientVersion to) {
        this.from = from;
        this.to = to;
    }

    /** The state in the server's version, -1 if it has no equivalent. */
    int convert(int state) {
        if (from == to) {
            return state;
        }
        Integer cached = cache.get(state);
        if (cached != null) {
            return cached;
        }
        int converted;
        try {
            WrappedBlockState recorded = WrappedBlockState.getByGlobalId(from, state, false);
            WrappedBlockState target = WrappedBlockState.getByString(to, recorded.toString(), false);
            if (target == null || (target.getGlobalId() == 0 && recorded.getGlobalId() != 0)) {
                // Unknown properties combination: fall back to the default state of the same block.
                target = WrappedBlockState.getDefaultState(to, recorded.getType(), false);
            }
            converted = target == null ? -1 : target.getGlobalId();
        } catch (RuntimeException e) {
            converted = -1;
        }
        cache.put(state, converted);
        return converted;
    }

    /**
     * Converts the data of a 1.8 block break effect (block id | metadata << 12) to a state id of the server.
     */
    int convertLegacyEffect(int data) {
        int id = data & 0xFFF;
        int meta = (data >> 12) & 0xF;
        return convert((id << 4) | meta);
    }
}
