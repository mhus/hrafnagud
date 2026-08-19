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
| [collection.md](collection.md) | Sources, source lists, feed ingest, deduplication, adaptive polling, language |
| [content-extraction.md](content-extraction.md) | Fetching article pages and separating content from furniture |
| [enrichments.md](enrichments.md) | Where the record of a processing step goes, and why it is not a field on the article |
| [translation.md](translation.md) | The pivot language, the provider SPI, and the Vancetope event behind it |
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

**Munin** (memory) collects and stores. A future **Hugin** (thought) would
query and analyse; it does not exist yet, and `hrafnagud-server` is
deliberately thin so it could be added beside Munin rather than inside it.

Everything facing [Vancetope](https://github.com/mhus/vance) lives outside
Munin, in its own module — see [architecture.md](architecture.md) §2.
