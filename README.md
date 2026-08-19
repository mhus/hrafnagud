# Hrafnagud

A news collector. Pulls articles from many sources worldwide, deduplicates
them across those sources, classifies their language, optionally fetches the
full article text, and stores the result in MongoDB.

Named after the raven-god. **Munin** (memory) is the part that collects and
stores; a future **Hugin** (thought) will be the part that queries and
analyses. Today only Munin exists.

> **Status:** backend only, REST, no authentication. Bind it to localhost or
> put it behind a reverse proxy — see [Known gaps](#known-gaps).

## Modules

```
server/
  hrafnagud-api/      DTOs and enums crossing the REST boundary. No Spring, no MongoDB.
  hrafnagud-munin/    Source registry, feed ingest, deduplication, full-text fetch, persistence.
  hrafnagud-translate/ Works Munin's translation backlog. Separate module so Munin keeps
                       no dependency on Vancetope.
  hrafnagud-centauri/ Serves the archive to Vancetope as a Centauri feed source. Outside
                       Munin for the same reason — the mirror image of translate: that one
                       calls a brain, this one answers one.
  hrafnagud-zarniwoop/ Answers Vancetope research queries out of the archive. Same data as
                       centauri, opposite question: a ranked answer, not a timeline.
  hrafnagud-server/   Boot module: entrypoint plus runtime configuration, nothing else.
```

`hrafnagud-server` is deliberately thin so that a second feature module
(Hugin) can be added beside Munin without either of them owning the
application.

**Stack:** Java 25, Spring Boot 4.1, Maven multi-module, Lombok, JSpecify,
Spring Data MongoDB, Rome (feed parsing), jsoup (HTML), Lingua (language
detection), JUnit 5 + Mockito + AssertJ.

## Running

Needs a MongoDB. The defaults expect one on `localhost:27017`:

```bash
cd server && mvn install
java -jar hrafnagud-server/target/hrafnagud.jar
```

Listens on `:9800`. Override with `HRAFNAGUD_PORT`, `HRAFNAGUD_MONGO_URI`,
`HRAFNAGUD_MONGO_DB`.

To route all outbound traffic through a proxy — feeds, source lists,
article pages and `robots.txt` alike, since they all leave through the same
client:

```yaml
munin:
  http:
    proxy:
      host: 10.42.10.24
      port: 8888
```

or `HRAFNAGUD_PROXY_HOST` / `HRAFNAGUD_PROXY_PORT`. Leaving the host empty
connects directly, which is the default. A host set without a valid port
fails at startup rather than quietly going direct — in an environment that
requires the proxy that would break every fetch, and the reason would be
nowhere near the mistake.

Add a curated directory of feeds and collect from it:

```bash
# awesome-rss-feeds (CC0) ships one OPML per country and per topic
curl -X POST localhost:9800/api/v1/source-lists -H 'Content-Type: application/json' -d '{
  "url": "https://raw.githubusercontent.com/plenaryapp/awesome-rss-feeds/master/countries/with_category/Germany.opml",
  "name": "awesome-germany",
  "type": "OPML",
  "defaultCountry": "DE"
}'

curl -X POST localhost:9800/api/v1/source-lists/awesome-germany/refresh
curl localhost:9800/api/v1/stats
curl 'localhost:9800/api/v1/articles?language=de&size=5'
```

### In a container

`deploy/` holds everything needed to run this as an image: a Dockerfile,
build and push scripts, a compose stack with MongoDB, and Kubernetes
manifests (base plus an overlay for a small k3s node).

```bash
deploy/docker/build-image.sh                    # image, Maven build included
cd deploy/docker && docker compose up -d        # + MongoDB, on localhost:9800
deploy/k8s/apply.sh                             # current kubectl context
```

Details, configuration surface and the reasoning behind the layout:
[deploy/README.md](deploy/README.md).

## Data model

| Collection | Holds |
|---|---|
| `sources` | one feed each: URL, poll schedule, failure history, statistics |
| `source_lists` | directories that populate `sources` |
| `articles` | article metadata, deduplicated across sources |
| `article_contents` | extracted bodies, images and page metadata, separate because bodies are ~50× larger |
| `enrichments` | results of processing steps over an article — one document per run, append-only |

Body fetching is off by default (`munin.content.enabled`). Turning it on
works through whatever has accumulated, since ingest queues everything:

```bash
java -jar hrafnagud-server/target/hrafnagud.jar --munin.content.enabled=true
```

`POST /api/v1/articles/{id}/fetch-content` requeues one article with a fresh
retry budget; `POST .../skip-content` takes one out of the queue for good.

## Translation

Off by default. Setting a **pivot language** starts filling a backlog:

```yaml
munin:
  translation:
    pivotLanguage: de
    translateSummary: true      # titles alone cost a tenth of the text
```

One pivot language, not a list of targets. Everything downstream — search,
rating, clustering — reads one language, and an article already in it is
marked `SKIPPED` at ingest rather than queued. Translating into a second
language is a presentation concern and belongs wherever it is displayed,
not in the archive.

Title and teaser go out in **one** call. At realistic volume the recipe
prompt dominates the token bill — roughly five times the length of the
text itself — so a second call would double the expensive half to save
nothing.

Munin owns the queue but neither the engine nor the result. Who produces
the translation is a `TranslationProvider`, and one ships: firing an event
at a [Vancetope](https://github.com/mhus/vance) brain through
[vance-ode](https://github.com/mhus/vance-ode).

```yaml
vance:
  ode:
    base-url: https://brain.example.com
    tenant: acme
    project: news
    events:
      translate-article:
        token: ${VANCE_TRANSLATE_TOKEN}
```

The brain side is the `translation` kit: the event, the script and the
recipe live there as documents, so the prompt and the model are editable
without redeploying this service. That is the reason to integrate through
an event rather than to call a model directly from here.

Set a pivot language with no provider wired and the backlog grows with
nothing draining it — a legible state, and the startup log says so
explicitly rather than leaving it to be discovered. `GET /api/v1/stats`
reports `translationBacklog` for the same reason.

The translation itself lands in `enrichments`, not on the article.
`GET /api/v1/articles?withTranslation=true` folds the newest one into each
article; `GET /api/v1/articles/{id}/enrichments` lists all of them, and
`POST /api/v1/articles/{id}/translate` requeues one article.

## Serving the archive to Vancetope

Off by default. The archive collects whether anybody reads it or not; handing
it out is a separate decision, and the endpoint is unauthenticated until a key
is set.

```yaml
munin:
  centauri:
    enabled: true
vance:
  ode:
    centauri:
      apiKey: ${HRAFNAGUD_CENTAURI_API_KEY:}   # empty = no check
```

or `HRAFNAGUD_CENTAURI_ENABLED=true`. The startup log states which of the two
auth situations you are in. The REST contract is served by
[vance-ode](https://github.com/mhus/vance-ode) — this service only supplies a
`FeedSource` bean:

```bash
curl -H 'Authorization: Bearer <key>' localhost:9800/ode/feed/capabilities
curl -H 'Authorization: Bearer <key>' localhost:9800/ode/feed/selectors
curl -H 'Authorization: Bearer <key>' \
  'localhost:9800/ode/feed/items?selector=source:bbc-world&limit=20'
```

Streams are `all` or `source:<name>`. A translated article is served **in the
pivot language** — that is what the pivot is for — with the original kept in
`extras.originalTitle` / `originalLanguage` alongside the model that produced
it. An article still waiting for translation is served in its own language
rather than withheld.

Two properties of this that are worth knowing before they surprise you:

- **Ordering is by `publishedAt`**, not by when the archive collected the
  article, because that is the key a reader merges several sources on.
  Consequence: an article published before a reader's cursor but collected
  after it sits behind that cursor, so a pull-forward misses it. Scrolling
  backwards finds it.
- **Text search matches the original**, since the text index covers the
  article's own title and teaser. A translated entry is findable by its
  English words and not by the German ones on screen. Making translations
  searchable means indexing the enrichment.

## Research queries from Vancetope

Also off by default, and switched independently of the feed:

```yaml
munin:
  zarniwoop:
    enabled: true
vance:
  ode:
    zarniwoop:
      apiKey: ${HRAFNAGUD_ZARNIWOOP_API_KEY:}
```

```bash
curl -H 'Authorization: Bearer <key>' localhost:9800/ode/search/capabilities
curl -X POST -H 'Authorization: Bearer <key>' -H 'Content-Type: application/json' \
  -d '{"query":"tariffs","modality":"NEWS","maxResults":5}' \
  localhost:9800/ode/search/search
```

Ranked by relevance rather than by date, over title and teaser **in both the
article's own language and the pivot translation** — so a German query finds an
English article that was translated into German. `EXPERT` tier accepts
`source`, `language`, `category`, `since`, `until`. Bodies are offered on
demand rather than shipped with the result list.

## Design decisions

The choices that were not obvious, with the reasoning, live in
[`specs/`](specs/) — one document per topic, because there are enough of them
that a single list stopped being readable:

| Document | Covers |
|---|---|
| [architecture](specs/architecture.md) | Modules, the rule that keeps Munin free of Vancetope, collections, why two timestamps |
| [collection](specs/collection.md) | Source identity, deduplication, URL normalisation, adaptive polling, source lists, language provenance |
| [content-extraction](specs/content-extraction.md) | The four-rung ladder, images, script-aware word counts, the fixture corpus |
| [enrichments](specs/enrichments.md) | Why a processing result is a document and not a field |
| [translation](specs/translation.md) | The pivot language, the provider SPI, the Vancetope event, the nested timeouts |
| [feed-source](specs/feed-source.md) | Serving the archive: streams, cursor, declared capabilities and the two declined ones |
| [research-source](specs/research-source.md) | Relevance search, searching translation and original, the two contract rules |

A few that shape everything else, in short:

**A source's identity is its normalised URL** — not its name, not its Mongo id.
**Deduplication is a unique index**, not an application check, so two workers
racing on the same feed entry resolve at the database. **Normalisation leans
conservative**, because over-normalising loses content unrecoverably while
under-normalising costs disk. **Ordering uses `firstSeenAt`** on the operator
API and `publishedAt` only where a contract demands it, because publishers
backdate and mis-timezone often enough that one broken feed would dominate any
sort built on their dates. **Queues are state fields with partial indexes**, so
an index tracks the backlog rather than the archive. **Processing results go in
`enrichments`**, never onto the article, so re-running a step does not destroy
the comparison that made re-running worth doing.

## Known gaps

Named rather than left to be discovered:

- **No authentication or authorisation.** Every endpoint is open. Do not
  expose this to a network you do not control.
- **No retention policy.** At ten thousand articles a day the archive grows
  quickly and nothing prunes it yet. A TTL or archival tier is needed before
  this runs for months.
- **Near-duplicate clustering is not implemented.** `contentHash` is stored
  and indexed so the same story republished at a different URL can be found,
  but nothing groups them yet. Exact-URL dedup is what works today. The same
  applies to `article_contents.canonicalUrl`: the page's own claim about its
  identity is recorded but not acted on, because merging two articles means
  moving a row under a unique index and deserves its own deliberate change.
- **Metadata found on the page does not flow back to the article.** A page's
  `datePublished`, author and language are frequently better than the feed's,
  but they are stored on the content document rather than overwriting the
  article's own fields — otherwise ordering and language provenance would
  depend on when a body happened to be fetched.
- **The `body` extraction rung is the weak tail.** Measured against a few
  hundred real German and English articles it accounts for about one in
  eighty and averages far less text than the rungs above it. Those pages are
  the queue for improving extraction; the way to work it is to reduce one to
  its structural skeleton and add it to the fixture corpus.
- **No backlog visibility.** If the registry outgrows the poll capacity
  (batch size × tick rate), `nextFetchAt` simply drifts into the past and
  feeds are polled less often than configured, with nothing reporting it.
- **Single instance assumed.** Claims are leases in MongoDB, so a second
  instance would not corrupt anything, but this has not been tested.
- **Storing full article text has copyright implications.** Fine for private
  research; if this output is ever published, quote-plus-link is the
  defensible form.
- **One source type.** `SourceType` and the reader SPI exist so a scraper or
  API client is a new bean, but only `RSS` (covering Atom) is implemented.
- **Articles without a `publishedAt` never appear in the feed.** They are
  collected and queryable through `/api/v1/articles`, but a chronological
  stream has no defensible position for them, and deriving one from
  `firstSeenAt` would place a week-old article at today's date. Nothing
  reports how many are affected.
- **Translations are not searchable.** See the note above — the text index
  covers the article, not its enrichments.
- **The feed accepts no signals.** `signalsAccepted` is empty, so a reader
  hides those controls rather than offering one that would be dropped here.
  A report has nowhere to go in this archive yet.

## Licence

GPLv3 — see `LICENSE`.
