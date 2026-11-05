# Security

Keepfin runs entirely on your devices: there is no Keepfin server, account or analytics. Downloads, your library and paired devices stay on your network or in the folders and cloud accounts you choose.

## Reporting a vulnerability

Please report security problems privately, not in a public issue: use **Security → Report a vulnerability** on this repository (GitHub private vulnerability reporting). Include the app (Windows or Android), its version (Settings → About Keepfin), and the steps to reproduce.

You will get an answer within a week. Fixes ship in the next release, and the release notes credit the reporter unless you ask not to be named.

## Supported versions

Only the [latest release](https://github.com/HishamSanjag/keepfin/releases/latest) receives fixes. Both apps update themselves from that feed.

## What is in scope

- The Windows app (Electron main process, the `keepfin-media://` protocol, IPC between the window and the main process)
- The Android app
- Device pairing and the local network service (pairing codes, pins, mutual TLS)
- Cloud copies (Google Drive, Dropbox, WebDAV) and how their sign-in tokens are stored
- The update feed and how updates are verified
