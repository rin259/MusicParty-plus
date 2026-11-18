package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YoutubeCookieJarTest {
    @TempDir Path directory;

    private static final String ORIGINAL = """
            # Netscape HTTP Cookie File
            .youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabcdef
            .youtube.com\tTRUE\t/\tTRUE\t0\t__Secure-1PSIDTS\tOLD
            """;

    private static final String ROTATED = """
            # Netscape HTTP Cookie File
            .youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabcdef
            .youtube.com\tTRUE\t/\tTRUE\t0\t__Secure-1PSIDTS\tNEW
            """;

    private Path canonical() throws Exception {
        Path file = directory.resolve("yt-cookies.txt");
        Files.writeString(file, ORIGINAL);
        return file;
    }

    @Test void everyRunGetsItsOwnCopyAndPersistsRotation() throws Exception {
        Path canonical = canonical();
        YoutubeCookieJar.Run run = YoutubeCookieJar.open(canonical);
        assertThat(run).isNotNull();
        assertThat(run.file()).isNotEqualTo(canonical);
        assertThat(Files.readString(run.file())).contains("OLD");
        assertThat(Files.getPosixFilePermissions(run.file()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));

        // yt-dlp writes the rotated jar back to the private copy.
        Files.writeString(run.file(), ROTATED, StandardCharsets.UTF_8);
        run.close();

        assertThat(Files.readString(canonical)).contains("NEW");
        assertThat(Files.list(directory)
                .noneMatch(path -> path.getFileName().toString().startsWith("yt-cookies.run")))
                .isTrue();
    }

    @Test void aConcurrentRunWithAStaleCopyCannotUndoANewerRotation() throws Exception {
        Path canonical = canonical();
        YoutubeCookieJar.Run first = YoutubeCookieJar.open(canonical);
        YoutubeCookieJar.Run second = YoutubeCookieJar.open(canonical);

        Files.writeString(first.file(), ROTATED);
        first.close();
        assertThat(Files.readString(canonical)).contains("NEW");

        // The second run started before the rotation, so its copy still holds OLD.
        assertThat(Files.readString(second.file())).contains("OLD");
        second.close();

        assertThat(Files.readString(canonical)).contains("NEW");
    }

    @Test void aFreshAdminUploadIsNotOverwrittenByAnInFlightRun() throws Exception {
        Path canonical = canonical();
        YoutubeCookieJar.Run run = YoutubeCookieJar.open(canonical);

        String reexport = "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tfresh\n";
        Files.writeString(canonical, reexport);

        Files.writeString(run.file(), ROTATED);
        run.close();

        assertThat(Files.readString(canonical)).contains("fresh").doesNotContain("NEW");
    }

    @Test void missingCanonicalFileMeansAnonymousRuns() throws Exception {
        assertThat(YoutubeCookieJar.open(directory.resolve("absent.txt"))).isNull();
        assertThat(YoutubeCookieJar.open(null)).isNull();
        // Releasing a jar that was never opened must not throw.
        YoutubeCookieJar.release(null);
    }

    @Test void staleCopiesFromACrashArePruned() throws Exception {
        Path canonical = canonical();
        Path stale = directory.resolve("yt-cookies.run123.txt");
        Files.writeString(stale, ORIGINAL);
        Files.setLastModifiedTime(stale,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3 * 3600_000L));

        YoutubeCookieJar.Run run = YoutubeCookieJar.open(canonical);
        run.close();

        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.readString(canonical)).contains("OLD");
    }
}
