# Auto Pause Investigation

- Symptom: playback paused without a user-facing notification during brief WebSocket reconnects.
- Root cause: `UserService` cleared the active session on disconnect, but `getOnlineUserSummaries()` excluded pending-leave users. This published a zero-user event immediately and caused `MusicPlayerService` to enter idle mode.
- Fix: pending-leave users remain counted for the 45-second reconnect grace period. The delayed confirmation now publishes the zero-user event only when the grace period expires.
- Verification: `UserServiceTest` covers rapid reconnect and confirmed departure. Production logs showed a 0.19-second reconnect without the prior idle-mode pause.
