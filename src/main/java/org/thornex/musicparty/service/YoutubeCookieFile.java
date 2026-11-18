package org.thornex.musicparty.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Single source of truth for the YouTube cookie file location and usability. */
public final class YoutubeCookieFile {
    static final String DEFAULT_RELATIVE_PATH = "data/yt-cookies.txt";

    private YoutubeCookieFile() {}

    public static Path resolve(String configured) {
        return configured == null || configured.isBlank()
                ? Path.of(DEFAULT_RELATIVE_PATH) : Path.of(configured);
    }

    public static boolean usable(Path file) {
        try {
            return file != null && Files.isReadable(file) && Files.size(file) > 0;
        } catch (IOException error) {
            return false;
        }
    }
}