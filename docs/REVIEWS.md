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

# Review round 3

Security review and Codex review of `e2dc09c`. Still not run on a device. Earlier wording that END
"lifts a held finger" overclaimed: in rounds 1 and 2 a full queue could drop the release, a wheel swipe
could desynchronize the stroke state, and a rejected gesture was recorded as accepted. Those are fixed
below; the Android behavior itself (that the platform really lifts the finger) remains UNVERIFIED.

| # | Finding | Disposition |
|---|---------|-------------|
| 1 | A full queue dropped the button-up release and left the finger down; the next session's first drag got a stale endpoint prefix | Fixed. The release of an open stroke is never dropped by the cap: an ordinary entry is evicted for it. `endSession`/`beginSession` synthesize a stationary release at the last endpoint when none is queued, so an open stroke always has a release and nothing leaks into the next session. Tests in `ActionQueueTest`. |
| 2 | A wheel swipe replaced the stroke in the service but not in the queue, so END kept a "release" with an old prefix and the service started a new touch-down after END | Fixed. Dispatching a Tap, LongPress or Swipe forgets the endpoint in the queue, and a release is skipped when no stroke is open. A swipe still queued at END does not hide the real release. |
| 3 | Colluding uids evicted the real Linksy's bucket (0/101 BEGINs) | Fixed. `UidRateLimiter`: only privileged uids (pinned package uid via `PinnedUid`, and the owner) get their own never-evicted bucket; every other uid shares one global bucket. A BEGIN from another uid gets WRONG_PACKAGE without lookups; a rate-limited BEGIN gets the new result code 6 BUSY. `PinnedUid` refreshes at most every 10 s. |
| 4 | Coalescing kept only the first start and the latest end | Fixed. The merged path keeps the polyline, pruned to 64 points by dropping the points that bend it least (reversals survive), and durations add up, capped at 5 s. |
| 5 | A rejected `dispatchGesture` left the stroke recorded as valid | Fixed. `StrokeTracker` (core, tested) clears the stroke and the queue endpoint on rejection and on cancel. The service wiring is compile and lint only. |
| 6 | Owner traffic unmetered; the bridge session outlived engine expiry; main-thread cost | Partly fixed. The bridge session closes when the engine expires it. The owner has a high budget (burst 3000, 1500/s): exceeding it ends the session, with the release synthesized, instead of dropping single messages; END is never metered. Not changed: the per-INJECT approval lookup and JSON parse stay on the main thread; they are bounded by the owner budget. |
| 7 | README overstated caller-death; weak tests | Fixed. README reworded (the `replyTo` is caller-chosen, so its death is not proof of anything). Churn test asserts the Linksy uid is still admitted; full-cap button-up then END, and non-straight and unequal-duration coalescing are tested. |

Untested glue (needs a device): the `dispatchGesture` rejection path, the bridge/engine expiry hook, the
`getPackageUid` lookup.

# Review round 4

Round 4: Codex found one MEDIUM (confirmed by a probe); the opus security review rated the PR clean with five LOWs.

| # | Finding | Disposition |
|---|---------|-------------|
| 1 | MEDIUM (Codex): drag dispatched, a wheel swipe replaces it (the queue forgets the endpoint) while the mapper is still DRAGGING; the later button-up emits a terminal drag that, with no open stroke, was queued as an ordinary action and dispatched as a fresh short stroke (phantom touch). Same after a rejected stroke | Fixed in `ActionQueue`: it remembers that the gesture's stroke was replaced or lost (`strokeGone`) and drops the next terminal drag unless a move has started a new stroke. A terminal-only quick drag and taps are unchanged. The mapper is not reset: later moves still start a fresh stroke and get their own release. |
| 2 | LOW (security 1, 3): a kept release could keep moving the finger for up to 5 s after END, and releases piled up across BEGIN/END cycles past the cap | Fixed. `endSession()` and `beginSession()` drop every queued entry and queue ONE stationary lift at the last dispatched endpoint (none if nothing is held). `endSession` no longer takes the mapper's releases. |
| 3 | LOW (security 2): evicting an ordinary entry for a release that was then skipped lost typed text | Fixed. The release takes one extra slot (`maxQueue + 1`); nothing is evicted. At most one release is queued. |
| 4 | LOW (Codex 2): at 63 pending points each new point replaced the tail, losing an A-B-A excursion inside one 50 ms window | Fixed. At capacity the interior point that bends the path least is dropped. |
| 5 | LOW (security 4, 5, Codex): README overclaim on colluding uids; PinnedUid stale for 10 s after reinstall; client obligations; production constants untested; `BridgeIngress` default clock `{ 0L }` never refills | Fixed. README states what the bucket isolation proves, the possible WRONG_PACKAGE window, re-BEGIN after an ended session and the ~1500 msg/s limit. `PinnedUid` re-resolves a mismatching sender once per second at most. `OWNER_BURST`/`OWNER_PER_SEC` moved to `BridgeIngress` and tests assert 1200 Hz is admitted for a minute and 5000/s trips. `ownerLimiter` has no default. Not done: a `REPLY_ENDED` notification (not trivial; documented instead). |

Defaults chosen: lift duration 1 ms; recheck interval 1 s; re-resolve applies to every mismatching sender (bounded by the interval, not only BEGIN).

## Round 4 addendum

Codex re-reviewed the round 4 delta (dea26cb..5be0c57) and confirmed two more MEDIUMs in the `strokeGone` flag, plus one LOW.

| # | Finding | Disposition |
|---|---------|-------------|
| 1 | MEDIUM: `strokeGone` was cleared for every continuing drag before admission, so a move dropped at capacity still cleared it and the later button-up became a phantom touch | Fixed: the flag is cleared only when a move is queued or merged. Test: stroke lost, queue full, move dropped, button-up dispatches nothing. |
| 2 | MEDIUM: a skipped release (`dispatchedEnd == null`) left `strokeGone` set, so the next independent quick drag (terminal only) was silently dropped | Fixed: skipping a release clears the flag, since the gesture it belonged to is over. Test: drag, swipe, button-up, then a new quick drag dispatches. |
| 3 | LOW: three ActionQueue tests could not fail (unused local, a single release, no moving release) | Strengthened: a moving 5000 ms release is queued, two releases are supplied, the unused local is gone. |
| 4 | Privacy: commit 5be0c57 accidentally tracked local tool state (`.omc/STATE.md`, `.omc/state/**`; no secrets) | `.omc/` and `.claude/` are untracked and git-ignored. The files stay in PR history; the squash merge keeps main clean (a force-push would not purge them from GitHub either). |
