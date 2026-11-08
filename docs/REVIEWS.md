# Review round 1

Two independent reviews of commit `ce02d4d`: a security review and an adversarial Codex review. Nothing
here was run on a device. "Fixed" means code plus unit tests in `core/` where the logic is pure; the
Android glue (service, bridge, dialog) compiles and lints but is untested on hardware.

| # | Finding | Disposition |
|---|---------|-------------|
| 1 | Queued input kept running after END/unbind/timeout; a replacing BEGIN deleted a pending release; no total cap or rate limit | Fixed. `ActionQueue` (core): session generation, END keeps only releases, BEGIN never deletes a release, `MAX_QUEUE` for every action kind (releases exempt). `UidRateLimiter` token bucket per uid, BEGIN costs 20, applied before package/cert lookups. Timeout path also drops queued input. |
| 2 | Revoking approval did not end a live session | Fixed. `BridgeSession` re-checks the approval store on every message; Settings revoke ends the live session and clears the bridge owner. README wording corrected. |
| 3 | Drag continuity broke after dropped segments or rotation | Fixed. `ActionQueue` prefixes a segment with the last dispatched endpoint; `ControlEngine` releases a held drag (old coordinates) on display change; cancelled strokes reset the tracker. |
| 4 | Tapjacking on the Allow dialog | Fixed in code: `filterTouchesWhenObscured` on activity and dialog decor views. Unverified on device. |
| 5 | `onUnbind` only fires when all clients leave | Fixed. `linkToDeath` on the BEGIN caller's `replyTo` binder ends the session. BEGIN without `replyTo` is now ignored (default chosen: cannot answer, so do not start). |
| 6 | PairActivity stored `certs.first()` | Fixed. `CallerAuth.matchedCert()` (tested) is stored. |
| 7 | Backup rules, `.kotlin/`, node recycle | Fixed. `dataExtractionRules` and `fullBackupContent` exclude everything; `.kotlin/` ignored; focused node and root recycled in `finally` below API 33. |
| 8 | Docs overclaimed ("never reads the screen") | Fixed in README, PRIVACY, service description and intro: focused-field read, VISIBLE_PASSWORD caveat, lock-screen injection, never run on a device. |
| 9 | Vacuous mapper tests | Fixed: both now assert non-empty, concrete results. |

Not addressed: reviewers' suggestion to coalesce movement inside the queue (the mapper already rate
limits segments to 20/s and bounds path points). Partially-obscured-event rejection beyond
`filterTouchesWhenObscured` and overlay suppression APIs are not used.
