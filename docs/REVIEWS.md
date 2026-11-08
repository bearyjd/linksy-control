# Review round 1

Two independent reviews of commit `ce02d4d`: a security review and an adversarial Codex review. Nothing
here was run on a device. "Fixed" means code plus unit tests in `core/` where the logic is pure; the
Android glue (service, bridge, dialog) compiles and lints but is untested on hardware.

| # | Finding | Disposition |
|---|---------|-------------|
| 1 | Queued input kept running after END/unbind/timeout; a replacing BEGIN deleted a pending release; no total cap or rate limit | Fixed. `ActionQueue` (core): session generation, END keeps only the release of a held finger, BEGIN never deletes a release, `MAX_QUEUE` for every action kind (round 2: no exemption except the release of a held finger). `UidRateLimiter` token bucket per uid, BEGIN costs 20, applied before package/cert lookups. Timeout path also drops queued input. |
| 2 | Revoking approval did not end a live session | Fixed. `BridgeSession` re-checks the approval store on every message; Settings revoke ends the live session and clears the bridge owner. README wording corrected. |
| 3 | Drag continuity broke after dropped segments or rotation | Fixed. `ActionQueue` prefixes a segment with the last dispatched endpoint; `ControlEngine` releases a held drag (old coordinates) on display change; cancelled strokes reset the tracker. |
| 4 | Tapjacking on the Allow dialog | Fixed in code: `filterTouchesWhenObscured` on activity and dialog decor views. Unverified on device. |
| 5 | `onUnbind` only fires when all clients leave | Fixed. `linkToDeath` on the BEGIN caller's `replyTo` binder ends the session. BEGIN without `replyTo` is now ignored (default chosen: cannot answer, so do not start). |
| 6 | PairActivity stored `certs.first()` | Fixed. `CallerAuth.matchedCert()` (tested) is stored. |
| 7 | Backup rules, `.kotlin/`, node recycle | Fixed. `dataExtractionRules` and `fullBackupContent` exclude everything; `.kotlin/` ignored; focused node and root recycled in `finally` below API 33. |
| 8 | Docs overclaimed ("never reads the screen") | Fixed in README, PRIVACY, service description and intro: focused-field read, VISIBLE_PASSWORD caveat, lock-screen injection, never run on a device. |
| 9 | Vacuous mapper tests | Fixed: both now assert non-empty, concrete results. |

Not addressed in round 1: coalescing (done in round 2). Partially-obscured-event rejection beyond
`filterTouchesWhenObscured` and overlay suppression APIs are not used.

# Review round 2

Security review (NEW-1..NEW-6) and Codex review of `4660a42`. Still not run on a device.

| # | Finding | Disposition |
|---|---------|-------------|
| 1 | Any terminal `Drag` counted as a release: bypassed the cap, survived END/timeout/revoke, could run as a standalone swipe after the session | Fixed. A release is kept only if a stroke was dispatched and is still open; END clears everything else; a terminal drag without an open stroke is an ordinary capped, generation-scoped action; releases are dropped after `strokeLost()`. Test that encoded the bug rewritten. |
| 2 | Per-uid limiter metered the owner's own input (24% pass at 1000 Hz), losing button-ups, keys, chars | Fixed. `BridgeIngress` meters only BEGIN and non-owner uids. `ActionQueue` coalesces consecutive queued moves (start of the first, end of the latest, duration of the latest); button, key, text, END and terminal drags are never merged or exempt from the cap. |
| 3 | LRU eviction gave a new uid a full bucket | Fixed. A uid new to a full table starts with 0 tokens. |
| 4 | Shared DeathRecipient could end a newer session; OK sent before linkToDeath; retained Messenger after destroy | Fixed in the service glue (untested on device): per-session recipient checked against a session token and the owner binder, OK sent only after linkToDeath, `destroyed` flag checked in BEGIN. |
| 5 | Docs: unbind wording; rotation | Fixed. README states the 60 s idle bound and the rotation limitation (queued actions run in old coordinates). |
| 6 | Weak tests | Fixed. Forward clock step added after the backwards step; the `gen`-argument test replaced by one using the real call path. |
