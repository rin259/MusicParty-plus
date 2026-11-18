#!/bin/sh
# Keep yt-dlp current so SoundCloud / YouTube Music extraction does not break
# when upstream sites change. Best-effort: any update failure is ignored so the
# app always starts and keeps running with whatever binary is present.

YTDLP_BIN="${YT_DLP_PATH:-yt-dlp}"
YTDLP_AUTOUPDATE_INTERVAL_SECONDS="${YTDLP_AUTOUPDATE_INTERVAL_SECONDS:-21600}"
# `--update` follows the stable channel, which only moves every few weeks. YouTube
# extraction fixes usually land on nightly first, so make the channel selectable
# and say what happened instead of failing silently.
YTDLP_UPDATE_CHANNEL="${YTDLP_UPDATE_CHANNEL:-stable}"

update_ytdlp() {
    if [ "$YTDLP_UPDATE_CHANNEL" = "stable" ]; then
        output="$("$YTDLP_BIN" --update 2>&1)" || true
    else
        output="$("$YTDLP_BIN" --update-to "$YTDLP_UPDATE_CHANNEL" 2>&1)" || true
    fi
    case "$output" in
        *"up to date"*) : ;;
        *"Updated yt-dlp"*|*"Downloading"*) echo "[yt-dlp] $output" ;;
        "") : ;;
        *) echo "[yt-dlp] update on '$YTDLP_UPDATE_CHANNEL' failed: $output" ;;
    esac
}

# Update once at startup.
update_ytdlp

# Then keep it fresh in the background for long-running containers.
(
    while true; do
        sleep "$YTDLP_AUTOUPDATE_INTERVAL_SECONDS"
        update_ytdlp
    done
) &

# CVE-2026-114938: disable the affected Base64 intrinsic. HotSpot processes
# _JAVA_OPTIONS after the command line, so keep unrelated flags but enforce
# the mitigation last there as well if that variable is set.
if [ -n "${_JAVA_OPTIONS:-}" ]; then
    export _JAVA_OPTIONS="$_JAVA_OPTIONS -XX:-UseBASE64Intrinsics"
fi
exec java -XX:-UseBASE64Intrinsics -jar /app/app.jar
