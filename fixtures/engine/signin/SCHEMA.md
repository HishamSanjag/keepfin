# signin

Which sign-ins exist for the site of a link. Decided by `app.keepfin.core.signin.SignIn` (`core/`): desktop through `gui/shared/core/signin.ts` (`gui/desktop/signin/browsers.ts` finds the browser cookies), Android through `CookieExport.signedInSites`. The core never sees a cookie value.

The site table (key, name, link hosts, cookie domains, session cookie names, login page) lives in the core only: `instagram`, `facebook`, `x`, `tiktok`, `reddit`, `youtube`, `vimeo`, `soundcloud`, `pinterest`.

| file | input | expected |
|---|---|---|
| `site.json` | `url` | `site`: the key, or null. A host matches the site's host or a sub-domain of it (`m.`, `music.`, `old.`); a look-alike (`notinstagram.com`, `instagram.com.evil.test`) matches nothing |
| `signed-in.json` | `site`, `now` (Unix seconds), `cookies` (`name`, `domain`, `expiry`; no values) | `signedIn`: true when a cookie named like one of the site's session cookies (exact case) is on one of its domains (or a sub-domain) and is live: `expiry` 0 is a session cookie, otherwise it must be after `now`. Tracking cookies never count |
| `sources.json` | `sources` (`kind` `keepfin` or `browser`, `browser`, `profile`, `label`) | `order`: the sources' row texts in order: Keepfin's own first, then browsers Firefox, Chrome, Edge, Brave, Opera, Vivaldi, Chromium; equals keep their order. A browser profile is appended as ` [profile]` here only so the case can tell profiles apart |
| `sources.json` | `source` | `notice`: `Signed in with <label>` for a browser, `Signed in` for Keepfin's own |
| `retry.json` | `url`, `action` (the failure's action wire string or null), `autoOn`, `manualOverride`, `available` and `tried` (sources as in `sources.json`) | `retry`: the row text of the source to retry with (profile appended as ` [profile]`), or null. Only after a `signIn` failure, only while `autoOn` and not `manualOverride`, only on a known site; the first of `available` in `order` that is not in `tried` (same kind, browser and profile) |
