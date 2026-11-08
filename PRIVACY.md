# Privacy

- No internet permission, no analytics, no accounts. The app cannot send data off the phone.
- It subscribes to no accessibility events and does not read your screen. It asks for
  "retrieve window content" only to find the one focused text field when you type from the
  computer; it skips password fields.
- It acts only during a Linksy control session you started, and ignores everything otherwise.
- Typed text and touch data are never logged or stored. The only thing stored is the list of
  approved apps (package name and certificate hash), which you can revoke in the app.
- Only the official Linksy app (package and signing certificate pinned at build time) can talk to
  it, after you approve it once. An unpinned build refuses everyone.
