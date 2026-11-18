package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.config.AppProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YoutubeCookiesAdminTest {
    @TempDir Path directory;

    private YoutubeCookiesAdmin admin() {
        AppProperties properties = new AppProperties();
        properties.getYtDlp().setCookiesFile(directory.resolve("yt-cookies.txt").toString());
        return new YoutubeCookiesAdmin(properties);
    }

    private static final String VALID = """
            # Netscape HTTP Cookie File
            .youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabcdef
            .youtube.com\tTRUE\t/\tTRUE\t0\tHSID\tabcdef
            """;

    @Test void savesOwnerOnlyAndReportsStatusWithoutLeakingContent() throws Exception {
        YoutubeCookiesAdmin admin = admin();
        assertThat(admin.status().configured()).isFalse();

        var status = admin.save(VALID);
        assertThat(status.configured()).isTrue();
        assertThat(status.bytes()).isGreaterThan(0);
        assertThat(status.updatedAt()).isGreaterThan(0);

        Path file = directory.resolve("yt-cookies.txt");
        assertThat(Files.readString(file)).contains(".youtube.com").contains("SID");
        assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(status.toString()).doesNotContain("abcdef");
    }

    @Test void rejectsInputThatIsNotAYouTubeCookieExport() {
        YoutubeCookiesAdmin admin = admin();
        assertThatThrownBy(() -> admin.save("   ")).hasMessageContaining("cookies.txt");
        assertThatThrownBy(() -> admin.save("hello world")).hasMessageContaining("Netscape");
        assertThatThrownBy(() -> admin.save("# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tTRUE\t0\tSID\tx"))
                .hasMessageContaining("youtube.com");
        assertThatThrownBy(() -> admin.save("# random\n.youtube.com\tTRUE\t/\tTRUE\t0\tOTHER\tx"))
                .hasMessageContaining("SID");
    }

    @Test void clearRemovesTheFileAndYtDlpStopsUsingIt() {
        YoutubeCookiesAdmin admin = admin();
        admin.save(VALID);
        Path file = directory.resolve("yt-cookies.txt");
        assertThat(YoutubeCookieFile.usable(file)).isTrue();

        admin.clear();
        assertThat(admin.status().configured()).isFalse();
        assertThat(YoutubeCookieFile.usable(file)).isFalse();
    }
}