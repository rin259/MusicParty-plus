package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.config.AppProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YtDlpResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsTheCanonicalSoundCloudPageFromSearchResults() throws Exception {
        Path executable = writeExecutable("#!/bin/sh\nprintf '%s\\n' '{\"entries\":[{\"id\":\"254112129\",\"title\":\"One More Time\",\"uploader\":\"Daft Punk\",\"webpage_url\":\"https://soundcloud.com/daftpunkofficialmusic/one-more-time\"}]}'\n");

        YtDlpResolver resolver = newResolver(executable, 2);
        List<YtDlpResolver.TrackMetadata> tracks = resolver.searchSoundCloud("one more time", 1).block(Duration.ofSeconds(4));

        assertThat(tracks).singleElement().extracting(YtDlpResolver.TrackMetadata::sourceUrl)
                .isEqualTo("https://soundcloud.com/daftpunkofficialmusic/one-more-time");
    }

    @Test
    void passesConfiguredCookiesOnlyToYouTubeRequests() throws Exception {
        Path args = temporaryDirectory.resolve("args.txt");
        Path executable = writeExecutable("#!/bin/sh\nprintf '%s\\n' \"$@\" > " + args
                + "\nprintf '%s\\n' '{\"entries\":[]}'\n");
        Path cookies = temporaryDirectory.resolve("yt-cookies.txt");
        Files.writeString(cookies, "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\ttest\n");

        YtDlpResolver resolver = newResolver(executable, 5, cookies);
        resolver.searchYoutubeMusic("test", 1).block(Duration.ofSeconds(4));
        String youtubeArgs = Files.readString(args);
        assertThat(youtubeArgs)
                .contains("--cookies")
                .contains("--js-runtimes").contains("bun:/usr/local/bin/bun");
        // Each run works on a private copy so concurrent yt-dlp processes cannot
        // clobber each other's rotated session tokens.
        String cookiePath = youtubeArgs.lines()
                .dropWhile(line -> !line.equals("--cookies"))
                .skip(1)
                .findFirst()
                .orElseThrow();
        assertThat(Path.of(cookiePath).getParent())
                .isEqualTo(temporaryDirectory.toAbsolutePath());
        assertThat(Path.of(cookiePath).getFileName().toString()).startsWith("yt-cookies.run");
        assertThat(cookiePath).isNotEqualTo(cookies.toString());

        resolver.searchSoundCloud("test", 1).block(Duration.ofSeconds(4));
        assertThat(Files.readString(args)).doesNotContain("--cookies").doesNotContain("--js-runtimes");
    }

    @Test
    void terminatesAStalledResolverWithinTheConfiguredTimeout() throws Exception {
        Path executable = writeExecutable("#!/bin/sh\nexec sleep 5\n");
        YtDlpResolver resolver = newResolver(executable, 1);

        assertThatThrownBy(() -> resolver.searchYoutubeMusic("test", 1).block(Duration.ofSeconds(4)))
                .hasMessage("yt-dlp timed out during metadata query");
    }

    @Test
    void cancellingReactiveRequestTerminatesChildProcessAndReleasesItsPermit() throws Exception {
        Path pid = temporaryDirectory.resolve("pid");
        Path executable = writeExecutable("#!/bin/sh\necho $$ > " + pid + "\nexec sleep 30\n");
        var resolver = newResolver(executable, 30);
        var request = resolver.searchYoutubeMusic("test", 1).subscribe();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(pid) && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(Files.exists(pid)).isTrue();
            long processId = Long.parseLong(Files.readString(pid).trim());
            request.dispose();
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally { request.dispose(); }
    }

    @Test
    void rejectsDrmProtectedSoundCloudAudioDuringValidation() throws Exception {
        Path executable = writeExecutable("#!/bin/sh\nprintf '%s\\n' 'ERROR: [soundcloud] 1953196147: This video is DRM protected'\nexit 1\n");
        YtDlpResolver resolver = newResolver(executable, 2);

        assertThatThrownBy(() -> resolver.validateSoundCloudAudio("https://soundcloud.com/example/drm-track")
                .block(Duration.ofSeconds(4)))
                .hasMessageContaining("DRM protected");
    }

    @Test
    void acceptsPlayableSoundCloudAudioDuringValidation() throws Exception {
        Path executable = writeExecutable("#!/bin/sh\nprintf '%s\\n' '1285760224'\n");
        YtDlpResolver resolver = newResolver(executable, 2);

        resolver.validateSoundCloudAudio("https://soundcloud.com/example/playable-track")
                .block(Duration.ofSeconds(4));
    }

    @Test
    void downloadsSoundCloudHlsWithConcurrentFragments() throws Exception {
        Path audio = temporaryDirectory.resolve("stem.m4a");
        Files.writeString(audio, "audio-bytes");
        Path argsFile = temporaryDirectory.resolve("args.txt");
        Path executable = writeExecutable("#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > " + argsFile.toAbsolutePath() + "\n"
                + "printf '%s\\n' '__MUSIC_PARTY_FILE__:" + audio.toAbsolutePath() + "'\n");

        YtDlpResolver resolver = newResolver(executable, 5);
        resolver.downloadAudio("https://api.soundcloud.com/tracks/1", temporaryDirectory, "stem")
                .block(Duration.ofSeconds(10));

        String args = Files.readString(argsFile);
        assertThat(args).contains("--concurrent-fragments\n4");
    }

    private YtDlpResolver newResolver(Path executable, long timeoutSeconds) {
        return newResolver(executable, timeoutSeconds, null);
    }

    private YtDlpResolver newResolver(Path executable, long timeoutSeconds, Path cookiesFile) {
        AppProperties properties = new AppProperties();
        properties.getYtDlp().setPath(executable.toString());
        properties.getYtDlp().setTimeoutSeconds(timeoutSeconds);
        properties.getYtDlp().setDownloadTimeoutSeconds(10);
        if (cookiesFile != null) properties.getYtDlp().setCookiesFile(cookiesFile.toString());
        return new YtDlpResolver(properties, new ObjectMapper());
    }

    private Path writeExecutable(String content) throws Exception {
        Path executable = temporaryDirectory.resolve("yt-dlp-test");
        Files.writeString(executable, content);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }
}
