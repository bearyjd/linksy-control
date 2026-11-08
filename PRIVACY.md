# Privacy

- No internet permission, no analytics, no accounts: the app itself opens no network connections.
  It can however type and tap in other apps for Linksy, and those apps may send what you enter.
- It subscribes to no accessibility events and does not record or capture your screen. It asks for
  "retrieve window content" only to find the one focused text field when you type from the
  computer, and it reads that field's current text to insert at the cursor. It skips fields Android
  marks as passwords; a field that shows its password in clear (`VISIBLE_PASSWORD`) may not be marked.
- It acts only during a Linksy control session you started, and ignores everything otherwise. While
  a session runs it also works on the lock screen, like a person touching the phone.
- Revoking approval in the app ends a live session immediately.
- Typed text and touch data are never logged or stored. The only thing stored is the list of
  approved apps (package name and certificate hash), which you can revoke in the app. It is excluded
  from backups and device-to-device transfer.
- Only the official Linksy app (package and signing certificate pinned at build time) can talk to
  it, after you approve it once. An unpinned build refuses everyone.
- This app has never been run on a device.
