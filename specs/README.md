# Hrafnagud — Specifications

Reference documentation for what is built, and why it is built that way.

The repository `README.md` is the entry point: what this is, how to run it,
what the endpoints are. These documents are the layer under it — the model,
the mechanisms, and the decisions with their reasoning.

Written in English, like the code and the README. Every document has the same
shape: what the thing is for, the model it operates on, how it works, the
decisions that were not obvious, and where it stops.

| Document | Covers |
|---|---|
| [architecture.md](architecture.md) | Modules, boundaries, collections, the rules that keep Munin free of Vancetope |
| [catalogs.md](catalogs.md) | Where source lists come from, the OPML directory standard, and the readers for publishers who ignore it |
| [collection.md](collection.md) | Sources, source lists, feed ingest, deduplication, adaptive polling, language |
| [categories.md](categories.md) | Normalising publishers' section names against IPTC Media Topics — a mapping table that learns, and what it must not throw away (design) |
| [content-extraction.md](content-extraction.md) | Fetching article pages and separating content from furniture |
| [filter.md](filter.md) | Deciding which articles are worth fetching and translating — accept/deny rules, and why the category rule needs a second pass (design) |
| [images.md](images.md) | Keeping copies of article images: why it is optional and per-image, the URL-derived address, what it costs |
| [enrichments.md](enrichments.md) | Where the record of a processing step goes, and why it is not a field on the article |
| [geo.md](geo.md) | Three kinds of location, the containment hierarchy, and why a source's country is not an article's subject (design) |
| [translation.md](translation.md) | The pivot language, the provider SPI, and the Vancetope event behind it |
| [settings.md](settings.md) | Operational values in the database: the two layers, what stays a start-up property, and when a change takes effect |
| [console.md](console.md) | The operator console, the API token in front of it, and what it deliberately cannot do |
| [feed-source.md](feed-source.md) | Serving the archive to Vancetope as a Centauri feed source |
| [research-source.md](research-source.md) | Answering Vancetope research queries as a Zarniwoop search source |

## What these documents are not

They are not a changelog and not a task list. A decision that was reversed is
recorded only if the reversal is itself worth knowing about — otherwise the
current state is the whole story.

They also do not restate the code. Where a document says *how* something
works it is because the shape is not obvious from reading one class; where it
says *why*, it is because a reasonable person would have done it differently
and the alternative deserves an answer.

## Naming

Odin's two ravens, and here they are the layering rather than a decoration.

**Munin** (memory) collects and stores: the source registry, ingest,
deduplication, the full-text fetch, persistence, the operator API. It works
without a brain anywhere near it, and that is checked rather than hoped for.

**Hugin** (thought) interprets what Munin holds — anything that hands text to a
model. Today that is translation and deciding what a publisher's category
means; rating, clustering and summarising would be neighbours there rather than
anything inside Munin. Hugin imports Munin, never the other way round.

The line between them is also the line between two budgets: Munin spends
requests against publishers, Hugin spends model time somebody pays for, which
is why Hugin's workers are off until they are switched on. The property roots
follow the same split — `munin.*`, `hugin.*`, and `hrafnagud.*` for what belongs
to neither.

Everything facing [Vancetope](https://github.com/mhus/vance) as a *server* —
`centauri`, `zarniwoop`, `facet` — lives outside both, in its own package. See
[architecture.md](architecture.md) §2.
