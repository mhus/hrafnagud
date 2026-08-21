---
name: hrafnagud-archive
description: >-
  Read the hrafnagud news archive — a continuously collected, deduplicated
  corpus of articles from several hundred publishers worldwide. Use when a
  question is about what was reported, when, or by whom.
---

# The news archive

A collector polls several hundred feeds worldwide, deduplicates each story
across the outlets that carried it, detects its language, and keeps it. It is
not a search engine over the live web: it holds what it collected, from when it
started collecting, and nothing else.

## Which surface answers which question

**"What is new?"** — the Centauri feed at `centauri.endpoint.hrafnagud`. A
timeline with a cursor, ordered by collection. Use it to follow a topic
forward, not to search.

**"What is there about X?"** — the research provider at
`research.endpoint.hrafnagud`. Relevance-ranked over titles, teasers and
extracted bodies, in the original language **and** in translation where one
exists. A German query can therefore surface an Indonesian article.

**"Give me the text."** — the mount at `_ext/hrafnagud/`. Every article is a
Markdown file, partitioned by the minute it was collected:

```
_ext/hrafnagud/article/<yyyy>/<mm>/<dd>/<HH>/<mm>/<id>.md
_ext/hrafnagud/img/<yyyy>/<mm>/<dd>/<HH>/<mm>/<sha256>.jpg
```

Read them with the ordinary document tools, link them, embed them. The path is
stable: it means the same file tomorrow, so it is safe to store in a note.

**`mount_list` before concluding a file is absent.** `doc_search` and
`doc_list_in_folder` scan the project's own documents and `_ext` falls out of
their scope — so an article that is there will look like it is not.

## What the files contain

YAML front matter, then the article:

```
title, sources, url, language, published, collected, categories
author, extractor, words        (only where a body was extracted)
```

Two timestamps because they answer different questions and routinely disagree:
`published` is the publisher's claim, `collected` is when the archive saw it.
The **path is derived from `collected`** — an article published last week and
collected today lives under today.

**A file without a body is normal**, not an error. Body fetching is a separate
switch on the archive's side; where it is off, the file carries the title, the
feed's teaser, the delivering sources and the link, and says so plainly. That
is still enough to cite and to follow.

## What it cannot tell you

- **Nothing before it started collecting.** Feed windows reach back years for
  some publishers and hours for others, so early coverage is uneven by
  publisher rather than by date.
- **No opinion about truth.** Several outlets carrying one story is a fact
  about distribution, not corroboration — the archive records who delivered
  what, and the deduplication that groups them is by URL, not by claim.
- **Categories are the publisher's words**, kept verbatim. Where they were
  normalised, that is recorded as a separate judgement, not as a correction of
  the original.
