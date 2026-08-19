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

## Data model

| Collection | Holds |
|---|---|
| `sources` | one feed each: URL, poll schedule, failure history, statistics |
| `source_lists` | directories that populate `sources` |
| `articles` | article metadata, deduplicated across sources |
| `article_contents` | extracted bodies, images and page metadata, separate because bodies are ~50× larger |

Body fetching is off by default (`munin.content.enabled`). Turning it on
works through whatever has accumulated, since ingest queues everything:

```bash
java -jar hrafnagud-server/target/hrafnagud.jar --munin.content.enabled=true
```

`POST /api/v1/articles/{id}/fetch-content` requeues one article with a fresh
retry budget; `POST .../skip-content` takes one out of the queue for good.

## Translation

Off by default. Adding target languages starts filling a backlog:

```yaml
munin:
  translation:
    targets: [de, en]
    translateSummary: true      # titles alone cost a tenth of the text
```

Munin owns the queue and the storage but not the engine — it only records
that a language is *owed*. Who produces it is a `TranslationProvider`, and
one ships: firing an event at a [Vancetope](https://github.com/mhus/vance)
brain through [vance-ode](https://github.com/mhus/vance-ode).

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

Configure targets with no provider wired and the backlog grows with
nothing draining it — a legible state, and the startup log says so
explicitly rather than leaving it to be discovered. `GET /api/v1/stats`
reports `translationBacklog` for the same reason.

## Design decisions

These are the choices that were not obvious, with the reasoning, because
each of them is somewhere a reasonable person would do it differently.

**A source's identity is its normalised URL — not its name, not its Mongo id.**
Two entries pointing at the same feed are the same source however they were
spelled. Without this, a publisher who starts appending a campaign parameter
to its own feed link gets imported a second time, along with a second copy
of its archive.

**Deduplication is a unique index, not an application check.** The dedup key
is a SHA-256 over the normalised article URL, and the index is unique, so two
workers racing on the same feed entry resolve at the database. A wire report
reaches us from every outlet that carries it; without this the archive would
be mostly duplicates and every query would return the same story a dozen
times.

**URL normalisation leans conservative.** Over-normalising merges distinct
articles and loses content, which is unrecoverable; under-normalising costs
disk. Tracking parameters, AMP markers, fragments, `www.`, default ports and
query-parameter order are folded. An `m.` host prefix and an `amp.`
subdomain are *not* — those are frequently distinct hosts with distinct
content. IRIs are converted to URIs (punycode plus percent-encoding) rather
than rejected, because `java.net.URI` refuses non-ASCII outright and a
worldwide collector cannot drop every internationalised domain.

**An article records every source that delivered it.** Once articles are
deduplicated across sources, "which feed did this come from" has more than
one answer, and keeping only the first would discard exactly the information
that makes the deduplication measurable.

**The ingest path does not write in the common case.** A feed re-serves its
whole window on every poll, so most ingest calls concern an article the same
source already delivered. Those resolve to an upsert that matches, adds
nothing and modifies nothing. There is deliberately no "still there"
timestamp — it would make the archive's largest write load a field with no
reader. `lastSourceAddedAt` is named for what it actually is: the last time
a feed that *did not already have* the article delivered it.

**Ordering uses `firstSeenAt`, never `publishedAt`.** Publishers backdate,
forward-date and mis-timezone their dates often enough that one broken feed
would dominate any sort built on them. `publishedAt` is kept as the
publisher's claim, sanity-checked and nulled when implausible.

**Language is stored with its provenance.** `SOURCE` (a human configured it)
beats `FEED` (the publisher declared it) beats `DETECTED` (we classified
it). A feed's `<language>` element is frequently absent or simply wrong, so
a consumer filtering on "German articles" needs to know whether it is
trusting a publisher or a classifier. Detection abstains rather than guesses
on short input — `UNKNOWN` is more useful than a confident wrong answer.

**Categories are stored verbatim, never normalised.** Publishers disagree
completely about what a category is; some emit sections, some emit tags,
some emit both in one field. Folding them into a taxonomy at ingest would
destroy information no later step could recover. A `topics` layer can be
built on top later.

**Poll intervals adapt per feed.** A fixed interval is wrong in both
directions at once: a wire service outruns it and entries are lost when its
feed window rolls over, while a regional weekly gets polled two thousand
times per published item. Delivering a lot halves the interval, delivering
nothing grows it gently, failing backs off geometrically. Adjustment is
asymmetric on purpose — reacting fast to a feed we are behind on, slowly to
a quiet weekend.

**A failing source is never auto-disabled.** Outages end, certificates get
renewed, DNS changes settle. Backoff is capped at a daily retry so a feed
that returns resumes by itself. A registry that quietly shrinks on every
transient problem is one nobody can trust.

**A source list is authoritative except where a human has spoken.** Every
field written through the API is recorded in `lockedFields` and becomes
off-limits to the list. Without it, disabling a feed that publishes garbage
lasts until the next refresh — and then nobody bothers correcting anything
again. Sources the list has dropped are *disabled*, not deleted: effective
without being destructive, so a briefly truncated upstream response cannot
delete half the registry.

**A `304 Not Modified` on a source list skips reconciliation entirely.** Not
an optimisation but a correctness requirement: reconciliation decides which
sources the list has dropped, and a document we did not read cannot support
that conclusion. Treating "unchanged" as "empty" would disable every source
the list owns.

**Full-text fetching is a separate queue, separate state machine, and off by
default.** It is an order of magnitude slower than a feed poll, fails in far
more ways, and is a qualitatively different act — reading a document
published for polling, versus fetching a page that was not. Each rejection
gets a status that says *why* (`BLOCKED`, `PAYWALL`, `FAILED`, or staying
`PENDING`), because the four call for four different responses and
collapsing them would keep retrying pages that can never succeed.

**Ingest queues every article, regardless of whether the fetcher runs.** The
feed run creates open jobs; working them is the fetcher's business. Letting
the producer consult the consumer's configuration would bake a runtime
decision into stored state — switch the fetcher on later and every article
collected until then would be stranded, unfetchable, with no way back short
of a bulk rewrite. The cost is honest and small: with the fetcher off the
`PENDING` index covers the whole archive rather than just the backlog.

**Extraction asks before it guesses.** Four rungs, and which one produced a
given body is recorded in `article_contents.extractor`:

1. `json-ld` — the publisher's own `schema.org` metadata. Not a heuristic:
   an answer. Nearly every news site emits it because Google News requires
   it, so coverage is far better than the effort suggests.
2. `semantic` — a container the page marks as its article body.
3. `scored` — most paragraph text relative to link density.
4. `body` — last resort.

Metadata merges across rungs independently of the body, because JSON-LD
frequently carries no `articleBody` while its headline, image, date,
language and author still beat anything derived from the DOM. A JSON-LD
body shorter than fifty words is treated as a description in the wrong
field and the DOM rungs are used instead.

Recording the rung is what makes extraction quality *measurable*: the
failure mode here is silent — a navigation rail stored as article text
throws nothing and is discovered months later. Aggregating on `extractor`
shows which publishers fall through to the guessing rungs, and those are
the ones worth looking at.

**Images are collected as URLs with captions, never as bytes.** A reference
is cheap and uncontroversial; storing publishers' image files is a storage
question and a copyright question at once, and a URL leaves that decision
open where the reverse would not. The lead image comes from the
declaration; inline images come from inside the chosen container, because
position is a far better discriminator than anything about the image
itself. Captions are a field of the image rather than part of the body — in
news writing the caption is often the most informative sentence about the
photograph, and burying it in the prose loses that.

Two traps, both found by measuring against real pages rather than by
reasoning: on many sites `src` holds a transparent placeholder and the real
image is in `srcset` or `data-src`, so reading `src` first fills the archive
with references to the same blank GIF; and an image with no caption, no alt
text and no declared geometry is page furniture — a newspaper's own
front-page thumbnail advertising a subscription is the archetype — because
nothing about it was meant to be read.

**Word counts are script-aware.** Counting whitespace-separated tokens is
the obvious implementation and is wrong for a large part of the world:
Japanese, Chinese, Korean and Thai do not separate words with spaces, so a
complete article scores a handful of "words". Every length threshold built
on that then misfires in the same direction, and the archive quietly ends up
holding no CJK bodies at all. This was caught by the fixture corpus on its
first run, which is precisely what the corpus is for.

**Politeness is centralised, not conventional.** One HTTP client, one user
agent, one per-host rate limiter, one body cap. A directory import easily
puts fifty feeds of one publisher into the registry; without per-host pacing
they all get polled in the same second and the publisher responds the way
any operator would. `robots.txt` is obeyed for article pages — and
deliberately not consulted for feeds, which are published expressly to be
polled.

**Extraction scores containers by link density.** Navigation and
related-story rails are text-heavy too, but nearly all of their text sits
inside anchors while article prose sits outside them. That one ratio
separates the two more reliably than any word count.

**Translations are a map keyed by language, not `titleDe`/`titleEn`
fields.** The third target language must not be a schema change.

**The translation queue is a field, not a query.** Which languages an
article still owes is stored on it rather than derived from what the
translations map is missing, because the set of targets is configuration:
a derived query would need rebuilding — and re-indexing — every time an
operator adds a language.

**A translation is one article and one language.** An article owing two
languages is two units of work, so a provider failing on the second does
not cost the first, and the storage write is per-language rather than a
whole-map replace. A failing teaser likewise does not sink the title: a
translated headline with an untranslated teaser is a usable entry, losing
both to one call is not.

**Permanent failures spend the whole retry budget at once.** Retrying a
rejected token four more times produces four more rejections. The provider
says whether its failure is worth repeating; the queue does not
second-guess it.

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

## Licence

GPLv3 — see `LICENSE`.
