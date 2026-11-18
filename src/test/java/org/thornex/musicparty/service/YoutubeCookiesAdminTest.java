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
            .youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\tabcdef
            .youtube.com\tTRUE\t/\tTRUE\t0\tLOGIN_INFO\tabcdef
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

    @Test void keepsOnlyYouTubeAndGoogleLinesFromAWholeBrowserExport() throws Exception {
        StringBuilder export = new StringBuilder("# Netscape HTTP Cookie File\n");
        for (int i = 0; i < 6000; i++) export.append(".example").append(i).append(".com\tTRUE\t/\tFALSE\t0\tSESSION\tvalue").append(i).append("\n");
        export.append(".youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabcdef\n");
        export.append("#HttpOnly_.google.com\tTRUE\t/\tTRUE\t0\tSAPISID\tabcdef\n");
        export.append("#HttpOnly_.google.com\tTRUE\t/\tTRUE\t0\tLOGIN_INFO\tabcdef\n");
        assertThat(export.length()).isGreaterThan(200_000);

        var status = admin().save(export.toString());
        assertThat(status.configured()).isTrue();
        String stored = Files.readString(directory.resolve("yt-cookies.txt"));
        assertThat(stored).contains(".youtube.com").contains(".google.com").doesNotContain(".example0.com");
        assertThat(stored.lines().count()).isLessThan(10);
    }

    @Test void rejectsExportsWithoutYouTubeOrGoogleCookies() {
        assertThatThrownBy(() -> admin().save("# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tTRUE\t0\tSID\tx"))
                .hasMessageContaining("youtube.com");
    }

    @Test void rejectsInputThatIsNotAYouTubeCookieExport() {
        YoutubeCookiesAdmin admin = admin();
        assertThatThrownBy(() -> admin.save("   ")).hasMessageContaining("cookies.txt");
        assertThatThrownBy(() -> admin.save("hello world")).hasMessageContaining("Netscape");
        assertThatThrownBy(() -> admin.save("x".repeat(4_000_001))).hasMessageContaining("4MB");
        assertThatThrownBy(() -> admin.save("# random\n.youtube.com\tTRUE\t/\tTRUE\t0\tOTHER\tx"))
                .hasMessageContaining("SID");
    }

    @Test void rejectsExportsThatOnlyCarryScriptVisibleCookies() {
        // The shape produced by document.cookie: every Google auth cookie that is
        // HttpOnly (SID, SAPISID, LOGIN_INFO) is absent, so the file never
        // authenticates even though it looks like a real export.
        String scriptVisible = """
                # Netscape HTTP Cookie File
                .youtube.com\tTRUE\t/\tTRUE\t0\t__Secure-3PSID\tabcdef
                .youtube.com\tTRUE\t/\tTRUE\t0\t__Secure-3PAPISID\tabcdef
                .youtube.com\tTRUE\t/\tTRUE\t0\t__Secure-1PSIDTS\tabcdef
                """;
        assertThatThrownBy(() -> admin().save(scriptVisible))
                .hasMessageContaining("HttpOnly")
                .hasMessageContaining("SID")
                .hasMessageContaining("SAPISID")
                .hasMessageContaining("LOGIN_INFO");
    }

    @Test void reportsWhichAuthCookiesAreMissing() {
        YoutubeCookiesAdmin admin = admin();
        admin.save("""
                # Netscape HTTP Cookie File
                .youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabcdef
                .youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\tabcdef
                .youtube.com\tTRUE\t/\tTRUE\t0\tLOGIN_INFO\tabcdef
                """);
        assertThat(admin.status().sessionReady()).isTrue();
        assertThat(admin.status().missing()).isEmpty();
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