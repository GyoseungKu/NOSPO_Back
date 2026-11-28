package kr.nospo.book.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

/** Bounded process-local cache for short-lived provider results and continuation state. */
public final class BookSearchCache<K, V> {
    private record Entry<V>(Instant expiresAt, V value) {}

    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>();
    private final Clock clock;
    private final Duration ttl;
    private final int capacity;

    public BookSearchCache(Clock clock, Duration ttl, int capacity) {
        this.clock = clock;
        this.ttl = ttl;
        this.capacity = capacity;
    }

    public synchronized V get(K key) {
        var entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt().isAfter(clock.instant())) return entry.value();
        entries.remove(key);
        return null;
    }

    public synchronized void put(K key, V value) {
        entries.remove(key);
        while (entries.size() >= capacity) entries.pollFirstEntry();
        entries.put(key, new Entry<>(clock.instant().plus(ttl), value));
    }

    public synchronized void prune() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
    }
}
