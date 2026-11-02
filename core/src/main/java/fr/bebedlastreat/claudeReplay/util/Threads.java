package fr.bebedlastreat.claudeReplay.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class Threads {

    private Threads() {
    }

    /** Daemon threads named {@code prefix + n}. */
    public static ThreadFactory daemon(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
