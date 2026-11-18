package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Gives every yt-dlp run its own copy of the YouTube cookie jar and merges the
 * rotated session tokens back afterwards.
 *
 * <p>Why this exists: a signed-in YouTube session rotates {@code __Secure-1PSIDTS}
 * and friends on nearly every response, and yt-dlp writes the updated jar back to
 * whatever file it was handed. With several concurrent runs sharing one file the
 * last process to exit wins, which can put a stale rotation back on disk. Google
 * then sees a token chain it already replaced and drops the session, so a working
 * export dies minutes after the queue gets busy.</p>
 *
 * <p>Each run therefore works on a private copy. On exit the copy is promoted to
 * the canonical file only if the canonical file still matches the snapshot taken
 * when the run started; if another run (or an admin upload) already moved it
 * forward, the newer content wins and our copy is discarded.</p>
 */
@Slf4j
public final class YoutubeCookieJar {
    private static final String PREFIX = "yt-cookies.run";
    private static final String SUFFIX = ".txt";
    private static final Duration STALE_AFTER = Duration.ofHours(2);

    /**
     * The cookies that carry the login itself. Google answers a rejected session
     * with Set-Cookie clears for these, and yt-dlp faithfully writes the deletion
     * out; a single failed fetch then erases a working export for good. Keep them
     * unless the operator uploads a new file.
     */
    private static final Set<String> LOGIN_COOKIES = Set.of(
            "SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO",
            "__Secure-1PSID", "__Secure-1PAPISID", "__Secure-3PSID", "__Secure-3PAPISID",
            "SIDCC", "__Secure-1PSIDCC", "__Secure-3PSIDCC");

    private YoutubeCookieJar() {}

    /** A private cookie file for one yt-dlp run. Always close it. */
    public static final class Run implements AutoCloseable {
        private final Path canonical;
        private final Path copy;
        private final byte[] snapshot;
        private boolean closed;

        private Run(Path canonical, Path copy, byte[] snapshot) {
            this.canonical = canonical;
            this.copy = copy;
            this.snapshot = snapshot;
        }

        public Path file() {
            return copy;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            synchronized (YoutubeCookieJar.class) {
                try {
                    merge(canonical, copy, snapshot);
                } catch (IOException error) {
                    log.warn("Could not merge rotated YouTube cookies back: {}", error.toString());
                } finally {
                    try {
                        Files.deleteIfExists(copy);
                    } catch (IOException ignored) {
                        // A leftover copy is pruned on the next open.
                    }
                }
            }
        }
    }

    /**
     * Opens a private copy of {@code canonical}, or returns {@code null} when the
     * canonical file is missing/unreadable so callers can fall back to anonymous runs.
     */
    public static Run open(Path canonical) {
        if (canonical == null || !YoutubeCookieFile.usable(canonical)) return null;
        synchronized (YoutubeCookieJar.class) {
            try {
                Path parent = canonical.toAbsolutePath().getParent();
                pruneStale(parent);
                byte[] snapshot = Files.readAllBytes(canonical);
                Path copy = Files.createTempFile(parent, PREFIX, SUFFIX);
                restrict(copy);
                Files.write(copy, snapshot);
                return new Run(canonical, copy, snapshot);
            } catch (IOException error) {
                log.warn("Could not stage YouTube cookie jar: {}", error.toString());
                return null;
            }
        }
    }

    /** Releases a run opened by {@link #open(Path)}; null-safe. */
    public static void release(Run run) {
        if (run != null) run.close();
    }

    private static void merge(Path canonical, Path copy, byte[] snapshot) throws IOException {
        byte[] current = Files.readAllBytes(canonical);
        if (!Arrays.equals(current, snapshot)) {
            // Someone else already promoted a newer rotation (or the admin uploaded
            // a fresh export). Never overwrite that with our older copy.
            return;
        }
        byte[] updated = preserveLoginCookies(new String(snapshot, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(copy), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        if (Arrays.equals(updated, snapshot)) return;

        Path parent = canonical.toAbsolutePath().getParent();
        Path staged = Files.createTempFile(parent, PREFIX, ".merge");
        try {
            Files.write(staged, updated);
            restrict(staged);
            Files.move(staged, canonical, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Rotated YouTube session cookies persisted ({} → {} bytes)",
                    snapshot.length, updated.length);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * Re-adds any login cookie that the run dropped. Rotation updates values in
     * place, so a vanished name means the server cleared it, not that it moved.
     */
    static String preserveLoginCookies(String snapshot, String updated) {
        Map<String, String> updatedLines = index(updated);
        List<String> restored = new ArrayList<>();
        for (String name : LOGIN_COOKIES) {
            if (!updatedLines.containsKey(name)) {
                String original = index(snapshot).get(name);
                if (original != null) restored.add(original);
            }
        }
        if (restored.isEmpty()) return updated;
        StringBuilder out = new StringBuilder(updated);
        if (!updated.isEmpty() && !updated.endsWith("\n")) out.append('\n');
        for (String line : restored) out.append(line).append('\n');
        return out.toString();
    }

    /** name → raw line, for the cookies a Netscape file actually declares. */
    private static Map<String, String> index(String content) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (String line : content.replace("\r\n", "\n").split("\n")) {
            String bare = line.startsWith("#HttpOnly_") ? line.substring("#HttpOnly_".length()) : line;
            if (bare.isBlank() || bare.startsWith("#")) continue;
            String[] parts = bare.split("\t");
            if (parts.length >= 7) byName.put(parts[5], line);
        }
        return byName;
    }

    private static void pruneStale(Path directory) {
        if (directory == null) return;
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        List<Path> stale;
        try (Stream<Path> files = Files.list(directory)) {
            stale = files
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
                    })
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
                        } catch (IOException ignored) {
                            return false;
                        }
                    })
                    .toList();
        } catch (IOException error) {
            return;
        }
        for (Path path : stale) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Best effort: a credential copy that outlives a crash is still owner-only.
            }
        }
    }

    private static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems keep their defaults.
        }
    }

    /** Test seam: the on-disk text of the jar, decoded as UTF-8. */
    static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
