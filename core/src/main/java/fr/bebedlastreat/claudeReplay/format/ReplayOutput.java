package fr.bebedlastreat.claudeReplay.format;

import fr.bebedlastreat.claudeReplay.api.replay.ReplayInfo;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Receives the records of a recording and produces its replay file.
 */
public interface ReplayOutput {

    /** Appends a record. Records must be given in tick order. */
    void write(ReplayRecord record);

    /**
     * Writes the replay file.
     *
     * @param info the description of the whole recording
     * @return the file and the description of what it really contains (which can be shorter than the recording)
     */
    CompletableFuture<Saved> finish(ReplayInfo info);

    /** Stops writing and deletes the file. */
    void abort();

    record Saved(Path file, ReplayInfo info) {
    }
}
