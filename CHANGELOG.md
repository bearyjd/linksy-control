# Changelog

## Unreleased

- Initial version: gesture mapping (tap, long press, drag, scroll, Back, Home, text), fail-closed
  caller authentication (pinned package and certificate, user approval), session gate with idle and
  maximum duration, accessibility service, bound Messenger service, settings and pairing screens.
- Not yet run on a device.
- Review round 1 fixes: action queue with session generations and caps, per-uid rate limit, approval re-check and end-on-revoke, end-on-caller-death, drag continuity, tapjacking filter, backup exclusion, accurate privacy wording. See docs/REVIEWS.md.
- Review round 3 fixes: release never dropped by the queue cap, END synthesizes a release, stroke state kept consistent after taps, swipes and rejected gestures, one global rate bucket for non-Linksy uids, BUSY result code 6, geometry-preserving coalescing, owner budget, bridge session ends with the engine. See docs/REVIEWS.md.
