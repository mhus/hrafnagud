# Hrafnagud

A news collector. Pulls articles from many sources worldwide, deduplicates
them across those sources, classifies their language, optionally fetches the
full article text, and stores the result in MongoDB.

Named after the raven-god. **Munin** (memory) is the part that collects and
stores; a future **Hugin** (thought) will be the part that queries and
analyses. Today only Munin exists.

> **Status:** backend plus a small read-only console. One shared API token,
> no accounts — bind it to localhost or put it behind a reverse proxy, see
> [Known gaps](#known-gaps).

## Layout

One Maven artifact, built from the repository root. The packages carry the structure:

```
de.mhus.hrafnagud.
  api/        DTOs and enums crossing the REST boundary. No Spring, no MongoDB.
  munin/      MEMORY: source registry, feed ingest, deduplication, full-text
              fetch, persistence, the operator API. No brain anywhere near it.
  hugin/      THOUGHT: everything that hands text to a model.
    translate/  works Munin's translation backlog through a Vancetope brain
    classify/   decides what a publisher's category means
  centauri/   Serves the archive to Vancetope as a Centauri feed source — the mirror
              image of hugin: that half calls a brain, this one answers one.
  zarniwoop/  Answers Vancetope research queries out of the archive. Same data as
              centauri, opposite question: a ranked answer, not a timeline.
  facet/      What both of those let a reader filter by, declared once.
  config/     The property roots: munin.*, hugin.*, hrafnagud.*
  settings/   The values in force — config plus whatever an operator overrode.
  server/     Entrypoint plus runtime wiring, nothing else.
```

Beside `src/` sits [`kits/`](kits/) — the brain side of what `hugin/` calls,
as Vancetope kits installable straight from this repository.

**The two ravens are the layering.** Munin remembers: it collects, deduplicates
and stores, and it does that without a brain anywhere near it. Hugin thinks:
translation today, rating or clustering later. Hugin imports Munin, never the
other way round — **deleting all of `hugin/` has to leave a collector that still
compiles.** That was six Maven modules and therefore compiler-checked until the
service turned out not to need the ceremony; see
[architecture.md](specs/architecture.md) §2 for the rule and how it is checked
now.

The same split runs through the configuration: `munin.*` for collecting and
storing, `hugin.*` for what spends model time, `hrafnagud.*` for what belongs to
neither ([settings.md](specs/settings.md) §1).

**Stack:** Java 25, Spring Boot 4.1, Maven (single module), Lombok, JSpecify,
Spring Data MongoDB, Rome (feed parsing), jsoup (HTML), Lingua (language
detection), JUnit 5 + Mockito + AssertJ.

## Running

Needs a MongoDB. The defaults expect one on `localhost:27017`:

```bash
mvn install
java -jar target/hrafnagud.jar
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

A fresh database gets **two catalogues** from `awesome-rss-feeds` (CC0) — its
news half and its blog half, each with its own poll interval class — both
**switched off**. Turn one on at `http://localhost:9800/#catalogs` and the
three layers pull each other along without anyone pressing anything again:

```
catalogue ──daily──▶ source lists ──daily──▶ sources ──adaptive──▶ articles
```

Once enabled, the catalogue is read within the next quarter hour (or
immediately, via *Jetzt lesen*); the lists it delivers are then drained by the
list tick, which runs every five minutes and works through the whole backlog in
one round. It also means roughly 1,700 outbound requests an hour once the
registry is full — which is why nothing starts on its own. Enable only the news
half to start smaller, or skip the bundled catalogues entirely:

```yaml
munin:
  catalog:
    installBundled: false              # bring your own catalogue
```

Catalogues are readers per *publication shape*, not per publisher:
`opml-directory` for anything following the OPML 2.0 directory spec,
`github-opml` for a repository of loose OPML files. Adding a collection is a
`POST`, not a release — see [catalogs.md](specs/catalogs.md).

Add a single curated directory of feeds by hand instead:

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

## Console

`http://localhost:9800/` opens a small operator console, one view per
subsystem: an overview that says whether collection is alive, the source
registry with its failures, the articles with what was extracted from them, the
category mappings, the filter rules, the catalogues, and the settings.

```yaml
munin:
  api:
    token: ${HRAFNAGUD_API_TOKEN:}        # empty = /api/v1/** is unguarded
    consoleEnabled: true
```

The token is typed into the console and kept in the browser — in the tab by
default, on the device only if you tick the box. The page itself is never
guarded: it holds no data and no credential, and asking for a token in order
to reach the page that asks for a token is a loop, not a security measure.

**Mostly read-only, and the exceptions are chosen rather than accumulated.**
What the console can change is what an operator has to change while looking at
the data, and what a mis-click cannot destroy: switch a catalogue on or off and
re-read it, settle a category mapping by hand, write and re-apply filter rules,
turn an operational value up or down.
No article deletion, no source editing, no re-queueing a body — those exist in
the API and are one `curl` away. Putting an operation behind a button is a
different decision from showing what is going on, and it is made one operation
at a time.

Bootstrap comes from a CDN, so the console needs internet access even where
hrafnagud does not. That is the one trade in it: ~60 KB of JAR against a
dependency on `cdn.jsdelivr.net` being reachable from the browser.

## Settings

**Operational values live in the database and can be changed while the service
runs** — the worker switches, the batch sizes, the retry budgets, the interval
bounds, the thresholds. `application.yml` and the `HRAFNAGUD_*` environment stay
the layer underneath: a value with no stored override comes from there, and
deleting an override is the way back to it.

```bash
curl localhost:9800/api/v1/settings                     # value, default, and which is in force
curl -X PUT localhost:9800/api/v1/settings/munin.content.enabled \
     -H 'Content-Type: application/json' -d '{"value":"true"}'
curl -X DELETE localhost:9800/api/v1/settings/munin.content.enabled
```

The keys are the property names unchanged, so `munin.feed.batchSize` in the YAML
is the default for the setting of that name — and the root says which half owns
it: `munin.*` collects and stores, `hugin.*` hands text to a model. Values are
checked against the declared type before they are stored, and a key this build
does not declare is a 404 rather than a row nothing reads. The console edits the
same list under **Einstellungen**.

Switches and counts take effect at the start of the next round; the interval
bounds apply the next time a source is rescheduled, so poll times already
written stay as they are. **Start-up values are deliberately not settings** —
tick cadences, the proxy, the console switch, the Vancetope endpoint switches,
`installBundled` — because nothing would read them after boot. Nor are secrets:
the API token and the Ode keys stay in the environment. The reasoning for both:
[settings.md](specs/settings.md).

## Data model

| Collection | Holds |
|---|---|
| `source_catalogs` | directories of source lists, re-read daily — this is what makes the registry grow by itself |
| `sources` | one feed each: URL, poll schedule, failure history, statistics |
| `source_lists` | directories that populate `sources` |
| `articles` | article metadata, deduplicated across sources |
| `article_contents` | extracted bodies, images and page metadata, separate because bodies are ~50× larger |
| `enrichments` | results of processing steps over an article — one document per run, append-only |
| `category_mappings` | what each publisher category was decided to mean, once for the whole archive |
| `filter_rules` | accept/deny rules deciding which articles are worth fetching and translating |
| `settings` | operational values an operator has changed — one document per override, absent means "as configured" |

Every article carries the place path of the publisher it first arrived through
(`originPlaceIds`, world → region → sub-region → country from UN M.49 and ISO
3166-1), so `?originPlace=m49:142` finds everything from an Asian publisher and
`?originPlace=iso:SG` only Singaporean ones. That is **origin, not subject** —
what an article is *about* is a different field that does not exist yet, and
conflating the two is the mistake [geo.md](specs/geo.md) exists to prevent.

Body fetching is off by default (`munin.content.enabled`). Turning it on
works through whatever has accumulated, since ingest queues everything — at
start-up, or while it runs:

```bash
java -jar target/hrafnagud.jar --munin.content.enabled=true

curl -X PUT localhost:9800/api/v1/settings/munin.content.enabled \
     -H 'Content-Type: application/json' -d '{"value":"true"}'
```

`POST /api/v1/articles/{id}/fetch-content` requeues one article with a fresh
retry budget; `POST .../skip-content` takes one out of the queue for good.

## Filter rules

Fetching a body costs a request and translating costs tokens. Which articles are
worth either is decided by accept/deny rules in the database, written in the
console under **Filter**:

```bash
curl -X POST localhost:9800/api/v1/filter/rules -H 'Content-Type: application/json' -d '{
  "pipeline": "TRANSLATION", "decision": "DENY",
  "type": "HOST", "matchType": "SUFFIX", "value": "youtube.com" }'
```

Evaluation is **accept, then deny, then accept by default** — so an accept rule
is an exception to a deny rule, and with no rules at all everything is accepted.
Rules are a set, not a list: no ordering, no priority, only "does any rule
match". A rule can read the URL or its host, the source, the language, the
publisher's region, the publisher's own category, the normalised topic, or the
source's fetch profile; regions and topics match through the materialised
ancestor path, so `m49:142` covers a Singaporean source and `medtop:15000000`
covers an article tagged *Cricket*.

The decision lands on the article together with the rule that made it, which is
what makes "why is this not translated" answerable. New articles are decided at
ingest; stored ones keep their decision until re-evaluated:

```bash
curl -X POST 'localhost:9800/api/v1/filter/reevaluate?days=10'
```

That is also the way back for anything skipped while `pivotLanguage` was unset.
A queue only moves when the decision actually flips, so a finished translation
and a body skipped by hand both survive a run. Details and the reasoning:
[filter.md](specs/filter.md).

**The rules decide what is paid for, not what is served.** Everything collected
stays readable through the API, the feed and the research source — a denied
article still has a title, a place and a URL, and a spending decision made today
must not silently rewrite what a reader can see. Narrowing is the reader's
request, as an `accepted` facet on both Vancetope-facing surfaces:

```bash
curl 'localhost:9800/ode/feed/items?selector=all&facet=accepted:yes'   # in scope
curl 'localhost:9800/ode/feed/items?selector=all&facet=accepted:no'    # the discards
```

The `no` side is the useful half: it shows what the rules are throwing away,
which a list of rules cannot.

## Translation

Off by default, and it takes two switches — `enabled` runs the worker,
`pivotLanguage` decides what gets queued:

```yaml
munin:
  translation:
    enabled: true               # the worker; off by default
    pivotLanguage: de           # the one target, decided at ingest
    readableLanguages: en       # languages that need no translation at all
    translateSummary: true      # titles alone cost a tenth of the text
```

Separate on purpose: pausing translation by clearing the pivot language would
mark every article ingested meanwhile as `SKIPPED`, silently and for good.
With `enabled: false` the queue keeps filling and resumes when you switch the
worker back on — the startup log says which of the four states you are in, and
the log says so again whenever the switch changes.

Both are [settings](#settings): they can be changed while the service runs, and
the worker picks the change up on its next round. A new pivot language applies
to articles arriving from then on — the ones already stored keep their decision
until the filter is re-evaluated.

One pivot language, not a list of targets. Everything downstream — search,
rating, clustering — reads one language. Translating into a *second* language is
a presentation concern and belongs wherever it is displayed, not in the archive.

**One target does not mean one exempt language.** `readableLanguages` lists the
languages that need no translation at all — with `pivotLanguage: de` and
`readableLanguages: en`, both German and English articles are marked `SKIPPED`
at ingest and only the rest is queued. That is the difference between paying for
the half of the archive you could already read and not paying for it. The pivot
is always exempt and need not be repeated; an article whose language is
*unknown* is queued anyway, because the cost of being wrong that way is one call
that returns the text unchanged.

Both are read at ingest, so a change applies to articles arriving from then on.
For the ones already stored, `POST /api/v1/filter/reevaluate` asks the same
function and moves them out of the queue — or back in.

Title and teaser go out in **one** call. At realistic volume the recipe
prompt dominates the token bill — roughly five times the length of the
text itself — so a second call would double the expensive half to save
nothing.

Munin owns the queue but neither the engine nor the result. Who produces the
translation is a `TranslationProvider`, and **two** ship:

| `hugin.translation.provider` | What it does |
|---|---|
| `vance-ode` | fires an event at a [Vancetope](https://github.com/mhus/vance) brain through [vance-ode](https://github.com/mhus/vance-ode); the brain owns the prompt |
| `gemini` | calls Google's Gemini API directly through langchain4j; this service owns the prompt |

Both can be wired at once, and the setting is resolved per article — so the
same articles can go through both and be compared afterwards, because every
translation records the `producer` and the `model` that answered. With both
wired and the setting empty, nothing translates: picking one of two ways to
spend money by accident is worse than saying so.

```yaml
hugin:
  gemini:
    apiKey: ${GEMINI_API_KEY:}        # empty = that provider is not wired
    model: gemini-3.5-flash-lite      # a setting: it is what an experiment turns
```

The brain path:

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

The brain side is the **`translation` kit in this repository**
([kits/translation](kits/translation)): the event, the script and the recipe
live there as documents, so the prompt and the model are editable without
redeploying this service. That is the reason to integrate through an event
rather than to call a model directly from here — and the reason the kit ships
here rather than in the kit collection next door is that the payload is one
contract with two halves, so both change in the same commit.

```bash
# in the brain, for the project this service is configured against
kit install https://github.com/mhus/hrafnagud.git --sub-path kits/translation
```

The event's token and `vance.ode.events.translate-article.token` have to agree;
[kits/README.md](kits/README.md) lists the three values that span the boundary.

Set a pivot language with no provider wired and the backlog grows with
nothing draining it — a legible state, and the startup log says so
explicitly rather than leaving it to be discovered. `GET /api/v1/stats`
reports `translationBacklog` for the same reason.

**The queue is worked newest first**, so an archive that falls behind stays
current rather than complete — and `hugin.translation.maxAge` (a week) takes out
what the queue would therefore never reach, as `SKIPPED` with the reason on the
article. Without the cutoff the backlog would grow for ever
([translation.md](specs/translation.md) §5.2).

**A rate limit is not a failure.** A `429` puts the article back in the queue
with its attempt returned and pauses the whole worker for
`hugin.translation.throttleCooldown` — on a free tier being throttled is the
normal state, and charging articles for it would mark the backlog `FAILED`
without anything having been translated. Both edges of the pause are in the log
([translation.md](specs/translation.md) §5.1).

Each run is recorded in `enrichments`, not on the article — so re-running with
a better model adds a row instead of overwriting the comparison. The article
carries a searchable copy of the newest one, which is a derived read model
rather than a second record (see [enrichments](specs/enrichments.md) §2.1).
`GET /api/v1/articles?withTranslation=true` folds the newest one into each
article; `GET /api/v1/articles/{id}/enrichments` lists all of them, and
`POST /api/v1/articles/{id}/translate` requeues one article.

## Serving the archive to Vancetope

On by default — serving a reader is what these packages are for, and the
optionality belongs to the library (`vance-ode-centauri` cannot know whether
its host wants to serve), not here. Switch it off with `enabled: false` for a
deployment that only collects. Note that the endpoint is unauthenticated until
a key is set, exactly like the operator API above.

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
English article that was translated into German — and over the **fetched
article text** as a second tier below those. `EXPERT` tier accepts
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
| [settings](specs/settings.md) | Operational values in the database, what stays a start-up property, and when a change takes effect |
| [translation](specs/translation.md) | The pivot language, the two providers and why the choice is a setting, the Vancetope event, the nested timeouts |
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
`enrichments`** rather than into a field on the article, so re-running a step
does not destroy the comparison that made re-running worth doing.

## Known gaps

Named rather than left to be discovered:

- **One token, no accounts.** `munin.api.token` guards `/api/v1/**` and
  everyone who has it can do everything, including delete. That is honest for
  a service whose user base is the person running it, and not enough for
  anything with several operators. Empty — the default — means no check at
  all: right on a loopback binding, wrong on a reachable port.
- **Settings have no history.** A changed value carries the time it was
  written and nothing else — not the previous value and not who wrote it. The
  log line for the write is the only trace, and it is not queryable. Nor is
  anything validated beyond its type: `minInterval` above `maxInterval` is
  accepted and behaves as you would expect.
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

Third-party data ships with the service; the notices are in `NOTICE` and, so
that they also reach somebody who only ever runs the container, at
`/actuator/info`.

**IPTC Media Topics** — the vocabulary article categories are normalised
against ([categories.md](specs/categories.md)). Licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); *"you can use them
for free in any way, but we request that you give IPTC credit"*. Modified:
projected to id, parent, name and normalised labels, other metadata dropped.
Regenerate with `scripts/generate-mediatopics-tsv.py`.

**Place hierarchy** — UN M49 containment joined to ISO 3166-1 alpha-2
([geo.md](specs/geo.md)). Factual codes from those standards, not a copy of a
compilation.
