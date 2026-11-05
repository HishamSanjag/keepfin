# spaces

Spaces v2 logic. Desktop: `gui/shared/library.ts` (`matchesSmartRules`, `normalizeTags`, `collectSourceTags`, `tagsInScope`, `newEntries`, `entryKey`, `gateNew`, `removedFromSource`, `sortBySourceOrder`, `collectionFromPlaylist`, `canonicalSourceUrl`, `sourceIdOfCollection`, `collectionKindOf`, `isInfiniteSource`, `defaultFollow`, `homeSpaces`, `reorderSpaces`, `defaultSpaceFor`) and `isDue` in `gui/desktop/library/follow.ts`. Android: `SmartLogic`, `TagLogic`, `CollectionLogic` (`Collections.kt`) and `SpaceLogic` (`LibraryLogic.kt`). The words of smart rules and of the tag filter's smart space are `RuleWords` (`fixtures/engine/spaces/rule-words.json`), in the phone's wording; the desktop reads them through `describeSmartRules`, `tagFilterRules` and `tagFilterName` in `gui/shared/core/spaces.ts`, Android through `RuleText` and `TagSmart`. The words around a downloaded collection are `CollectionWords` (`collection-words.json`, the phone wording; the desktop reads them through `collectionKindWords`, `everyWords`, `followLineWords`, `agoWords` and `collectionRowWords` in `gui/shared/core/spaces.ts`, Android through `CollectionText`). The words of "Where your files are" are `WhereWords` (`where-words.json`, Windows' wording with the device name as a parameter; the desktop reads them through `gui/web/settings/whereText.ts`, Android through `WhereWords` in `where/WhereFiles.kt`). Since phase 9 all of it is decided by the shared core, `app.keepfin.core.spaces` (`SmartRules`, `Tags`, `Collections`, `Spaces`); both apps keep thin wrappers that map their own types onto the core model.

| `input.op` | input | expected |
|---|---|---|
| `smart` | `now`, `items`, `rules`, optional `collectionsOf` (item id -> collection ids) | `{ids}`: the items a smart space gathers, in order. Item: `{id, space? (music), kind? (audio), tags, userTags, artist, albumArtist, album, favorite, plays, addedDaysAgo (0), sourceUrl, sourceId}`. Rules: `{match: any|all, rules, onlyKind?, excludeSpaces?}` with rule `{field: tag|collection|space|source|artist|album|kind, values}`, `{field: addedWithin, days}`, `{field: favorite}`, `{field: unplayed}` |
| `normalizeTags` | `raw` (list of strings, may hold `null`) | the normalised source tags |
| `sourceTags` | `info` (`genres`, `genre`, `tags`, `categories`) | the tags captured from an inspection |
| `scopeTags` | `items` (`{tags, userTags}`), optional `minItems` (2) | list of `{tag, count, user}` |
| `newKeys` | `entries` (urls), `seen` (keys) | `{keys}`: keys of the entries never seen, once each, in source order |
| `itemKey` | `url` | the entry key (`youtube:<id>`... or the link) |
| `gate` | `count`, `confirmAbove`, optional `approved` | `none`, `enqueue` or `confirm` |
| `removed` | `members` (`{id, sourceId?}`), `discovered` (source ids) | `{ids}` of members that left the source |
| `order` | `ids`, `index` (id -> source position) | `{ids}` in source order, unknown last in their own order |
| `migratePlaylist` | `playlist {id, name, itemIds, createdAt, updatedAt}`, `space` | the collection: `{id, name, kind: "own", space, itemIds, seen, createdAt, updatedAt}` |
| `sameSource` | `a`, `b` (links) | `{same}`: one collection per source |
| `canonical` | `url` | `{url, id}`: the stored source link and the playlist/channel/album id |
| `collectionKind` | `url` | `{kind (playlist|album|channel), infinite}` |
| `defaultFollow` | `kind`, optional `url`, optional `keepInSyncHours` (1, 6, 12, 24 or 168) | Following is opt-in: `mode` is `off` unless `keepInSyncHours` is given for a playlist or channel that is not a radio mix; then `auto` at that interval. `{mode, intervalHours, wifiOnly, notify, confirmAbove}`; where no follow exists at all (own lists, radio mixes on Android) the result is just `{mode: "off"}` |
| `due` | `now`, `collections` (`{id, kind?, sourceUrl?, follow? {mode, intervalHours?, wifiOnly?, lastCheckedAt?, pending?}}`), optional `metered` (Android) | `{ids}` of the collections to check now |
| `home` | `spaces` (`{id, pinned?, type?}`) | `{shown (ids), more}` |
| `move` | `ids`, `id`, `forward` | `{order}`: the ids with one moved a place later or earlier, unchanged at the ends or for an unknown id |
| `reorder` | `spaces`, `ids` | `{order}` or `{error: true}` when an id is unknown or repeated |
| `ruleWords` | `rules` (a rule set as in `smart`), optional `names` ({collection: {id: name}, space: {id: name}}) | the smart space's rules in plain words, as a string: "Tag is lofi or chill and Favorite · only songs", "No rules yet" for none. Collection and space ids read as their names; kinds read Songs, Videos, Photos, Other files; added within 60 days or more in whole months reads "N months". The phone's wording, shown by both apps |
| `tagRules` | `tags`, `match` (any\|all), optional `space`, optional `onlyKind` (Android only) | the smart space "Save as smart space" makes from a tag filter: `{match, rules, onlyKind?}`. Tags once each in A to Z order; any = one tag rule, all = a rule per tag; with a `space` a space rule is added and `match` is `all` |
| `tagName` | `tags` | the suggested name: the first three tags in A to Z order, first letter capital, joined with " + ", at most 40 characters |
| `countLabel` | `total`, `audio`, `video`, `image`, `other` (counts, 0 when absent) | "58 songs", "1 video", "12 items": the noun follows the one kind present (Android only: the desktop does not show it) |
| `origin` | `bySpace` (space id -> count, in order), `names` (id -> name) | "From Music (51) and Downloads (7)": biggest first, at most four, empty spaces left out (Android only) |
| `collectionWord` | `kind` (playlist\|album\|channel\|own) | "Playlist", "Album", "Channel", "Yours" (`collection-words.json`) |
| `everyWords` | `hours` | "every hour", "every day", "every week", "every 6 hours" |
| `intervalLabel` | `hours` | an interval as a choice: "1 hour", "12 hours", "24 hours", "7 days" |
| `followLine` | `kind`, optional `sourceUrl`, `mode` (auto\|manual\|off), `intervalHours`, `press` (default "tap"; the desktop says "click") | "Following · every 6 hours", "Checks when you tap Check now", "Not following"; null when the collection cannot be followed (your own, no source, a radio mix) |
| `ago` | `ageMs` (how long before `now`) | "just now", "12 min ago", "3 h ago", "yesterday", "4 days ago"; from a week on the screen's date, `date <ms>` here. Floors, never rounds |
| `checkedLine` | optional `ageMs`, `lastError`, `newCount`, `noun` (Android only: the desktop writes its card by hand) | "Checked 12 min ago · 3 new songs added", the error, or "Not checked yet" |
| `rowSubtitle` | `kind`, optional `sourceUrl`, `noun`, `present`, `mode`, `ageMs` (the last check), `extras` | "Playlist · 31 songs · following, checked 12 min ago": kind, count, follow status (nothing for an album that is not followed), then the screen's extras |
| `switchStart` | `position` (seconds, may be null or negative), `otherLength` (seconds, null or 0 when unknown) | where the other side of Song | Video starts (`music-videos.json`): the same second, landing 1 s before the other side's end when it is shorter or the place is in its last second, 0 for an unusable place |
| `musicVideoRows` | `items` (`{id, kind, title, artist?, hasVideo, videoDuration?, videoSize?, addedAt}`) | `{rows}`: a row per audio item that has a video, newest first then by title: `{id (<itemId>#video), itemId, title, artist, duration, size, addedAt}`; a length or size of 0 reads null |
| `musicVideoRowId` | `itemId` | the id of its video row |
| `musicVideoItemId` | `rowId` | the song behind a video row id, null when it is not one |
| `musicVideoWords` | none | the words of the rows, Capture and the refusal, in order: label, shelf, see all, go to song, delete, delete question, join note, has-video note, keep-this-video check box, has-video error |
| `previewWords` | none | the download preview's Both words, in order: looking for the music video, none found (Both is off), found, the link itself (`preview-words.json`; `PreviewWords`) |
| `extrasCount` | optional booleans `subtitles`, `cut`, `sponsor`, `chapters`, `inFile`, `beside`, `queue` (each: that Extras group differs from the person's defaults) | how many groups were changed, a number |
| `extrasLabel` | `changed` | the Extras tab's label: "Extras", "Extras · 2" |
| `whereAgo` | `ageMs` | "Where your files are" time in Windows' wording, rounded: "just now", "2 min ago", "3 h ago", "yesterday", "3 days ago", "2 weeks ago", "4 months ago" (`where-words.json`) |
| `whereHolds` | optional `all`, `spaces`, `favourites`, `playlists` (names), `held`, `of` | "Everything", "Music, Favourites", "Road trip and 2 more", "40 of 1,200 files", "Nothing yet" |
| `whereState` | `state` (upToDate\|copying\|waiting\|offline\|problem\|needsDecision), optional `ageMs`, `done`, `total`, `reason`, `unplugged`, `count`, `toCopy` | "up to date 2 min ago", "copying · 40 of 210", "waiting for Wi-Fi", "last copied 3 days ago · not plugged in", "offline · last seen 5 min ago", "50 files not copied", "needs a decision" |
| `whereReason` | `text` (Android only: the desktop keeps only short reasons, through `whereState`) | a waiting reason as a state, whole: "waiting for Wi-Fi" |
| `whereSingle` | `kind` (songs\|videos\|other), `count`, `place` | "12 videos are only on Desk PC", "1 other file is only on this phone" |
| `whereOnePlace` | `thisName` | "Everything is on this PC only. Keep a copy on another device, a drive or the cloud." |
| `whereItemLine` | `places` (`{name, offline?}`) | "On: this PC, Pixel 9 (offline)", "Only on this PC", "Not on any device yet" |
| `route` | `url`, `routes {music, other}`, `spaces` | the id of the space a download goes to by default |

`now` is `1800000000000` (ms) in every case; one day is 86400000 ms.
