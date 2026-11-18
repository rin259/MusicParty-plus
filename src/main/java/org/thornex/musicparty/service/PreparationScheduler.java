package org.thornex.musicparty.service;

import java.util.*;
import java.util.function.LongSupplier;
import org.thornex.musicparty.service.LocalCacheService.DownloadPriority;

/** Bounded admission and dispatch. No I/O or application callbacks under this monitor. */
final class PreparationScheduler<T> {
    record Demand(String id, String room, String user, DownloadPriority priority) {}
    record Dispatch<T>(String id, T payload, long queuedMillis) {}
    record Snapshot(int waiting, int active, int retryBackoff, int sourceCooldown, Map<String, Integer> activeBySource) {}
    private record Owner(String room, String user, DownloadPriority priority) {}
    private static final class Job<T> {
        final String id; final T payload; final long sequence;
        long enqueuedAt;
        boolean active;
        long readyAt;
        boolean retry;
        Job(String id, T payload, long at, long sequence) {
            this.id = id; this.payload = payload; this.enqueuedAt = at; this.sequence = sequence;
        }
    }
    private final LinkedHashMap<String, Job<T>> jobs = new LinkedHashMap<>();
    private final Map<String, Map<String, Demand>> rooms = new HashMap<>();
    private final Map<String, DownloadPriority> standalone = new HashMap<>();
    private final Map<String, Integer> bySource = new HashMap<>();
    private final Map<String, Long> sourceReadyAt = new HashMap<>();
    private final Map<String, Long> roomTurns = new HashMap<>();
    private final Map<String, Map<String, Long>> userTurns = new HashMap<>();
    private final int concurrency, perSource, ytConcurrency, maxPending;
    private final LongSupplier clock;
    private long sequence, turn;
    private int active, activeYt;

    PreparationScheduler(int concurrency, int perSource, int ytConcurrency, int maxPending, LongSupplier clock) {
        this.concurrency = Math.max(2, concurrency);
        this.perSource = Math.max(1, perSource);
        this.ytConcurrency = Math.max(1, ytConcurrency);
        this.maxPending = Math.max(1, maxPending);
        this.clock = clock;
    }
    static String source(String id) {
        int split = id.indexOf('_');
        return split > 0 ? id.substring(0, split) : "bilibili";
    }
    private boolean yt(String id) { return Set.of("soundcloud", "ytmusic").contains(source(id)); }

    /** Replace a room's demand snapshot; shared jobs survive while another room needs them. */
    synchronized List<String> replace(String room, Collection<Demand> demands) {
        Map<String, Demand> next = new HashMap<>();
        for (Demand d : demands) next.merge(d.id(), d, (a, b) -> a.priority().rank <= b.priority().rank ? a : b);
        if (next.isEmpty()) {
            rooms.remove(room); roomTurns.remove(room); userTurns.remove(room);
        } else rooms.put(room, next);
        Map<String, Long> turns = userTurns.get(room);
        if (turns != null) {
            Set<String> users = new HashSet<>();
            next.values().forEach(d -> users.add(d.user()));
            turns.keySet().retainAll(users);
        }
        List<String> removed = new ArrayList<>();
        jobs.values().removeIf(job -> {
            if (!job.active && owners(job.id).isEmpty()) { removed.add(job.id); return true; }
            return false;
        });
        return removed;
    }
    synchronized boolean demanded(String id) { return rooms.values().stream().anyMatch(m -> m.containsKey(id)); }
    synchronized Set<String> interestedRooms(String id) {
        Set<String> result = new HashSet<>();
        rooms.forEach((room, demand) -> { if (demand.containsKey(id)) result.add(room); });
        return Set.copyOf(result);
    }
    synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        int backoff = 0, cooling = 0;
        for (Job<T> job : jobs.values()) {
            if (job.active) continue;
            if (job.readyAt > now) backoff++;
            else if (sourceReadyAt.getOrDefault(source(job.id), 0L) > now) cooling++;
        }
        return new Snapshot(pending(), active, backoff, cooling, Map.copyOf(bySource));
    }
    synchronized void promote(String id, DownloadPriority priority) {
        if (standalone.containsKey(id)) standalone.merge(id, priority, (a, b) -> a.rank <= b.rank ? a : b);
        for (Map<String, Demand> demand : rooms.values()) demand.computeIfPresent(id, (key, old) ->
                new Demand(id, old.room(), old.user(), old.priority().rank <= priority.rank ? old.priority() : priority));
    }
    synchronized boolean submit(String id, T payload, DownloadPriority priority) {
        if (!demanded(id)) standalone.merge(id, priority,
                (a, b) -> a.rank <= b.rank ? a : b);
        if (jobs.containsKey(id)) { promote(id, priority); return true; }
        if (pending() >= maxPending) { standalone.remove(id); return false; }
        jobs.put(id, new Job<>(id, payload, clock.getAsLong(), ++sequence));
        return true;
    }
    private List<Owner> owners(String id) {
        List<Owner> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Demand>> entry : rooms.entrySet()) {
            Demand d = entry.getValue().get(id);
            if (d != null) result.add(new Owner(entry.getKey(), d.user(), d.priority()));
        }
        if (standalone.containsKey(id)) result.add(new Owner("~standalone", id, standalone.get(id)));
        return result;
    }
    private long roomTurn(Owner owner) { return roomTurns.getOrDefault(owner.room(), 0L); }
    private long userTurn(Owner owner) { return userTurns.getOrDefault(owner.room(), Map.of()).getOrDefault(owner.user(), 0L); }
    private int compare(Owner a, Owner b) {
        int n = Integer.compare(a.priority().rank, b.priority().rank);
        if (n == 0) n = Long.compare(roomTurn(a), roomTurn(b));
        if (n == 0) n = Long.compare(userTurn(a), userTurn(b));
        return n;
    }
    private int compareJobs(Job<T> a, Owner ownerA, Job<T> b, Owner ownerB) {
        int n = Integer.compare(ownerA.priority().rank, ownerB.priority().rank);
        if (n == 0) n = Boolean.compare(a.retry, b.retry);
        if (n == 0) n = compare(ownerA, ownerB);
        return n != 0 ? n : Long.compare(a.sequence, b.sequence);
    }
    synchronized Dispatch<T> poll() {
        if (active >= concurrency || pending() == 0) return null;
        long now = clock.getAsLong();
        Job<T> chosen = null; Owner winner = null;
        for (Job<T> job : jobs.values()) {
            String source = source(job.id);
            if (job.active || bySource.getOrDefault(source, 0) >= perSource
                    || Math.max(job.readyAt, sourceReadyAt.getOrDefault(source, 0L)) > now
                    || (yt(job.id) && activeYt >= ytConcurrency)) continue;
            Owner owner = owners(job.id).stream().min(this::compare).orElse(null);
            if (owner == null) continue;
            // One lane is reserved for a room's near-playback candidates.
            if (owner.priority() != DownloadPriority.URGENT && active >= concurrency - 1) continue;
            if (winner == null || compareJobs(job, owner, chosen, winner) < 0) {
                chosen = job; winner = owner;
            }
        }
        if (chosen == null) return null;
        chosen.active = true; active++;
        bySource.merge(source(chosen.id), 1, Integer::sum);
        if (yt(chosen.id)) activeYt++;
        roomTurns.put(winner.room(), ++turn);
        if (rooms.containsKey(winner.room())) {
            userTurns.computeIfAbsent(winner.room(), k -> new HashMap<>()).put(winner.user(), turn);
        }
        return new Dispatch<>(chosen.id, chosen.payload, Math.max(0, now - chosen.enqueuedAt));
    }
    private void release(Job<T> job, long cooldownMillis) {
        String source = source(job.id);
        active--;
        bySource.computeIfPresent(source, (key, count) -> count == 1 ? null : count - 1);
        if (yt(job.id)) activeYt--;
        sourceReadyAt.merge(source, clock.getAsLong() + Math.max(0, cooldownMillis), Math::max);
    }
    synchronized void complete(String id, long cooldownMillis) {
        Job<T> job = jobs.remove(id);
        standalone.remove(id);
        if (job != null && job.active) release(job, cooldownMillis);
    }
    synchronized boolean defer(String id, long delayMillis, long cooldownMillis) {
        Job<T> job = jobs.get(id);
        // If retry would displace an admitted waiter, finish this attempt instead.
        // On rejection the caller still owns the active job and must complete it.
        if (job == null || !job.active || pending() >= maxPending || owners(id).isEmpty()) return false;
        release(job, cooldownMillis);
        job.active = false; job.retry = true;
        job.enqueuedAt = clock.getAsLong();
        job.readyAt = job.enqueuedAt + Math.max(1, delayMillis);
        return true;
    }
    synchronized int pending() { return jobs.size() - active; }
    synchronized int active() { return active; }
    synchronized boolean contains(String id) { return jobs.containsKey(id); }
    synchronized void penalize(String id, long millis) {
        sourceReadyAt.merge(source(id), clock.getAsLong() + millis, Math::max);
    }
}
