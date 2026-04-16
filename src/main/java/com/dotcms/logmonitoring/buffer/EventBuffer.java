package com.dotcms.logmonitoring.buffer;

import com.dotcms.logmonitoring.model.LogEvent;
import com.dotmarketing.util.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Thread-safe bounded ring buffer for LogEvents.
 *
 * When the buffer is full, the oldest event is dropped to make room for the
 * newest — ensuring forward progress and preventing out-of-memory conditions
 * if the Loki shipper falls behind.
 *
 * Singleton scoped to the OSGi bundle classloader.
 */
public class EventBuffer {

    private static final int MAX_CAPACITY = 100_000;

    private static final EventBuffer INSTANCE = new EventBuffer();

    private final LinkedBlockingDeque<LogEvent> buffer =
            new LinkedBlockingDeque<>(MAX_CAPACITY);

    private EventBuffer() {}

    public static EventBuffer getInstance() {
        return INSTANCE;
    }

    /**
     * Add an event to the buffer. If the buffer is full, the oldest event is
     * dropped and a warning is logged.
     */
    public void add(final LogEvent event) {
        if (!buffer.offer(event)) {
            buffer.pollFirst();
            if (!buffer.offer(event)) {
                Logger.warn(EventBuffer.class, "EventBuffer: failed to add event after evicting oldest entry.");
            } else {
                Logger.warn(EventBuffer.class, "EventBuffer full — oldest event dropped to make room.");
            }
        }
    }

    /**
     * Drain all buffered events into a list and clear the buffer.
     * The returned list is safe to process outside of any lock.
     */
    public List<LogEvent> drainAll() {
        final List<LogEvent> events = new ArrayList<>(buffer.size());
        buffer.drainTo(events);
        return events;
    }

    public int size() {
        return buffer.size();
    }
}
