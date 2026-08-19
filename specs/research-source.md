# Research source

Answering Vancetope research queries out of the archive.

## 1. Scope, and how it differs from the feed

`de.mhus.hrafnagud.zarniwoop` implements `SearchSource` from
`vance-ode-zarniwoop` and publishes it as a bean; the Ode module serves the
REST contract over it. Same shape as [feed-source.md](feed-source.md): no
controller here, no path, no serialisation.

Same archive, opposite question:

| | Centauri (feed) | Zarniwoop (research) |
|---|---|---|
| Asks | "give me a timeline" | "answer this" |
| Order | chronological | relevance |
| Paging | cursor, endless | one shot, `maxResults` |
| Reader | a person scrolling | a model inside a research turn |

Both read-only. Serving both is not duplication — a chronological page and a
ranked answer are different products of the same data.

Off by default, same reasoning and same shape as the feed:

```yaml
munin:
  zarniwoop:
    enabled: true            # HRAFNAGUD_ZARNIWOOP_ENABLED
vance:
  ode:
    zarniwoop:
      path: /ode/search
      apiKey: ""             # empty = no check
      maxResults: 25
```

The two surfaces switch independently: serving a timeline is not a reason to
answer queries, or the reverse.

## 2. Relevance is a third ordering

`ArticleService` now sorts three ways, and each answers a different question:

| Method | Orders by | Question |
|---|---|---|
| `search` | `firstSeenAt` | what has this archive collected lately |
| `pageByPublished` | `publishedAt` | what was published, in order |
| `searchByRelevance` | text score | what best matches these words |

A search result sorted by date is not a search result: the best match is
rarely the newest document, and a caller that gets one page has no way to look
past it. `searchByRelevance` refuses a query with no text rather than silently
degrading to one of the other two.

The filter half is shared — a relevance search applies the same source,
language, category and date filters as a browse, and differs only in how it
sorts.

## 3. Searching the translation *and* the original

MongoDB allows **one text index per collection**, and it has to live on the
document being searched. Enrichments therefore cannot be indexed where they
are; instead the newest translation is mirrored onto the article:

| Field | Weight | Holds |
|---|---|---|
| `title` | 10 | the article as collected |
| `summary` | 3 | " |
| `pivotTitle` | 10 | the newest translation |
| `pivotSummary` | 3 | " |

Same weights as their originals, so a translated title ranks like a title.
Written by exactly one caller — `ArticleService.recordTranslated`, from the
same step that records the enrichment. The enrichment stays the append-only
record; these two fields are a derived read model and a re-run overwrites
them, which is correct: the newest translation is the one a reader is shown,
so it is the one that should be findable.

Measured against the live archive, with a German pivot over English sources:

```
"tariffs" → 1 hit   Trump setzt neue Zölle auf Kanada …
"Zölle"   → 1 hit   Trump setzt neue Zölle auf Kanada …
"Canada"  → 1 hit   "
"Kanada"  → 1 hit   "
```

Before the mirror, `Zölle` returned nothing while `tariffs` returned the
article — displayed with its German title. That asymmetry was documented as a
limitation of the feed; for a research provider it would have been the core
function failing, because the caller is a model formulating in the user's
language.

### 3.1 Bodies, as a second tier

The extracted article text is searched too — but it lives in
`article_contents`, its own collection, and one text index per collection
means one index there as well. Two indexes produce two scores, over different
fields, on scales that are not comparable. Ranking them against each other
would be arithmetic on incomparable numbers.

So they are **concatenated, not merged**: every metadata hit, then every body
hit, each tier ordered by its own score.

```
"Trump"                                        "Ukraine"
1. [head] Why has Trump shifted on North Korea 1. [head] Sacked Ukrainian defence minister …
2. [head] Trump pauses new tariffs on Canada   2. [head] Why has Russia threatened the UK …
3. [head] South Korea shortens war games …     3. [body] BBC visits smouldering Kyiv market
4. [body] Liberia agrees to accept deportees   4. [body] Ukrainian man arrested in Croatia
5. [body] Sacked Ukrainian defence minister …  5. [body] South Korea shortens war games …
```

The ordering is a statement anyone can check: in news a headline match is a
stronger signal than a mention somewhere in the body. The cost, stated rather
than hidden: an overwhelming body match ranks below a weak headline match.

Two practical details. The second query **only runs when the first did not
fill the page**, so the common case stays one query. And because every filter
is a property of the *article* while the text is in the content collection,
the body search over-fetches by a bounded factor (4×) and then re-applies the
filters — an unbounded over-fetch to satisfy a filter is a full scan with
extra steps.

Proof that it reaches text no headline carries: `flabbergasted` occurs in
exactly one body and in no title or teaser in the live archive, and the query
returns exactly that article.

### 3.2 The same trap, one collection later

`article_contents` had no text index until bodies became searchable — and it
has a `language` field too, holding the page's declared language. Adding the
index would have brought MongoDB's language override with it and made a
Japanese page unstorable, exactly as in
[collection.md](collection.md) §4.1. It carries its own `textLanguage` for
the same reason, set in the one service method that stores a body.

### 3.3 The query's own language

`OdeSearchQuery.locale` is passed through as the stemmer for the *query*
(`TextIndexLanguage`, the same mapping the documents use). A locale MongoDB
cannot stem falls back to no stemming, which matches literally rather than
wrongly.

## 4. Declared capabilities

| Capability | Value | Reasoning |
|---|---|---|
| `modalities` | `NEWS` only | a news archive answering an `ACADEMIC` query would be answering a question it was not asked |
| `domains` | `NEWS` | " |
| `tiers` | `NORMAL` + `EXPERT` | there is a real filter vocabulary behind it — the contract warns against declaring `EXPERT` without one |
| `expertParams` | `source`, `originalLanguage`, `category`, `since`, `until` | Munin stores all five |
| `maxResults` | 25 | these go into a model's context, where the twentieth hit costs tokens and adds little |
| `servesContent` | true | bodies exist, on demand |
| `cacheTtl` | 1 h | the answer is a constant |

`since` and `until` map to `publishedSince` / `publishedUntil`, not to the
`firstSeenAt` window: a caller asking for "published after" means the
article's date, not ours.

`originalLanguage` is named for the field it filters, and the name is the point.
A hit is *presented* in the pivot language and labelled with it (§6), so a filter
called `language` would have meant two different fields under one word: a model
that saw `language: de` on every row and narrowed on `language: de` would have
removed exactly the translated articles. The filter and the extras key now agree:
`originalLanguage` in both.

## 5. Two contract rules that are easy to get backwards

**An empty result is not an exception.** Throwing makes Vancetope treat the
source as broken and stop asking for minutes — right for a dead index, wrong
for a quiet day. Nothing found returns `OdeSearchResponse.empty(note)`; a
search that *could not run* propagates.

**An unusable expert param is ignored, not refused.** The caller cannot know
this source's schema, so refusing one filter it guessed at costs the whole
query. An unparsable `since` is dropped with a debug line and the search runs.

## 6. Hits

Presented in the pivot language where a translation exists, the same way the
feed does it — a model reading the result should not have to notice which
entries the archive happened to translate. The original travels in `extras`.

`extras` carries `publishedAt`, `language` (the language it is *shown* in),
`categories`, `bodyWords` and the original title/language — the latter under
`originalLanguage`, the same name the expert filter uses (§4). Recency is not the ranking here, so a model judging a
news hit has no other way to tell whether it is reading last week or last year.

### 6.1 Bodies are offered, not shipped

A hit whose body has been fetched offers `STASH_ON_DEMAND` with the article id
as `contentId`. Twenty-five bodies shipped with a result list would spend the
research turn's context before the model has picked one.

A hit with no body offers **nothing** rather than a promise that resolves to a
404. `GET /ode/search/content/{id}` returns the extracted text as UTF-8
`text/plain` — always plain text, because what is stored is extracted prose
rather than the original document, even though the contract carries bytes so
that other sources can serve a PDF.

`sizeBytes` is reported as `0`, meaning unknown: the archive counts words, not
bytes, and reporting one as the other would be a plausible-looking wrong
number. The word count travels in `extras.bodyWords` instead.

## 7. Verified behaviour

Against the running service, real archive, real endpoints:

```
no api key                                   → 401
/capabilities                                → NEWS, EXPERT+NORMAL, maxResults 25
/search  "tariffs" / "Zölle"                 → the same article, both languages
/search  EXPERT source=bbc-world since=…     → 3 hits, all presented in de
/search  EXPERT source=does-not-exist        → 0 hits + note, not an error
/search  EXPERT since="letzten Dienstag"     → HTTP 200, filter ignored
```

## 8. Limits

- **A body hit can never outrank a headline hit**, however much better it is
  — see §3.1. The alternative needs a defensible way to compare two scores
  from two indexes, and there is not one.
- **Bodies only help where they have been fetched.** Full-text fetching is
  opt-in (`munin.content.enabled`), so on an archive that never ran it the
  second tier finds nothing and costs one empty query per search that did not
  fill its page.
- **One modality.** `NEWS`. The archive has nothing to say about `ACADEMIC` or
  `BOOK` and declines to pretend.
- **No de-duplication of near-identical stories in a result set.** The same
  wire report from three outlets is three hits. `contentHash` exists for this
  and nothing groups on it yet — see [collection.md](collection.md) §9.
