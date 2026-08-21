# Architecture

## 1. Purpose and scope

Hrafnagud collects news from many sources worldwide, deduplicates articles
across those sources, classifies their language, optionally fetches the full
article text, and stores the result in MongoDB. It then serves that archive to
[Vancetope](https://github.com/mhus/vance) two ways: as a chronological feed,
and as a research provider answering queries.

What it is not: a reader, a ranking engine, or a place where articles are
edited. It collects and hands out. Everything that interprets — rating,
clustering, summarising — either runs as an [enrichment](enrichments.md) or
lives in the consumer.

## 2. Packages

One Maven artifact, `de.mhus.hrafnagud:hrafnagud`, built from the repository root.

```
de.mhus.hrafnagud.
  api/        DTOs and enums crossing the REST boundary. No Spring, no MongoDB.
  munin/      MEMORY. Source registry, feed ingest, deduplication, full-text
              fetch, enrichment storage, persistence, the operator REST surface.
  hugin/      THOUGHT. Everything that hands text to a model.
    translate/  works Munin's translation backlog through a Vancetope brain
    classify/   decides what a publisher's category means, by asking one
  centauri/   Serves the archive to Vancetope as a Centauri feed source.
  zarniwoop/  Answers Vancetope research queries out of the archive.
  facet/      What both of those let a reader filter by, declared once.
  config/     The three property roots: munin.*, hugin.*, hrafnagud.*
  settings/   The values in force — config plus what an operator overrode.
  server/     Entrypoint plus runtime wiring, nothing else.
```

### 2.0a Two ravens, two budgets

The split is not decoration. **Munin remembers**: it collects and stores, and it
does so without a brain anywhere near it. **Hugin thinks**: it interprets what
Munin holds. Where a new subsystem goes follows from one question — does it hand
text to a model?

It is also the line between two budgets, which is why the two halves have
opposite defaults. Munin spends requests against publishers who did not ask to
be crawled, so its defaults are chosen for politeness and its collection runs by
itself. Hugin spends model time somebody pays for, so **its workers are off
until an operator switches them on**.

`config/` and `settings/` sit beside both because they serve both; a package
under either half would be claiming an ownership it does not have. The Vancetope-
facing servers (`centauri`, `zarniwoop`, `facet`) sit outside both because they
neither remember nor think — they hand out what Munin holds.

### 2.0 Why one artifact and not six

These were six Maven modules, which made the layering below compiler-enforced.
That is worth paying for in software that loads third-party code — and
hrafnagud has no add-on system, no published partial artifact and no consumer
of anything but the whole thing. What the split bought was a boundary; what it
cost was six poms, a reactor, and test fixtures that could not be shared across
modules that obviously wanted them. For a service one person builds in one go,
that is the wrong side of the trade.

The packages keep the names and the meaning. What changed is who enforces §2.1:
the compiler did, and now `ModuleBoundaryTest` does.

### 2.1 The one hard rule

**Munin has no dependency on Vancetope.** The archive must be collectable and
queryable without a brain anywhere near it.

Four package roots face Vancetope. Hugin calls out, two answer, one is shared
by the two that answer:

| Package | Direction | Uses |
|---|---|---|
| `hugin.translate` | calls a brain | `vance-ode-ursa` |
| `hugin.classify` | calls a brain | `vance-ode-ursa` |
| `centauri` | answers a brain | `vance-ode-centauri` |
| `zarniwoop` | answers a brain | `vance-ode-zarniwoop` |
| `facet` | neither — declares filter dimensions for both | `vance-ode-core` |

All of them import from `munin`; none is imported by it. **Deleting `hugin/`,
`centauri/`, `zarniwoop/` and `facet/` must leave a compiling collector** — that
is the test of whether the boundary is real, and it is now a thing to check
rather than a thing that fails the build. Concretely: no
`import de.mhus.hrafnagud.{hugin,centauri,zarniwoop,facet}` and no
`import de.mhus.vance.ode` anywhere under `munin/` — and, because Munin imports
them, not under `settings/` or `config/` either, which is where a Vancetope
import would otherwise reach Munin's classpath through the back door.

Each of them is also runtime-switchable and stays that way: `hugin.translate`
and `hugin.classify` are inert until `vance.ode.base-url` is set — they call
out, and without an address there is nowhere to call — while `centauri` and
`zarniwoop` answer and are therefore **on unless switched off**
(`munin.centauri.enabled` / `munin.zarniwoop.enabled`). The asymmetry is the
point: a missing address is a configuration that does not exist yet, a serving
surface is what these packages are for. An installation that wants none of them
ships them dormant rather than not at all, which is the one thing the merge
actually gave up.

### 2.2 Where the REST surface comes from

The operator API (`/api/v1/**`) is written in Munin. Neither Vancetope-facing
contract is written here at all: `vance-ode-centauri` serves `/ode/feed/**`
given a `FeedSource` bean, and `vance-ode-zarniwoop` serves `/ode/search/**`
given a `SearchSource` bean. The two packages supply those beans and nothing
else — see [feed-source.md](feed-source.md) and
[research-source.md](research-source.md).

## 3. Stack

Java 25, Spring Boot 4.1, Maven (single module), Lombok, JSpecify
(`@NullMarked` per package), Spring Data MongoDB, Rome (feed parsing), jsoup
(HTML and OPML), Lingua (language detection), Apache Commons, JUnit 5 +
Mockito + AssertJ.

Same stack as Vancetope, deliberately: one set of conventions to hold in your
head across both repositories.

## 4. Collections

| Collection | Holds | Why separate |
|---|---|---|
| `sources` | one feed each: URL, poll schedule, failure history, statistics | — |
| `category_mappings` | one publisher category each: what it was decided to mean, and by what | see [categories.md](categories.md) |
| `source_catalogs` | directories of source lists — where lists come from, so the registry fills itself | see [catalogs.md](catalogs.md) |
| `source_lists` | directories that populate `sources` | — |
| `articles` | article metadata, deduplicated across sources | — |
| `article_contents` | extracted bodies, images, page metadata | bodies are ~50× larger than the metadata, and most queries want the metadata |
| `images` | copies of article images: the bytes, keyed by `sha256(url)` | an image file is two orders of magnitude larger than the article referencing it, and optional — see [images.md](images.md) |
| `enrichments` | output of processing steps, one document per run | see [enrichments.md](enrichments.md) §2 |

### 4.0a The server is MongoDB 4.4

Worth knowing before designing anything that touches an index: the deployment
target runs MongoDB 4.4, and it stays there. MongoDB 5.0 and later require AVX
and the machine does not have it, so this is a floor rather than a migration
waiting to happen. The Java driver still supports 4.4 — its minimum is being
raised *to* that version — so the stack is at the bottom of its supported range
rather than outside it.

The practical consequence is narrow but sharp: a partial index may only use
equality, `$exists: true`, the range operators, `$type` and `$and`, and no two
indexes of one collection may share a key pattern. Both are refused at index
*creation*, which `auto-index-creation` turns into a failure to start — and only
on a database that already exists, so a fresh local run never sees it. A newer
local MongoDB is more permissive about both, which is why
`MongoIndexCompatibilityTest` checks the declarations instead of trusting a
local boot.

### 4.1 Queues are state fields, not queries

Both asynchronous pipelines — body fetching and translation — are driven by a
status field on the article plus a partial index on the pending value:

```
content_queue_idx      { contentNextAttemptAt: 1 }   partial: contentStatus = PENDING
translation_lifo_idx   { translationStatus: 1, firstSeenAt: -1 }
                                                    partial: translationStatus = PENDING
```

The two queues are ordered differently on purpose. Body fetching takes the
oldest due article, translation takes the **newest** — see
[translation.md](translation.md) §5.2 for why a news archive that falls behind
should be current rather than complete. So the translation index sorts by
arrival rather than by due time, and the due predicate is filtered rather than
indexed: with a range on one field and a sort on another, one of the two is
served by the index and the other is not, and sorting is the expensive half to
get wrong.

The status leads the key even though the partial filter already pins it, and
that is not decoration: `{ firstSeenAt: -1 }` on its own is `seen_idx`, and
Mongo rejects a second index with the same key pattern under a different name
(error 85, `IndexOptionsConflict`) however much the options differ. It refuses
at creation time, which makes it a boot failure on every database that already
has `seen_idx` — so a partial index over an existing key pattern needs a key of
its own.

The older `translation_queue_idx { translationNextAttemptAt: 1 }` is no longer
created. An existing deployment still has it — `auto-index-creation` adds
indexes and never drops them — and it can be dropped by hand; nothing queries
it any more.

#### Renaming an index is part of changing it

That asymmetry — created but never dropped — has a consequence sharp enough to
be a rule: **if an index's key pattern changes, its name changes with it.**

Reusing the name is error 86, `IndexKeySpecsConflict`, on every database that
already holds the old definition, while a fresh schema starts perfectly. Both
of the guards that exist miss it by construction: `MongoIndexCompatibilityTest`
reads declarations and cannot know what a server holds, and a boot against an
empty database creates the new definition unopposed. It surfaces only when
starting against real data — which, on the way to a deployment, means it
surfaces in the deployment.

An old index left under its own name is the cheap failure by comparison: it
lingers, costs write throughput, and is dropped when somebody gets round to it.

The reference for "what should exist" is a database the current code just
created. Filling an empty one and diffing the two index sets by
`collection|name|keys` is how leftovers get found without guessing, and it is
worth doing before a deployment that touched an index annotation.

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
| Research contract (`/ode/search`) | text score | a search result sorted by date is not a search result. See [research-source.md](research-source.md) §2. |

Publishers backdate, forward-date and mis-timezone often enough that one
broken feed would dominate any sort built on `publishedAt` alone — which is
why the operator surface does not use it, and why it is sanity-checked and
nulled when implausible. The feed contract has no choice: see
[feed-source.md](feed-source.md) §4.

Indexes exist for both (`seen_idx` and friends, `published_idx` and friends).

## 7. Configuration

All runtime configuration is bound under two prefixes:

- `munin.*` — collection, extraction, translation, language, HTTP politeness,
  and whether each Vancetope-facing surface is served
  (`munin.centauri.enabled`, `munin.zarniwoop.enabled` — independent, because
  serving a timeline is not a reason to answer queries).
- `vance.ode.*` — the Vancetope binding. `vance.ode.base-url` and the event
  block are the outbound side; `vance.ode.centauri.*` and
  `vance.ode.zarniwoop.*` are the served ones. Everything outbound is inert
  until `base-url` is set.

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
  [content-extraction.md](content-extraction.md) §8.
- **A real `HttpServer`**, not a mock, for the Ode client tests. What is being
  tested there is a wire contract.
