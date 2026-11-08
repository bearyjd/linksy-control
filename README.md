# Linksy Control

A small, native Android companion for [Linksy](https://github.com/LinkSy-mg). Its only job: when you
turn it on, let the Linksy app tap, swipe, press Back/Home and type on your phone while you control
it from your computer. Application id `app.linksy.mg.control`. No Flutter, no network access.

Status: early. **This app has never been run on a device**; it was built and reviewed without a phone. See "Verification" below.

## What it can and cannot do

Can: tap, long-press, drag, scroll (swipes), Back (right click / Esc), Home (middle click / Super),
type into the focused text field, press the keyboard's action key.

Cannot: multi-touch, record or capture your screen, open network connections itself (no INTERNET
permission), run without you enabling it in Android Settings, or act when no Linksy control
session is running.

Does read: the text of the one focused input field, when you type from the computer (to insert at the
cursor). It skips fields Android marks as passwords; a field that shows its password in clear
(`VISIBLE_PASSWORD` inputs) may not be marked and can be read. It subscribes to no accessibility
events. Injection also works while the phone is locked or in any app, like a person tapping it, and
whatever other apps then do with that input (including sending data) is out of this app's hands.

## Ground rule

It uses only routes Android supports and you turn on yourself. It does not ask you to disable Play
Protect, and does not hide that it is an Accessibility service. If your phone refuses to install or
enable it, the fallback ladder applies: (1) full touch control with this app, (2) typing via the
Linksy keyboard, (3) full control over the USB cable, (4) view only. See the "Ground rule" section
of the Linksy plan (`phone-control-over-wifi.md` §0).

## Turn it on

1. Install the app, open it, tap "Open Accessibility settings", enable Linksy Control.
2. Android 13+ and installed from a file: if the switch is greyed out, "Open app info", three-dot
   menu, "Allow restricted settings", then repeat step 1.
3. In Linksy, start control; approve the pairing request once. Revoke it any time in this app.

## How it pairs with Linksy

Pairing is fail-closed. A caller is accepted only if all hold: its package is `app.linksy.mg`, its
signing certificate SHA-256 equals the pin compiled into this build, and you approved it.
**A build without a pin rejects every caller** and the Settings screen says pairing is disabled.
Build with the official hash:

    gradle :app:assembleRelease -PlinksyCertSha256=<64 hex chars from the Linksy release certificate>

Contract (for the Linksy client): bind explicit component `app.linksy.mg.control/.ControlBridgeService`
(action `app.linksy.mg.control.BIND`), get a `Messenger`. Pair once with `startActivityForResult`
(action `app.linksy.mg.control.PAIR`, component `.PairActivity`; `RESULT_OK` = approved). Send
`Message.what` 1 = BEGIN (reply `what=100`, `arg1` = 0 ok, 1 pairing disabled, 2 wrong package,
3 wrong cert, 4 not approved, 5 accessibility off), 2 = END, 3 = INJECT with `Bundle` string `json`
holding one Linksy `ControlMsg` (`pointer`, `button`, `scroll`, `key`, `text`; fractions 0..1). The
sender uid of every message is checked against the open session, and the user's approval is re-read
from the approval store on every message (INJECT included), so revoking it in Settings ends a live
session at once. Input outside BEGIN..END is ignored. A session ends on END, after 60 s idle, after
30 min total, on revoke, when the BEGIN caller's process dies, or when the bridge is unbound.
BEGIN needs `Message.replyTo` (it is answered there). Messages are rate limited per uid (token
bucket) before any package or certificate lookup, and at most 32 ordinary actions wait to run; END
drops them and keeps only the release of a held finger. The pairing dialog ignores touches while
another window covers it (`filterTouchesWhenObscured`). Source: `app/.../BridgeProtocol.kt`.

## Layout

- `core/`: pure Kotlin/JVM, all logic and tests (`GestureMapper`, `CallerAuth`, `SessionGate`,
  `TextEdit`, `ControlEngine`, `ActionQueue`, `BridgeSession`, `UidRateLimiter`).
- `app/`: thin Android glue (Accessibility service, bridge service, settings and pairing screens).

Build and test with Gradle 8.14 and JDK 17+: `gradle :core:test :app:assembleDebug`. Needs an Android
SDK (platform 35) via `local.properties` (`sdk.dir=...`). No Gradle wrapper is checked in.

## Verification

`core/` is unit tested (93 tests) and `app/` compiles and passes lint. Nothing has been run on a
device, ever: the Accessibility behavior (gestures, set-text, pairing screen, binding) is UNVERIFIED.

See `PRIVACY.md`, `CHANGELOG.md` and `docs/REVIEWS.md`. No LICENSE file yet: the Linksy desktop repo is AGPL-3.0 but the
mobile repo states none, so the choice is left to the maintainer.
