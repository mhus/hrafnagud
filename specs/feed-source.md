# Feed source

Serving the archive to Vancetope as a Centauri feed source.

## 1. Scope and shape

`hrafnagud-centauri` implements one interface — `FeedSource` from
`vance-ode-centauri` — and publishes it as a bean. The REST contract is served
by the Ode module, which is conditional on that bean existing. This module
contains no controller, no path, no serialisation.

Read-only in the strong sense: it answers questions and changes nothing.
Collection, deduplication, body fetching and translation all run on their own
schedules whether anybody reads or not, which is why a reader can be added and
removed without the archive noticing.

## 2. Off by default

```yaml
munin:
  centauri:
    enabled: true              # HRAFNAGUD_CENTAURI_ENABLED
vance:
  ode:
    centauri:
      path: /ode/feed          # what Vancetope assumes
      apiKey: ""               # empty = no check
      maxLimit: 200
```

Collecting news should not imply publishing it. And the endpoint is
**unauthenticated until a key is set** — Ode's `apiKey` default is empty by
design, so that a host with its own security in front of the path is not forced
into a second scheme. A module that started exposing the archive over HTTP
merely by being on the classpath would make that somebody else's surprise.

The startup log states which of the two auth situations you are in.

`munin.centauri.enabled` deliberately does not reuse `munin.feed`, which
configures feed *ingest* — the opposite direction.

## 3. Streams

The selector grammar is small, with room in it:

| Selector | Stream |
|---|---|
| `all` (or empty) | everything the archive holds |
| `source:<name>` | one feed, by registry name |

Prefixed so that a later `category:` or `country:` does not have to guess
whether `tech` is a category or a source that happens to be called that.

`capabilities().selectorMode` is `ENUMERABLE`, and `selectors()` returns `all`
plus one entry per **enabled** source. A disabled feed still has articles in the
archive, but offering it as a stream promises updates that will not come.

Two non-answers, both deliberate:

- A selector **outside the grammar** yields an empty page and costs no query. A
  reader may hold a selector from a version that understood more than this one
  does; an empty timeline beats a source that reports itself broken.
- A `source:` selector naming a **deleted** source is answered by the query
  returning nothing. Checking the registry first would cost a lookup on every
  page turn to produce the same empty answer.

## 4. Ordering and paging

**Ordered by `publishedAt`**, which is what the contract merges on — and
deliberately not by `firstSeenAt`, which is what the operator API uses. See
[architecture.md](architecture.md) §6 for why the archive keeps both.

The two differ most whenever a feed is added: everything it carries arrives at
once and is weeks old. Ordering by collection time would drop a month of
history into a reader's timeline at today's date.

`ArticleService.pageByPublished` serves this, over `published_idx` and its
source/language variants.

### 4.1 Consequences, stated because they are real

- **An article published before a reader's cursor but collected after it** sits
  behind that cursor. Scrolling backwards finds it; a pull-forward does not.
  Inherent to a chronological contract over an archive that learns about the
  past.
- **Articles without a `publishedAt` never appear.** The contract says an entry
  without a timestamp cannot take part, and there is no defensible position for
  them in a chronological stream. Deriving one from `firstSeenAt` would place a
  week-old article at today's date. They remain collected and queryable through
  `/api/v1/articles`. Nothing reports how many are affected.
- **Page metadata does not correct the timestamp.** A page's own
  `datePublished` is often better than the feed's, but adopting it would change
  the ordering of an article *after* a reader has seen it — see
  [content-extraction.md](content-extraction.md) §8.

### 4.2 The cursor carries the id

`(publishedAt, articleId)`, wire form `<iso-instant>|<id>` — opaque to
Vancetope by contract, which is why it stays readable rather than being base64'd
into something nobody can debug. Opaque means "the reader does not interpret
it", not "the reader must not be able to".

The id is not decoration. Feeds publish in batches sharing a timestamp to the
minute, so a timestamp-only cursor must choose between `<` — which skips every
sibling of the last row on the page — and `<=`, which returns them again. Both
are visible to a reader as missing or duplicated entries.

An **unparsable** cursor is not an error: it decodes to `null`, meaning "start
at the beginning". A reader that kept a cursor across a change of format would
otherwise be stuck on a stream it can never open again. One glitchy page beats a
permanent error.

### 4.3 `hasMore` is answered by looking

One row beyond the requested limit is fetched. An unfiltered count over a
growing archive is the expensive half of a listing endpoint, and it is paid on
every page turn.

## 5. Declared capabilities

| Capability | Value | Reasoning |
|---|---|---|
| `selectorMode` | `ENUMERABLE` | the source registry is finite and named |
| `pushdownTextSearch` | **true** | see §5.1 |
| `pushdownLanguage` | **false** | see §5.2 |
| `pushdownSince` | true | on `publishedAt` — the same key the stream is ordered by, so bound and order agree |
| `supportsNewerDirection` | true | trivial with a timestamp cursor |
| `carriesFullBody` | false | bodies are a separate collection and a separate fetch; many entries have none |
| `maxPageSize` | 100 | what one page of a news archive is worth reading; `maxLimit` bounds what a request may cost |
| `signalsAccepted` | *empty* | see §5.3 |
| `carriesControlUrl` | false | this service has no UI to link into |
| `capabilitiesTtl` | 30 min | sources are added by an operator, not by the minute |

Every pushdown left false is a filter Vancetope applies itself after fetching.
Nothing is lost by declining one; what is lost is efficiency.

### 5.1 Text search is claimed, with a known asymmetry

The article collection carries a text index over its own title and teaser. A
**translated** entry is therefore searchable by its *original* words and not by
the ones the reader is looking at. Live, against a German-pivot archive:

```
'Zölle'   → 0 hits
'tariffs' → 1 hit   (displayed as: „Trump setzt neue Zölle auf Kanada …")
```

Claimed anyway, and this is the decision that goes the other way from §5.2.
Declining the language pushdown costs nothing. Declining this one would make
the reader page the whole archive to resolve one search. Wrong for the
translated minority beats unusable for everyone.

The fix is to index the enrichments — a named next step, not a subtlety left
unsaid. See [enrichments.md](enrichments.md) §6.

### 5.2 Language pushdown is declined

A translated article is served **in the pivot language** (§6), so what the
reader sees and what the article stores are different languages. A pushdown here
would filter the stored original while displaying the translation — quietly
wrong rather than visibly broken.

Declining it costs nothing, because the reader filters the `language` field it
was given, which is the presented one. It lands on the right answer for free.

### 5.3 No signals are accepted

`OdeSignal` is a closed back-channel vocabulary — report, clip. The archive has
nowhere to put a report and nothing to do with a clip.

An empty `signalsAccepted` makes the reader **hide** those controls. Declaring
one and dropping it would leave a button that appears to work.

## 6. How an item is presented

A translated article is served translated. That is what the pivot language is
for: everything downstream reads one language, and a reader that has to notice
which entries were translated has been handed the archive's internals.

| Field | Translated article | Untranslated |
|---|---|---|
| `title`, `summary` | the translation | the original |
| `language` | the pivot | the article's own |
| `extras.originalTitle` / `originalLanguage` | the original | absent |
| `extras.translationModel` | the model that answered, when known | absent |
| `extras.sources` | every feed that delivered it — plural, because deduplication merges them | same |
| `body` | always null (`carriesFullBody` is false) | same |
| `controlUrl` | always null | same |
| `tags` | the article's verbatim categories | same |

An article **still waiting** for its translation is served as it is, in its own
language. Withholding it until translated would make the newest entries of a
news archive the invisible ones — the opposite of what a news archive is for.

Nothing is lost: a reader that wants to show provenance can, and one that does
not need not know.

### 6.1 Bodies

`body(itemId, …)` reads `article_contents` and returns **absent** when there is
no text — which the contract turns into a 404.

Absent rather than empty, and the two are genuinely different here: body
fetching is opt-in and asynchronous, so "no text yet" is the normal state of a
fresh entry rather than a missing entry.

Fetch-on-demand — letting a `body()` request queue the fetch it just missed —
is an obvious next step and deliberately not built, because it turns a
read-only surface into one with a side effect on the content pipeline.

## 7. Verified behaviour

Against the running service, real archive, real endpoints:

```
no api key                          → 401
/capabilities                       → ENUMERABLE, maxPageSize 100, TTL PT30M
/selectors                          → all · source:bbc-world (en) · source:tagesschau (de)
/items?selector=all&limit=3         → 12:19 · 12:13 · 12:11   hasMore, cursor
  … &cursor=<cursor>                → 12:07 · 11:54 · 11:44   strictly descending, no repeat
  … &direction=NEWER&since=12:00    → 12:07 · 12:11 · 12:13   ascending
  … &text=tariffs                   → 1 hit (see §5.1)
/item/<id>                          → 404 while no body has been fetched
```

## 8. Limits

- **No retention.** The archive grows and a reader pages it, so this does not
  bite the contract — it bites the disk.
- **No `category:` or `country:` selector**, though both dimensions are stored.
  The grammar has room for them.
- **Translations are not searchable** (§5.1).
- **Signals are refused** (§5.3), and `controlUrl` is absent because there is no
  UI. Both would change if Hrafnagud ever grew one.
