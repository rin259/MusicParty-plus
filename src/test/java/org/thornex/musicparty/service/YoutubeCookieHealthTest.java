package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class YoutubeCookieHealthTest {
    @TempDir Path directory;

    private YoutubeCookiesAdmin cookies() {
        AppProperties properties = new AppProperties();
        properties.getYtDlp().setCookiesFile(directory.resolve("yt-cookies.txt").toString());
        return new YoutubeCookiesAdmin(properties);
    }

    private void seedCookies(YoutubeCookiesAdmin admin) {
        admin.save("""
                # Netscape HTTP Cookie File
                .youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabcdef
                .youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\tabcdef
                .youtube.com\tTRUE\t/\tTRUE\t0\tLOGIN_INFO\tabcdef
                """);
    }

    @Test void reportsSuccessFromAWorkingProbe() {
        YoutubeCookiesAdmin admin = cookies();
        seedCookies(admin);
        YoutubeCookieHealth health = new YoutubeCookieHealth(() -> Mono.empty(), admin);

        health.probe();

        assertThat(health.status().checked()).isTrue();
        assertThat(health.status().healthy()).isTrue();
    }

    @Test void reportsAnExpiredSession() {
        YoutubeCookiesAdmin admin = cookies();
        seedCookies(admin);
        YoutubeCookieHealth health = new YoutubeCookieHealth(
                () -> Mono.error(new IllegalStateException("yt-dlp failed during YouTube verification: "
                        + "YouTube 要求登录校验：服务端 cookies 没有登录态或已失效")), admin);

        health.probe();

        assertThat(health.status().healthy()).isFalse();
        assertThat(health.status().message()).contains("重新导出");
    }

    @Test void aBusyLaneDoesNotFlipTheVerdictToBroken() {
        YoutubeCookiesAdmin admin = cookies();
        seedCookies(admin);
        YoutubeCookieHealth health = new YoutubeCookieHealth(() -> Mono.empty(), admin);
        health.probe();
        assertThat(health.status().healthy()).isTrue();

        YoutubeCookieHealth busy = new YoutubeCookieHealth(
                () -> Mono.error(new IllegalStateException("yt-dlp busy during YouTube verification")), admin);
        busy.probe();
        // Even on a fresh instance the saturated lane must not claim the cookies died.
        assertThat(busy.status().healthy()).isFalse();
        assertThat(busy.status().message()).isEqualTo("尚未检测");
    }

    @Test void flagsAnExportWithoutLoginFieldsWithoutSpendingAProbe() throws Exception {
        YoutubeCookiesAdmin admin = cookies();
        // Simulates a file written before validation existed: the panel must call
        // it out on the next probe instead of waiting for a fetch to fail.
        Files.writeString(directory.resolve("yt-cookies.txt"), """
                # Netscape HTTP Cookie File
                .youtube.com\tTRUE\t/\tTRUE\t0\t__Secure-3PSID\tabcdef
                """);
        YoutubeCookieHealth health = new YoutubeCookieHealth(() -> Mono.empty(), admin);

        health.probe();

        assertThat(health.status().healthy()).isFalse();
        assertThat(health.status().message()).contains("缺少登录字段");
    }
}
