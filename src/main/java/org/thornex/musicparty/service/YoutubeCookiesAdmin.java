package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores the Netscape-format YouTube cookie export that yt-dlp needs for
 * anonymous streaming rejected by the "confirm you're not a bot" check.
 *
 * <p>The content is a login credential: it is never returned by the API, only
 * a status summary. Writes are atomic and owner-only.</p>
 */
@Service
@Slf4j
public class YoutubeCookiesAdmin {
    /** Whole-browser exports are often several megabytes; we keep only YouTube/Google. */
    private static final int MAX_INPUT_BYTES = 4_000_000;
    private static final int MAX_STORED_BYTES = 512_000;

    public record Status(boolean configured, long bytes, long updatedAt) {}
    public record TestResult(boolean ok, String message) {}

    private final Path file;

    public YoutubeCookiesAdmin(AppProperties appProperties) {
        this.file = YoutubeCookieFile.resolve(appProperties.getYtDlp().getCookiesFile());
    }

    public Status status() {
        if (!YoutubeCookieFile.usable(file)) return new Status(false, 0, 0);
        try {
            return new Status(true, Files.size(file), Files.getLastModifiedTime(file).toMillis());
        } catch (IOException error) {
            return new Status(false, 0, 0);
        }
    }

    public Status save(String content) {
        String normalized = validate(content);
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "yt-cookies.", ".tmp");
            try {
                Files.writeString(temporary, normalized, StandardCharsets.UTF_8);
                restrict(temporary);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            log.info("YouTube cookies updated ({} bytes)", normalized.length());
            return status();
        } catch (IOException error) {
            log.warn("Could not write YouTube cookies file");
            throw new IllegalStateException("无法保存 YouTube cookies，请检查服务端数据目录权限");
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
            log.info("YouTube cookies removed");
        } catch (IOException error) {
            throw new IllegalStateException("无法删除 YouTube cookies，请检查服务端数据目录权限");
        }
    }

    private static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems keep their defaults.
        }
    }

    static String filterYouTubeCookies(String content) {
        List<String> kept = new ArrayList<>();
        for (String line : content.replace("\r\n", "\n").split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            String bare = trimmed.startsWith("#HttpOnly_") ? trimmed.substring("#HttpOnly_".length()) : trimmed;
            if (bare.startsWith("#")) {
                // Keep the Netscape header; drop every other comment.
                if (bare.toLowerCase().contains("netscape http cookie file")) kept.add(trimmed);
                continue;
            }
            String domain = bare.split("\t", 2)[0].toLowerCase(java.util.Locale.ROOT);
            if (domain.endsWith("youtube.com") || domain.endsWith("google.com") || domain.endsWith("googlevideo.com")) {
                kept.add(trimmed);
            }
        }
        return String.join("\n", kept) + (kept.isEmpty() ? "" : "\n");
    }

    private static String validate(String content) {
        String raw = content == null ? "" : content.replace("\r\n", "\n").strip();
        if (raw.isEmpty()) throw new IllegalArgumentException("请粘贴 cookies.txt 的内容");
        if (raw.length() > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("cookies 内容超过 4MB，请只导出 youtube.com 或 google.com 的 cookies");
        }
        if (!raw.contains("\t")) throw new IllegalArgumentException("这不是 Netscape 格式的 cookies.txt");
        String text = filterYouTubeCookies(raw).strip();
        if (text.isEmpty() || !text.contains("\t")) {
            throw new IllegalArgumentException("未找到 youtube.com 或 google.com 的 cookie，请确认导出的是 YouTube 登录态");
        }
        if (text.length() > MAX_STORED_BYTES) {
            throw new IllegalArgumentException("过滤后仍然过大，请只导出 youtube.com 的 cookies");
        }
        boolean header = text.toLowerCase().contains("netscape http cookie file");
        boolean hasSid = text.contains("\tSID\t") || text.contains("\tSAPISID\t")
                || text.contains("\t__Secure-3PAPISID\t") || text.contains("\t__Secure-1PAPISID\t");
        if (!header && !hasSid) throw new IllegalArgumentException("cookies 内容缺少 YouTube 登录字段（SID / SAPISID）");
        return text + "\n";
    }
}