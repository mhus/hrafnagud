# Architecture

## 1. Purpose and scope

Hrafnagud collects news from many sources worldwide, deduplicates articles
across those sources, classifies their language, optionally fetches the full
article text, and stores the result in MongoDB. It then serves that archive to
[Vancetope](https://github.com/mhus/vance) as a feed source.

What it is not: a reader, a ranking engine, or a place where articles are
edited. It collects and hands out. Everything that interprets — rating,
clustering, summarising — either runs as an [enrichment](enrichments.md) or
lives in the consumer.

## 2. Modules

```
server/
  hrafnagud-api/       DTOs and enums crossing the REST boundary. No Spring, no MongoDB.
  hrafnagud-munin/     Source registry, feed ingest, deduplication, full-text fetch,
                       enrichment storage, persistence, the operator REST surface.
  hrafnagud-translate/ Works Munin's translation backlog by calling a Vancetope brain.
  hrafnagud-centauri/  Serves the archive to Vancetope as a Centauri feed source.
  hrafnagud-server/    Boot module: entrypoint plus runtime configuration, nothing else.
```

### 2.1 The one hard rule

**Munin has no dependency on Vancetope.** The archive must be collectable and
queryable without a brain anywhere near it, and that is the property the
module boundary protects.

Two modules face Vancetope, and they are mirror images:

| Module | Direction | Depends on |
|---|---|---|
| `hrafnagud-translate` | calls a brain | `vance-ode-ursa` |
| `hrafnagud-centauri` | answers a brain | `vance-ode-centauri` |

Both depend on `hrafnagud-munin`; neither is depended upon by it. Removing
both leaves a working collector, which is the test of whether the boundary is
real or notional.

`hrafnagud-server` is deliberately thin. A second feature module — Hugin, the
querying half — would sit beside Munin rather than inside it, and that is only
possible while no module owns the application.

### 2.2 Where the REST surface comes from

The operator API (`/api/v1/**`) is written in Munin. The feed contract
(`/ode/feed/**`) is **not** written here at all: `vance-ode-centauri` serves
it, given a `FeedSource` bean. `hrafnagud-centauri` supplies that bean and
nothing else — see [feed-source.md](feed-source.md).

## 3. Stack

Java 25, Spring Boot 4.1, Maven multi-module, Lombok, JSpecify
(`@NullMarked` per package), Spring Data MongoDB, Rome (feed parsing), jsoup
(HTML and OPML), Lingua (language detection), Apache Commons, JUnit 5 +
Mockito + AssertJ.

Same stack as Vancetope, deliberately: one set of conventions to hold in your
head across both repositories.

## 4. Collections

| Collection | Holds | Why separate |
|---|---|---|
| `sources` | one feed each: URL, poll schedule, failure history, statistics | — |
| `source_lists` | directories that populate `sources` | — |
| `articles` | article metadata, deduplicated across sources | — |
| `article_contents` | extracted bodies, images, page metadata | bodies are ~50× larger than the metadata, and most queries want the metadata |
| `enrichments` | output of processing steps, one document per run | see [enrichments.md](enrichments.md) §2 |

### 4.1 Queues are state fields, not queries

Both asynchronous pipelines — body fetching and translation — are driven by a
status field on the article plus a partial index on the pending value:

```
content_queue_idx      { contentNextAttemptAt: 1 }      partial: contentStatus = PENDING
translation_queue_idx  { translationNextAttemptAt: 1 }  partial: translationStatus = PENDING
```

The partial filter is what keeps the index proportional to the *backlog*
rather than to the archive — the difference between thousands of entries and
tens of millions.

The alternative for translation would be to derive the queue by asking which
articles have no `TRANSLATION` enrichment. That is a join across two
collections on every tick, against one indexed scan.

### 4.2 The schedule field is the lease

`contentNextAttemptAt` and `translationNextAttemptAt` each serve two purposes:
they are the retry schedule *and* the claim lease. Claiming a batch pushes the
timestamp out, so a worker that dies mid-work releases its articles when the
lease expires — no separate lock collection, no lock to leak.

The attempt counter is incremented **at claim time**, not at completion. An
article that reliably crashes the worker therefore exhausts its budget instead
of being retried forever.

## 5. Entity conventions

- `id` — Mongo ObjectId. Persistence only; not used to identify things in
  application logic.
- `name` — the technical, unique key within its scope. This is the fachliche
  identity: a source is addressed by name, not by id.
- `title` — display name, not unique.

A source's *identity*, however, is neither: it is the normalised URL. See
[collection.md](collection.md) §2.1.

## 6. Ordering: two timestamps, two questions

`articles` carries both `firstSeenAt` (when this archive learned of the
article) and `publishedAt` (when the publisher says it appeared, nullable).
They are not interchangeable, and which one is correct depends on who is
asking:

| Consumer | Orders by | Because |
|---|---|---|
| Operator API (`/api/v1/articles`) | `firstSeenAt` | "what has this archive collected lately". Stable under late arrivals; immune to a publisher with broken dates. |
| Feed contract (`/ode/feed/items`) | `publishedAt` | a reader merges several sources into one timeline, and the article's own timestamp is the only comparable key. |

Publishers backdate, forward-date and mis-timezone often enough that one
broken feed would dominate any sort built on `publishedAt` alone — which is
why the operator surface does not use it, and why it is sanity-checked and
nulled when implausible. The feed contract has no choice: see
[feed-source.md](feed-source.md) §4.

Indexes exist for both (`seen_idx` and friends, `published_idx` and friends).

## 7. Configuration

All runtime configuration is bound under two prefixes:

- `munin.*` — collection, extraction, translation, language, HTTP politeness,
  and whether the feed source is served (`munin.centauri.enabled`).
- `vance.ode.*` — the Vancetope binding. `vance.ode.base-url` and the event
  block are the outbound side; `vance.ode.centauri.*` is the served side.
  Everything outbound is inert until `base-url` is set.

`munin.centauri.enabled` is deliberately not called `munin.feed.enabled`:
`munin.feed` already configures feed *ingest*, which is the opposite
direction.

## 8. Testing

Unit tests with Mockito against mocked services, no Spring context where none
is needed. The interesting exceptions are the two places where reasoning has
repeatedly been wrong:

- **A fixture corpus of real article pages**, reduced to their structural
  skeleton, drives the extraction tests. Two defects that no amount of
  reasoning had surfaced were found by it on its first run — see
  [content-extraction.md](content-extraction.md) §7.
- **A real `HttpServer`**, not a mock, for the Ode client tests. What is being
  tested there is a wire contract.
