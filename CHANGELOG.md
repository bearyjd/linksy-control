# Changelog

## Unreleased

- Initial version: gesture mapping (tap, long press, drag, scroll, Back, Home, text), fail-closed
  caller authentication (pinned package and certificate, user approval), session gate with idle and
  maximum duration, accessibility service, bound Messenger service, settings and pairing screens.
- Not yet run on a device.
- Review round 1 fixes: action queue with session generations and caps, per-uid rate limit, approval re-check and end-on-revoke, end-on-caller-death, drag continuity, tapjacking filter, backup exclusion, accurate privacy wording. See docs/REVIEWS.md.
- Review round 3 fixes: release never dropped by the queue cap, END synthesizes a release, stroke state kept consistent after taps, swipes and rejected gestures, one global rate bucket for non-Linksy uids, BUSY result code 6, geometry-preserving coalescing, owner budget, bridge session ends with the engine. See docs/REVIEWS.md.
- Review round 4 fixes: a button-up after the stroke was replaced or lost is no longer a phantom touch, END/BEGIN collapse releases into one stationary lift, a release takes an extra queue slot instead of evicting typed text, pending drag points keep excursions at capacity, `PinnedUid` re-resolves a mismatching sender after 1 s, owner budget constants are tested, README limits clarified. See docs/REVIEWS.md.
- Review round 4 addendum: the stroke-suppression flag is no longer cleared by a dropped move, and a skipped release clears it so the next quick drag is not swallowed; weak queue tests strengthened; local tool state untracked and ignored.
- Review round 4 addendum 2: the global stroke-loss flag is replaced by per-gesture identity (`gestureId` on drags, a bounded `lostGestures` ring in `ActionQueue`), so a stale flag can no longer drop an independent quick drag or miss the old release. See docs/REVIEWS.md.
