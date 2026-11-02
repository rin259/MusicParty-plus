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
    void terminatesAStalledResolverWithinTheConfiguredTimeout() throws Exception {
        Path executable = writeExecutable("#!/bin/sh\nexec sleep 5\n");
        YtDlpResolver resolver = newResolver(executable, 1);

        assertThatThrownBy(() -> resolver.searchYoutubeMusic("test", 1).block(Duration.ofSeconds(4)))
                .hasMessage("yt-dlp timed out during metadata query");
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

    private YtDlpResolver newResolver(Path executable, long timeoutSeconds) {
        AppProperties properties = new AppProperties();
        properties.getYtDlp().setPath(executable.toString());
        properties.getYtDlp().setTimeoutSeconds(timeoutSeconds);
        properties.getYtDlp().setDownloadTimeoutSeconds(10);
        return new YtDlpResolver(properties, new ObjectMapper());
    }

    private Path writeExecutable(String content) throws Exception {
        Path executable = temporaryDirectory.resolve("yt-dlp-test");
        Files.writeString(executable, content);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }
}
