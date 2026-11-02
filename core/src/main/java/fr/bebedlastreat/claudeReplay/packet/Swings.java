package fr.bebedlastreat.claudeReplay.packet;

import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation;

/**
 * Arm swing of an entity: an entity animation until 26.2, a packet of its own since 26.3.
 */
public final class Swings {

    /** Vanilla swing duration, in ticks. */
    private static final int SWING_TICKS = 6;

    private Swings() {
    }

    public record Swing(Server type, PacketWrapper<?> wrapper) {
    }

    public static Swing of(int entityId, boolean offHand) {
        if (PacketCodec.exists(Server.SWING_ANIMATION)) {
            return new Swing(Server.SWING_ANIMATION, new WrapperPlayServerSwingAnimation(entityId,
                    offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    new ItemSwingAnimation(ItemSwingAnimation.Type.WHACK, SWING_TICKS)));
        }
        return new Swing(Server.ENTITY_ANIMATION, new WrapperPlayServerEntityAnimation(entityId, offHand
                ? WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND
                : WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
    }
}
