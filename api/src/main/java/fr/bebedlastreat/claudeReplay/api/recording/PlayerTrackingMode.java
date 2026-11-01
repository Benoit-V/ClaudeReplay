package fr.bebedlastreat.claudeReplay.api.recording;

/**
 * How the list of recorded players evolves during a recording.
 */
public enum PlayerTrackingMode {

    /**
     * The list is only changed through {@link Recording#addPlayer} / {@link Recording#removePlayer}.
     */
    MANUAL,

    /**
     * The list follows the players present in the recorded world: players entering the world are added,
     * players leaving it are removed. Manual changes are still allowed but may be overridden.
     */
    WORLD
}
