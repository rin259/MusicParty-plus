#!/bin/sh
# Keep yt-dlp current so SoundCloud / YouTube Music extraction does not break
# when upstream sites change. Best-effort: any update failure is ignored so the
# app always starts and keeps running with whatever binary is present.

YTDLP_BIN="${YT_DLP_PATH:-yt-dlp}"
YTDLP_AUTOUPDATE_INTERVAL_SECONDS="${YTDLP_AUTOUPDATE_INTERVAL_SECONDS:-21600}"

update_ytdlp() {
    "$YTDLP_BIN" --update >/dev/null 2>&1 || true
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
