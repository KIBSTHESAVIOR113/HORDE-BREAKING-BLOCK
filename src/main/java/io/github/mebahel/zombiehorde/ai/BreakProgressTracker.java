package io.github.mebahel.zombiehorde.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * REFERENCE (compiled + self-tested by the reviewer, zero Bukkit dependency).
 * Shared block-break progress (FR-09 / Q6): every mob that contributes advances the SAME stage counter,
 * so several zombies break a block faster; stale entries expire after 5 s without an update.
 * O(1) "does anyone else still hold this block?" via a holder set (replaces the O(n) scan in BreakBlockGoal).
 * K must have value-based equals/hashCode (use a record such as BlockKey).
 * NOT thread-safe: call from the main server thread only (the plugin is main-thread only).
 */
public final class BreakProgressTracker<K> {

    public static final int MAX_STAGE = 10;
    public static final long STALE_EXPIRY_MS = 5000L;

    /** stage = new stage after this contribution; completed = block must now be broken (entry already removed);
     *  released = previous block this mob abandoned whose crack overlay must be reset (null if none / still held). */
    public record Result<K>(int stage, boolean completed, K released) {}

    private static final class Entry {
        int stage;
        long lastUpdatedMs;
        final Set<UUID> holders = new HashSet<>();
    }

    private final Map<K, Entry> progress = new HashMap<>();
    private final Map<UUID, K> active = new HashMap<>();
    private final LongSupplier clockMs;

    public BreakProgressTracker(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    public Result<K> contribute(UUID mob, K block) {
        K released = null;
        K previous = active.get(mob);
        if (previous != null && !previous.equals(block)) {
            released = release(mob);
        }
        Entry e = progress.computeIfAbsent(block, k -> new Entry());
        e.stage++;
        e.lastUpdatedMs = clockMs.getAsLong();
        e.holders.add(mob);
        active.put(mob, block);
        if (e.stage >= MAX_STAGE) {
            for (UUID holder : e.holders) {
                active.remove(holder);
            }
            progress.remove(block);
            return new Result<>(MAX_STAGE, true, released);
        }
        return new Result<>(e.stage, false, released);
    }

    /** Detaches mob. Returns the block whose overlay must be cleared, or null if another mob still holds it. */
    public K release(UUID mob) {
        K block = active.remove(mob);
        if (block == null) {
            return null;
        }
        Entry e = progress.get(block);
        if (e == null) {
            return null;
        }
        e.holders.remove(mob);
        if (e.holders.isEmpty()) {
            progress.remove(block);
            return block;
        }
        return null;
    }

    /** Removes entries not updated for STALE_EXPIRY_MS; returns their keys (reset overlays). Also drops orphan pointers. */
    public List<K> expire() {
        long now = clockMs.getAsLong();
        List<K> expired = new ArrayList<>();
        Iterator<Map.Entry<K, Entry>> it = progress.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, Entry> en = it.next();
            if (now - en.getValue().lastUpdatedMs > STALE_EXPIRY_MS) {
                expired.add(en.getKey());
                for (UUID holder : en.getValue().holders) {
                    active.remove(holder);
                }
                it.remove();
            }
        }
        return expired;
    }

    /** NFR-07: returns every key that currently shows a crack overlay and empties the tracker. */
    public List<K> clear() {
        List<K> keys = new ArrayList<>(progress.keySet());
        progress.clear();
        active.clear();
        return keys;
    }

    public int stageOf(K block) {
        Entry e = progress.get(block);
        return e == null ? 0 : e.stage;
    }

    public int size() {
        return progress.size();
    }
}
