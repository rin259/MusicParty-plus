# Playback Stutter Investigation

- Symptom: playback has slight intermittent stutter despite no obvious local network weakness.
- Evidence: container CPU was about 0.03%, memory about 376 MiB, no OOM/restart; cached media supports HTTP Range and served 1 MiB ranges through HTTPS in about 16-21 ms.
- Most likely cause: recent playback resolution uses NetEase direct CDN URLs, which bypass the local cache. The frontend then compares `audio.currentTime` with server-derived progress every 200 ms and seeks when the gap exceeds 2 seconds, amplifying a short source stall into audible correction.
- Lower-probability cause: `/media/**` falls through the default Nginx proxy with buffering enabled, but local throughput measurements did not show it as the current bottleneck.
- Separate security issue: the Netease API container logs request query parameters containing cookies and should mask them.

## Fix Applied

- NetEase playback now follows the same local-cache contract as the other resolvers: uncached tracks return `PENDING_DOWNLOAD`, download through `LocalCacheService`, and later play from `/media/netease_<id>.<extension>`.
- Queue status handling now treats NetEase as a cached platform. After runtime configuration restores persisted credentials, pending restored queue items are prefetched so a restart cannot leave them permanently pending.
- The browser now marks both `waiting` and `stalled` as buffering. Server-progress correction waits for recovery, future media data, three seconds of buffered headroom, and uses a five-second foreground threshold with a five-second correction cooldown.
- Verification: frontend production build passed; Maven tests passed (5 tests); deployed container started without restart/OOM; an actual restored NetEase track was downloaded into the persistent cache; local and public HTTPS requests returned 200.
