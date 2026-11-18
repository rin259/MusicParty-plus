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

    public record Status(boolean configured, long bytes, long updatedAt,
                         boolean sessionReady, List<String> missing) {}
    public record TestResult(boolean ok, String message) {}

    /**
     * Cookies a signed-in YouTube session cannot work without. Google marks all
     * of them HttpOnly, so an export taken from {@code document.cookie} (or a
     * browser extension that only reads script-visible cookies) silently drops
     * them and leaves a file that looks plausible but is never authenticated.
     */
    private static final List<String> HTTP_ONLY_AUTH_COOKIES =
            List.of("SID", "SAPISID", "LOGIN_INFO");

    private final Path file;

    public YoutubeCookiesAdmin(AppProperties appProperties) {
        this.file = YoutubeCookieFile.resolve(appProperties.getYtDlp().getCookiesFile());
    }

    /** Canonical jar location; the health probe keeps a last-known-good copy beside it. */
    Path file() {
        return file;
    }

    public Status status() {
        if (!YoutubeCookieFile.usable(file)) return new Status(false, 0, 0, false, HTTP_ONLY_AUTH_COOKIES);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<String> missing = missingAuthCookies(text);
            return new Status(true, Files.size(file), Files.getLastModifiedTime(file).toMillis(),
                    missing.isEmpty(), missing);
        } catch (IOException error) {
            return new Status(false, 0, 0, false, HTTP_ONLY_AUTH_COOKIES);
        }
    }

    /** Cookie names present in the file, minus everything but the auth fields we care about. */
    static List<String> missingAuthCookies(String content) {
        List<String> present = new ArrayList<>();
        for (String line : (content == null ? "" : content).replace("\r\n", "\n").split("\n")) {
            String trimmed = line.strip();
            String bare = trimmed.startsWith("#HttpOnly_") ? trimmed.substring("#HttpOnly_".length()) : trimmed;
            if (bare.isEmpty() || bare.startsWith("#")) continue;
            String[] parts = bare.split("\t");
            if (parts.length >= 7) present.add(parts[5]);
        }
        List<String> missing = new ArrayList<>();
        boolean hasSid = present.contains("SID") || present.contains("__Secure-1PSID");
        boolean hasSapisid = present.contains("SAPISID") || present.contains("__Secure-1PAPISID");
        boolean hasLoginInfo = present.contains("LOGIN_INFO");
        if (!hasSid) missing.add("SID");
        if (!hasSapisid) missing.add("SAPISID");
        if (!hasLoginInfo) missing.add("LOGIN_INFO");
        return missing;
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
        List<String> missing = missingAuthCookies(text);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("cookies 缺少登录必需的 HttpOnly 字段：" + String.join("、", missing)
                    + "。用 document.cookie 或只读脚本 cookie 的扩展导出会漏掉它们；"
                    + "请改用 yt-dlp --cookies-from-browser，或支持 HttpOnly 的导出扩展。");
        }
        return text + "\n";
    }
}