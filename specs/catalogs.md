# Catalogues

## 1. Purpose

A catalogue is a directory of **source lists**, one layer above the lists that
enumerate feeds. It exists so that the archive fills itself: register a
directory once and the registry keeps up with it, instead of somebody pasting
OPML URLs in whenever the collection grows.

```
catalogue  ──refresh──▶  source lists  ──refresh──▶  sources  ──poll──▶  articles
 (daily)                   (daily)                   (adaptive)
```

Three layers, deliberately the same shape: identity by URL, a refresh schedule
that doubles as the claim lease, a policy for entries that vanish, a report of
the last pass. Somebody who has understood one layer has understood all three.

**Autonomy is the point.** Every layer runs on its own tick, so a fresh
instance goes from empty database to articles without anyone pressing
anything. The manual refresh endpoint exists for the case where waiting for
the next pass is the wrong answer, not as the way the system normally works.

## 2. There is a standard, and it is OPML

Worth stating plainly, because the obvious assumption is that a list of lists
needs a format of its own. It does not. The OPML 2.0 specification has a
section called *Directories*:

> A directory may contain an arbitrary structure of outline elements with type
> **include**, **link** or rss.

and defines both ways of pointing at another OPML file:

> An outline element whose type is `include` must have a url attribute that
> points to the OPML file to be included.

> When a link element is expanded in an outliner, if the address ends with
> `.opml`, the outline expands in place. This is called **inclusion**. […] If
> the address does not end with `.opml` the link is assumed to point to
> something that can be displayed in a web browser.

So the list of lists is the same format as the list. A publisher who follows
the spec needs no code here at all.

What has no standard is how a collection is published when it is *not* an OPML
directory — most commonly a repository of loose files with no index. That is
the entire reason the second reader exists.

## 3. One reader per publication shape, never per publisher

`CatalogReader` is the SPI: given a catalogue, enumerate the lists it offers.
Two implementations ship.

| Reader | Handles | Configuration |
|---|---|---|
| `opml-directory` | any spec-compliant OPML directory | the URL |
| `github-opml` | any repository of loose OPML files | `url` = repo, `params.paths`, optional `params.ref` |

The rule that keeps this from sprawling: **a well-known collection is a row in
the database, not a class.** `awesome-rss-feeds` has no code of its own — it is
a `github-opml` catalogue pointed at `plenaryapp/awesome-rss-feeds` with two
paths. The next GitHub collection is a form entry; a genuinely different shape
(an HTML page of links, a JSON API) is the third reader.

### 3.1 What a reader must not do

**Enumerate, do not download.** A reader returns URLs and labels. One that
fetches and parses every list it found has moved the layer below into itself
and turned a daily refresh into a crawl: 66 entries would be 68 HTTP calls
instead of 2.

**Throw rather than return empty.** An empty result means "the directory
offers nothing", and with `DISABLE` that disables every list the catalogue
owns. A network failure must not be able to say that, so it throws
`CatalogReadException`, the failure is recorded against the catalogue, and the
lists stay as they are.

### 3.2 Why `github-opml` does not recurse

Each directory is one call against GitHub's 60-per-hour unauthenticated
budget. A walk of an unknown tree can spend that in a single refresh and then
fail for an hour. Naming the directories costs one line of configuration and
makes the cost of a refresh knowable before it runs.

## 4. Selection

`include` and `exclude` are globs over the entry key — for a file directory
that is its path, which is why they read like paths.

```
include: ["countries/**"]          # the 25 country lists, not the 41 topic ones
exclude: ["**/Memes.opml"]
```

Globs, not regexes: the thing matched is a path, and a stray `.` matching any
character is a filter that quietly takes in its neighbours. `*` stays inside a
segment, `**` crosses them, `?` is one character; exclude wins over include;
an empty `include` means everything.

**The filter lives on the catalogue, not in the UI.** A selection made in a
view would be undone by the next scheduled pass — which is exactly the pass
nobody is watching. Changing either list clears the fingerprint (§5), so a
widened filter takes effect on the next refresh instead of on the next change
upstream.

## 5. Fingerprint instead of ETag

One layer down, a 304 short-circuits the refresh. A catalogue has no single
validator to send — the GitHub reader assembles its answer from several calls
— so the equivalent is a hash over the entry URLs, sorted so that a listing
returned in a different order is not mistaken for a change.

An unchanged fingerprint skips everything **including reconciliation**, and
that is a correctness requirement rather than an optimisation: a set we did
not act on cannot support the conclusion that an entry was dropped. SHA-256
and not `hashCode()`, because a collision here does not produce a wrong page,
it makes a real change invisible until something else moves.

## 6. Ownership and reconciliation

A list carries `originCatalogName` and `lastSeenInCatalogAt`, mirroring
`originListName`/`lastSeenInListAt` on a source.

- A list a human registered, or that a **different** catalogue owns, is
  stamped as still-present and otherwise left alone. Two catalogues carrying
  the same URL must not fight over it; the first claim wins.
- Lists the catalogue no longer offers are handled by `missingListPolicy`:
  `DISABLE` (default), `KEEP`, `DELETE`.
- `DELETE` removes the list and **keeps its sources**, which then belong to
  nobody — the same rule as deleting a list by hand. A directory edit must
  never delete collected articles as a side effect.

Names come from the catalogue's label for the entry:
`awesome-rss-feeds-australia`. The URL-derived form used for feeds
(`tagesschau-de-042f5a`) would make sixty lists served from one CDN sixty
variations of `raw-githubusercontent-com-<hash>`.

## 7. The bundled catalogue, and why it is off

A fresh database gets `awesome-rss-feeds` (CC0, 66 OPML files, ~840 feeds) —
**installed disabled**.

A catalogue is a standing instruction to crawl somebody else's list of
publishers, and more will ship beside this one. An installation that starts
all of them the moment it boots is a surprise for whoever runs it and for
everyone being crawled: those 840 feeds alone are roughly 1,700 outbound
requests an hour. So the catalogue is present, visible, and one switch away —
and which of them to run is decided by the person who runs it, in the console,
without editing a configuration file.

That inverts an earlier decision here, which argued that installing it dormant
makes the first run a manual step. It does. The counterweight is that "several
bundled catalogues, all live on first boot" is worse, and the manual step is
one click on a page that already exists.

Narrow the selection with `munin.catalog.bundledInclude` (e.g.
`["countries/**"]`), or skip the installation entirely with
`munin.catalog.installBundled=false`.

**Disabled governs the schedule, not the data.** The catalogue is created due,
so switching it on starts it at the next tick; a manual refresh works while it
is off, because an explicit request has already decided.

Installed **once**, keyed by name. A catalogue that was then enabled, filtered
or deleted stays that way across restarts — re-asserting bundled configuration
on every boot would make a local decision impossible to keep, and would switch
something back on that somebody turned off.

New catalogues registered through the API are disabled by default for the same
reason; `enabled: true` in the request is honoured.

## 8. Pacing

A catalogue delivers its lists **all due at once**, which is a burst the layer
below was not built for. The list tick used to take one list per five-minute
tick; 65 lists then needed five hours, and 5 per tick still needed an hour —
during which the registry shows 65 lists and no sources at all, which reads as
broken rather than busy.

So a round **drains** instead of trickling: it keeps claiming
`batchSize` lists until nothing is due or `maxPerRound` (200) is reached. Two
bounds rather than one, each with its own job — `batchSize` is the lease
granularity, `maxPerRound` is what makes a round end and release its leases.

What actually paces the burst is politeness, not the tick: all 66 lists of the
bundled catalogue are served from one CDN host, and
`munin.http.minHostInterval` is 2 s. The first round after an import therefore
takes a couple of minutes and does the whole job.

The first sources still appear no sooner than the next list tick — up to five
minutes after the catalogue ran. That is the honest figure; an earlier draft of
this document quoted "sources a minute later", which came from a test run with
the tick intervals shortened and was never true of the defaults.

## 8a. Interval classes

A catalogue is where somebody already knows what kind of sources these are, so
it carries `fetchProfile` and hands it down to its lists and their feeds —
`blog` for a blog collection, nothing for news. The classes themselves are
configuration, not code: see [collection.md](collection.md) §6.1a. Optionally
`sourceFetchIntervalSeconds` overrides the class's starting interval for one
particular collection.

Without this a blog catalogue is unusable at scale: 36,000 indie-web feeds at
the news ceiling are ~72,000 requests an hour, and at a weekly ceiling ~5,000 a
day.

## 9. Where it stops

- **No authentication for `github-opml`.** 60 requests an hour is enough for a
  daily refresh of a handful of catalogues; a token is the change to make when
  that stops being true.
- **No nesting.** A catalogue yields lists, never further catalogues, even
  though an OPML directory could express that.
- **No preview.** The refresh endpoint reports what it did; there is no dry run
  that reports what it would do.
- **Selection is by path, not by content.** "Every list with more than ten
  German feeds" would mean downloading all of them — the thing §3.1 exists to
  prevent.
