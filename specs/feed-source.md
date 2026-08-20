# Feed source

Serving the archive to Vancetope as a Centauri feed source.

## 1. Scope and shape

`de.mhus.hrafnagud.centauri` implements one interface — `FeedSource` from
`vance-ode-centauri` — and publishes it as a bean. The REST contract is served
by the Ode module, which is conditional on that bean existing. This package
contains no controller, no path, no serialisation.

Read-only in the strong sense: it answers questions and changes nothing.
Collection, deduplication, body fetching and translation all run on their own
schedules whether anybody reads or not, which is why a reader can be added and
removed without the archive noticing.

## 2. On by default, and switchable

```yaml
munin:
  centauri:
    enabled: true              # HRAFNAGUD_CENTAURI_ENABLED, default true
vance:
  ode:
    centauri:
      path: /ode/feed          # what Vancetope assumes
      apiKey: ""               # empty = no check
      maxLimit: 200
```

**The default belongs here, not in the library.** `vance-ode-centauri` cannot
know whether its host wants to serve, so there it is opt-in — the module stays
dormant until a `FeedSource` bean exists. Hrafnagud does want to serve: that is
what the package is for, and a collector that has to be told to hand anything
out has the switch on the wrong side.

It stays a switch for the deployment that only collects. What it is **not** is
a security boundary. The endpoint is unauthenticated until `apiKey` is set —
and so is the operator API, which also deletes. Both assume a loopback binding
or a proxy in front; making this one endpoint the exception would have bought
nothing while looking like it bought something.

**Off is announced.** `@ConditionalOnProperty` skips the configuration class
entirely, so a disabled surface publishes no bean, gets no controller, and
answers 404 with nothing in the log — a healthy service and a dead path,
indistinguishable from a broken deployment and diagnosable only from the
process environment. `CentauriDisabledNotice` logs one line instead. The switch
was defensible; its silence was not, and it cost two debugging rounds before
anyone suspected a flag.

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
  [content-extraction.md](content-extraction.md) §9.

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

**Every entry carries its own cursor**, in `OdeItem.cursor`. That is not
redundancy next to the page-level `nextCursor`: the reader merges this stream with
others, so it usually shows only part of the batch it fetched and has to resume
from the entry it stopped at — which a page-level token cannot express. Without
the per-entry token the reader falls back to the bare item id, which this source
cannot parse; it would read that as "start at the beginning" and the reader's
scroll would serve the same page forever. Nothing errors, which is why it is
stated here: the tolerance for an unparsable cursor above is exactly what makes
the failure quiet.

### 4.3 `hasMore` is answered by looking

One row beyond the requested limit is fetched. An unfiltered count over a
growing archive is the expensive half of a listing endpoint, and it is paid on
every page turn.

## 5. Declared capabilities

| Capability | Value | Reasoning |
|---|---|---|
| `selectorMode` | `ENUMERABLE` | the source registry is finite and named |
| `pushdownTextSearch` | **true** | title + teaser, original and translation; see §5.1 |
| `pushdownLanguage` | **false** | see §5.2 |
| `pushdownSince` | true | on `publishedAt` — the same key the stream is ordered by, so bound and order agree |
| `supportsNewerDirection` | true | trivial with a timestamp cursor |
| `carriesFullBody` | false | bodies are a separate collection and a separate fetch; many entries have none |
| `maxPageSize` | 100 | what one page of a news archive is worth reading; `maxLimit` bounds what a request may cost |
| `signalsAccepted` | *empty* | see §5.3 |
| `carriesControlUrl` | false | this service has no UI to link into |
| `capabilitiesTtl` | 30 min | sources are added by an operator, not by the minute |
| `facets` | `origin-place`, `subject-topic` | §9 |

Every pushdown left false is a filter Vancetope applies itself after fetching.
Nothing is lost by declining one; what is lost is efficiency.

### 5.1 Text search is claimed

The article collection's text index covers title and teaser **in both the
article's own language and the pivot translation**, so a query in either finds
the article. The feed gets that for free: its filter runs through the same
`buildQuery` and therefore the same index as the research provider — see
[research-source.md](research-source.md) §3.

It was not always so. This section previously documented an asymmetry — a
translated entry findable by `tariffs` but not by `Zölle`, while being
*displayed* with its German title. Tolerable for a feed, where search is a
convenience; not tolerable for a research provider, where it is the whole
function. Building that provider is what forced the fix.

What the feed still does not search is the **article body** — the second tier
in §3.1 of that document is a `searchByRelevance` feature, and the feed's
filter is a chronological query with a text predicate on it.

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
- **No `category:` or `country:` selector.** Both dimensions are answerable,
  but not as selectors — see §9.
- **Translations are not searchable** (§5.1).
- **Signals are refused** (§5.3), and `controlUrl` is absent because there is no
  UI. Both would change if Hrafnagud ever grew one.

## 9. Facets

Place and topic are filters, not streams. The distinction is the whole reason
they are not selectors: a selector says *which stream*, and one stream carries
one selector string, so „Asian **and** about sport" would need a conjunction
inside an opaque value that the reader cannot render. Two configured streams
would be a union, not an intersection. A facet is a subset of whatever stream
is selected, and facets combine.

| Key | From | Shape |
|---|---|---|
| `origin-place` | `originPlaceIds` | hierarchical, M.49 above the country, ISO at it, whole table inline |
| `subject-topic` | `topicIds` | hierarchical, IPTC Media Topics, ~1,400 concepts served level by level |

Both are declared to the research contract as well, from the same
`de.mhus.hrafnagud.facet.ArchiveFacets` — the two surfaces ask the same
question of the same field, and declaring it twice is how the answers drift
apart.

**Declaring one is a promise to apply it.** Vancetope does no local facet
filtering: it neither post-filters nor asks entries to carry facet values, so a
reader that selects a facet we did not declare skips this source for that
request. That is why the verbatim publisher categories are *not* offered as a
facet: 7,365 distinct strings ([categories.md](categories.md) §1), some of them
places and some a person's name, would make a picker nobody can use and a
filter that finds a third of what it claims.

**Both are materialised paths already**, so one selected node answers for every
rung below it — `m49:142` finds a Singaporean publisher, `medtop:15000000`
finds an article tagged only *Cricket*. Several values of one key are an „or"
(`$in` over the same multikey index); several keys are an „and".

**`origin-place` is the publisher, not the subject.** [geo.md](geo.md) §1 says
why that distinction is in the key name rather than in a footnote. A
`subject-place` facet is the obvious next one and needs `contentLocation`,
which needs extraction — the same wait as everything else in that document.
