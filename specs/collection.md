# Collection

How articles get into the archive: the source registry, the directories that
populate it, the ingest path, deduplication, and the polling schedule.

## 1. Model

```
source_lists  ──populates──▶  sources  ──delivers──▶  articles
```

A **source** is one feed. A **source list** is a directory of feeds (an OPML
file, a plain text list of URLs) that creates and updates sources. An
**article** is one entry, deduplicated across every source that carried it.

Enums, all in `de.mhus.hrafnagud.api`:

| Enum | Values |
|---|---|
| `SourceType` | `RSS` (covers Atom — Rome parses both) |
| `SourceListType` | `OPML`, `TEXT` |
| `SourceOrigin` | `MANUAL`, `LIST` |
| `FetchOutcome` | `OK`, `NOT_MODIFIED`, `FETCH_ERROR`, `PARSE_ERROR` |
| `MissingSourcePolicy` | `DISABLE`, `KEEP`, `DELETE` |
| `LanguageSource` | `SOURCE`, `FEED`, `DETECTED`, `UNKNOWN` |

## 2. Identity and deduplication

### 2.1 A source's identity is its normalised URL

Not its name, not its Mongo id. Two entries pointing at the same feed are the
same source however they were spelled.

Without this, a publisher who starts appending a campaign parameter to its own
feed link gets imported a second time — along with a second copy of its
archive.

### 2.2 Deduplication is a unique index

`dedupKey` is a SHA-256 over the normalised article URL, and its index is
`unique`. Two workers racing on the same feed entry therefore resolve at the
database rather than in application code.

This is not a micro-optimisation. A wire report reaches the archive from every
outlet that carries it; without deduplication the archive would be mostly
duplicates and every query would return the same story a dozen times.

### 2.3 URL normalisation leans conservative

Over-normalising merges distinct articles and loses content, which is
unrecoverable. Under-normalising costs disk. Given that asymmetry the rule is:
fold only what is provably meaningless.

**Folded:** tracking parameters, AMP markers, fragments, `www.`, default
ports, query-parameter order.

**Not folded:** an `m.` host prefix, an `amp.` subdomain. Those are frequently
distinct hosts serving distinct content, and merging them would lose one.

**IRIs are converted, not rejected.** `java.net.URI` returns a null host for a
Unicode domain and throws on a Unicode path, so the obvious implementation
silently drops every internationalised domain — unacceptable in a worldwide
collector. `UrlNormalizer` converts to punycode and percent-encodes the rest
(`toAsciiUri`). This was found by a test, not in production.

### 2.4 An article records every source that delivered it

`sourceNames` is a list. Once articles are deduplicated across sources, "which
feed did this come from" has more than one answer, and keeping only the first
would discard exactly the information that makes the deduplication measurable.

## 3. The ingest path

`ArticleService.ingest(candidate, source, …)` is called once per feed entry per
poll and returns an `IngestOutcome`.

### 3.1 It does not write in the common case

A feed re-serves its whole window on every poll, so most ingest calls concern
an article the same source already delivered. Those resolve to an upsert that
matches, adds nothing and modifies nothing.

There is deliberately **no "still there" timestamp**. It would make the
archive's largest write load a field with no reader. `lastSourceAddedAt` is
named for what it actually is: the last time a feed that *did not already
have* the article delivered it.

### 3.2 It queues every article for body fetching

Regardless of whether the fetcher is switched on. The feed run creates open
jobs; working them is the fetcher's business.

Letting the producer consult the consumer's configuration would bake a runtime
decision into stored state: switch the fetcher on later and every article
collected until then would be stranded — unfetchable, with no way back short
of a bulk rewrite. The cost is honest and small: with the fetcher off, the
`PENDING` partial index covers the whole archive rather than just the backlog.

### 3.3 It decides the translation status at ingest

An article already in the pivot language is stored `SKIPPED`, not queued and
then discovered to be a no-op. See [translation.md](translation.md) §3.

## 4. Language

Stored with its provenance, in `languageSource`:

```
SOURCE  (a human configured it)  ▶  FEED  (the publisher declared it)  ▶  DETECTED
```

A feed's `<language>` element is frequently absent or simply wrong, so a
consumer filtering on "German articles" needs to know whether it is trusting a
publisher or a classifier.

**Detection abstains rather than guesses.** Below `munin.language.minChars`
(default 40) of title plus teaser, the result is `UNKNOWN` — more useful than a
confident wrong answer, and a wrong language is worse than none because
everything downstream trusts it.

Lingua runs in low-accuracy mode. All languages at high accuracy costs several
GB of heap; low accuracy costs a few hundred MB and differs only on very short
input, which `minChars` already excludes.

### 4.1 The text index has a trap in it

A MongoDB text index selects its stemmer per document from a **language
override field**, which defaults to the field named `language`. An article has
one, holding a BCP-47 subtag — and MongoDB accepts exactly fifteen values
there. Anything else is not degraded, it is **rejected on write**:

```
language override unsupported: ja
```

One such entry aborted the whole poll of its feed — every tick, indefinitely. A
worldwide collector that could not store Japanese, Chinese, Korean, Polish,
Czech, Arabic, Ukrainian or Greek, and did not say so.

Two separate faults, and only one of them was about languages. The stemmer
override was the trigger; what made it fatal was that the ingest loop had **no
per-article catch**, so one unstorable entry cost every entry of that feed rather
than itself. Both are fixed: the derived field below removes this trigger, and
the loop now counts a failed entry into `itemsInvalid` and carries on — because
the next trigger will not be a language.

It went unnoticed for the same reason the CJK word count did (see
[content-extraction.md](content-extraction.md) §5): a German and English
archive is exactly the case that works.

The fix is a second, derived field. `language` stays the honest record and
stays in the API; `textLanguage` carries what MongoDB is allowed to see —
a stemmer name, or `none` for a language it cannot stem. `none` rather than a
default: applying English stop words to Japanese is worse than doing nothing,
and it still indexes the tokens. See `TextIndexLanguage`.

> **Upgrading an existing database.** The index definition changes, so the
> first boot against an older archive fails — `IndexKeySpecsConflict` (86),
> because the generated name `ArticleDocument_TextIndex` stays the same while its
> keys and options do not. Auto-creation only ever *creates*; it never redefines.
> Drop `ArticleDocument_TextIndex` and Spring Data recreates it. There is no
> migration framework here yet, and the failure is at least loud — the pod
> crash-loops rather than running with a stale index. Runbook:
> `deploy/README.md` § "Changing an index means dropping it first".

## 5. Categories are stored verbatim

Never normalised. Publishers disagree completely about what a category is;
some emit sections, some emit tags, some emit both in one field. Folding them
into a taxonomy at ingest would destroy information no later step could
recover. A `topics` layer can be built on top later — the reverse is not
possible.

## 6. Polling

### 6.1 Intervals adapt per feed

A fixed interval is wrong in both directions at once: a wire service outruns
it and entries are lost when its feed window rolls over, while a regional
weekly gets polled two thousand times per published item.

- delivering a lot → halve the interval
- delivering nothing → grow it gently
- failing → back off geometrically

Adjustment is **asymmetric on purpose**: react fast to a feed we are behind
on, slowly to a quiet weekend. Bounds come from the source's interval class
(§6.1a), which defaults to `munin.feed.minInterval` / `maxInterval`, with
`maxFailureInterval` capping the failure backoff.

### 6.1a Interval classes (fetch profiles)

Adaptation only moves within the bounds it is given, and one set of bounds
cannot fit every kind of publisher. At the news ceiling of twelve hours, a blog
that posts monthly is polled about **sixty times per article**; at a weekly
ceiling it is four.

So the bounds are named, and the name travels **catalogue → list → source**:

```yaml
munin:
  feed:
    profiles:
      news: { defaultInterval: PT30M, minInterval: PT5M, maxInterval: PT12H }
      blog: { defaultInterval: P1D,   minInterval: PT6H, maxInterval: P7D }
```

A profile inherits every field it does not set from `munin.feed.*`, so adding
the block changes nothing until something names a profile. Names are **free
strings, not an enum** — a new class is a config entry, and nothing in the code
knows the word `blog`.

Three decisions worth keeping:

- **A name, not three numbers on every layer.** "These are blogs" is said once,
  where the collection is registered.
- **An unknown name falls back to the default and warns once**, rather than
  failing. The name arrives from a document somebody typed, sometimes before
  the profile is configured; refusing to poll over a spelling mistake would
  turn a config slip into a gap in the archive.
- **Failure backoff never polls more often than a healthy source of the same
  class.** The global `maxFailureInterval` of a day would otherwise retry a
  broken weekly blog seven times as often as a working one.

The bug this was built for: `defaultFetchIntervalSeconds: 86400` on a list used
to be clamped to the global twelve-hour ceiling — silently, so the setting
looked like it had taken effect. "Poll this once a day" was not expressible.

### 6.2 A failing source is never auto-disabled

Outages end, certificates get renewed, DNS changes settle. Backoff is capped
at a daily retry so a feed that comes back resumes by itself.

A registry that quietly shrinks on every transient problem is one nobody can
trust — and the failure is invisible: nothing announces that a source stopped
being polled.

### 6.3 Politeness is centralised

One HTTP client, one user agent, one per-host rate limiter, one body cap, one
optional proxy (`munin.http.proxy`).

A directory import easily puts fifty feeds of one publisher into the registry.
Without per-host pacing they all get polled in the same second, and the
publisher responds the way any operator would.

`robots.txt` is obeyed for **article pages** and deliberately not consulted
for **feeds** — a feed is published expressly to be polled.

A proxy host configured without a valid port fails at **startup**, rather than
quietly connecting directly. In an environment that requires the proxy, going
direct breaks every fetch and the reason would be nowhere near the mistake.

## 7. Source lists

### 7.1 Authoritative except where a human has spoken

Every field written through the API is recorded in `lockedFields` and becomes
off-limits to the list.

Without it, disabling a feed that publishes garbage lasts until the next
refresh — and after that happens once, nobody bothers correcting anything
again. `POST /api/v1/sources/{name}/unlock` gives the list its authority back.

### 7.2 Dropped sources are disabled, not deleted

`MissingSourcePolicy` defaults to `DISABLE`: effective without being
destructive, so a briefly truncated upstream response cannot delete half the
registry. `DELETE` exists for operators who want it; `KEEP` for lists that are
additive by nature.

### 7.3 `304 Not Modified` skips reconciliation entirely

Not an optimisation but a correctness requirement. Reconciliation decides
which sources the list has dropped, and a document we did not read cannot
support that conclusion. Treating "unchanged" as "empty" would disable every
source the list owns.

## 8. REST surface

```
GET    /api/v1/sources                 list, filter, page
POST   /api/v1/sources                 create
GET    /api/v1/sources/{name}
PUT    /api/v1/sources/{name}          write — records lockedFields
POST   /api/v1/sources/{name}/unlock   give the list its authority back
POST   /api/v1/sources/{name}/fetch    poll now
DELETE /api/v1/sources/{name}

GET    /api/v1/source-lists            + POST, GET/PUT/DELETE {name}
POST   /api/v1/source-lists/{name}/refresh

GET    /api/v1/articles                filter, page; ?withTranslation=true
GET    /api/v1/articles/{id}
DELETE /api/v1/articles/{id}           also drops its enrichments

GET    /api/v1/stats
```

Article content and enrichment endpoints are documented in
[content-extraction.md](content-extraction.md) §6 and
[enrichments.md](enrichments.md) §5.

No authentication — see the README's known gaps.

## 9. Limits

- **One source type.** `SourceType` and the reader SPI exist so a scraper or
  an API client is a new bean, but only `RSS` is implemented.
- **Near-duplicate clustering is not implemented.** `contentHash` is stored and
  indexed so the same story republished at a different URL can be *found*, but
  nothing groups them. The same applies to `article_contents.canonicalUrl`: the
  page's own claim about its identity is recorded and not acted on, because
  merging two articles means moving a row under a unique index and deserves its
  own deliberate change.
- **No backlog visibility.** If the registry outgrows the poll capacity (batch
  size × tick rate), `nextFetchAt` drifts into the past and feeds are polled
  less often than configured, with nothing reporting it.
- **Single instance assumed.** Claims are leases in MongoDB, so a second
  instance would not corrupt anything, but this has not been tested.
