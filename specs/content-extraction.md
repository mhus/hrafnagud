# Content extraction

Fetching the article page behind a feed entry and separating what was written
from what surrounds it.

## 1. Why this is its own pipeline

Full-text fetching is a separate queue, a separate state machine, and **off by
default** (`munin.content.enabled`).

It is an order of magnitude slower than a feed poll, it fails in far more ways,
and it is a qualitatively different act: reading a document published for
polling, versus fetching a page that was not. Treating the two as one pipeline
would let the slow, failure-prone half set the pace of the reliable one.

Turning it on works through whatever has accumulated, because ingest queues
every article regardless — see [collection.md](collection.md) §3.2.

## 2. States say why

`ContentStatus`: `PENDING`, `FETCHED`, `PAYWALL`, `BLOCKED`, `FAILED`,
`SKIPPED`.

Each rejection gets a status that says *why*, because the cases call for
different responses and collapsing them into "failed" would keep retrying pages
that can never succeed:

| Status | Means | Retry? |
|---|---|---|
| `PENDING` | not attempted yet, or a transient failure with budget left | yes, on the schedule |
| `FETCHED` | body stored | — |
| `PAYWALL` | the page exists but is not readable | no |
| `BLOCKED` | `robots.txt` says no | no |
| `FAILED` | budget exhausted | no |
| `SKIPPED` | an operator took it out of the queue | no |

`POST /api/v1/articles/{id}/fetch-content` requeues one article with a fresh
retry budget; `POST .../skip-content` takes one out for good.

**Permanent failures spend the whole retry budget at once** rather than
retrying four more times to collect four more identical rejections.

## 3. The extraction ladder

Four rungs, tried in order, and which one produced a given body is recorded in
`article_contents.extractor`:

1. **`json-ld`** — the publisher's own `schema.org` metadata. Not a heuristic:
   an answer. Nearly every news site emits it because Google News requires it,
   so coverage is far better than the effort suggests.
2. **`semantic`** — a container the page marks as its article body.
3. **`scored`** — most paragraph text relative to link density.
4. **`body`** — last resort.

### 3.1 Ask before guessing

The ordering is the whole design. Rungs 3 and 4 are guesses, and a guess that
goes wrong here is *silent* — a navigation rail stored as article text throws
nothing.

### 3.2 Metadata merges independently of the body

JSON-LD frequently carries no `articleBody` while its headline, image, date,
language and author still beat anything derived from the DOM. So metadata is
merged across rungs separately from the body decision.

A JSON-LD body shorter than fifty words is treated as a description in the
wrong field, and the DOM rungs are used for the body instead.

`JsonLdReader` is deliberately tolerant: `@type` as an array, `@graph`
wrappers, image as object or array, author as a list. Publishers emit all of
these, and a strict reader would fall through to guessing on pages that had
handed over the answer.

### 3.3 Scoring is by link density

Navigation and related-story rails are text-heavy too, but nearly all of their
text sits inside anchors while article prose sits outside them. That one ratio
separates the two more reliably than any word count.

### 3.4 Recording the rung is what makes quality measurable

Aggregating on `extractor` shows which publishers fall through to the guessing
rungs, and those are the ones worth looking at. Without the field the failure
mode is discovered months later, if at all.

Measured against a few hundred real German and English articles, the `body`
rung accounts for roughly one in eighty and averages far less text than the
rungs above it. Those pages are the queue for improving extraction; the way to
work it is to reduce one to its structural skeleton and add it to the fixture
corpus.

## 4. Images

**Collected as URLs with captions, never as bytes.** A reference is cheap and
uncontroversial; storing publishers' image files is a storage question and a
copyright question at once, and a URL leaves that decision open where the
reverse would not.

- The **lead image** comes from the declaration (JSON-LD, `og:image`).
- **Inline images** come from inside the chosen container, because position is
  a far better discriminator than anything about the image itself.
- **Captions are a field of the image**, not part of the body. In news writing
  the caption is often the most informative sentence about the photograph, and
  burying it in the prose loses that. `figcaption` was originally in the noise
  list and removed for exactly this reason; captions are now harvested before
  the element is dropped.

### 4.1 Two traps, both found by measuring

Neither was reachable by reasoning about it:

**Placeholder sources.** On many sites `src` holds a transparent placeholder
and the real image is in `srcset` or `data-src`. Reading `src` first fills the
archive with references to the same blank GIF.

**Page furniture.** An image with no caption, no alt text and no declared
geometry is furniture — a newspaper's own front-page thumbnail advertising a
subscription is the archetype — because nothing about it was meant to be read.
Applying that rule took inline caption coverage from 90% to 100% and dropped
the `body` fallback rung from 15 pages to 3 in the corpus.

## 5. Word counts are script-aware

Counting whitespace-separated tokens is the obvious implementation and is
wrong for a large part of the world. Japanese, Chinese, Korean and Thai do not
separate words with spaces, so a complete article scores a handful of "words".

Every length threshold built on that then misfires in the same direction, and
the archive quietly ends up holding **no CJK bodies at all**.

`TextCleaner.wordCount` therefore counts characters of unspaced scripts
(`\p{IsHan}`, `\p{IsHiragana}`, `\p{IsKatakana}`, `\p{IsHangul}`,
`\p{IsThai}`) at a fixed characters-per-word ratio.

Caught by the fixture corpus on its first run, which is precisely what the
corpus is for.

## 6. The body is searchable

`article_contents.text` carries a text index, which is what lets a research
query match a phrase that appears only inside an article. How a body hit ranks
against a headline hit — and why the two are not merged — is in
[research-source.md](research-source.md) §3.1.

Adding that index brought MongoDB's language-override trap into this
collection too; `textLanguage` is here for the same reason it is on the
article. See [collection.md](collection.md) §4.1.

## 7. Storage and REST

`article_contents` is a separate collection because bodies are ~50× larger than
the metadata and most queries want the metadata. It holds the extracted text,
the image list, page metadata, the `extractor` rung and `canonicalUrl`.

```
GET  /api/v1/articles/{id}/content
POST /api/v1/articles/{id}/fetch-content
POST /api/v1/articles/{id}/skip-content
```

The feed contract exposes bodies through a different door — see
[feed-source.md](feed-source.md) §6.

## 8. What the fixture corpus is for

Real article pages, reduced to their structural skeleton, checked into
`qa`-style test resources and driven by unit tests.

Two defects on its first run, both of which would have quietly degraded the
archive rather than failing anything:

1. CJK articles rejected by every length threshold (§5).
2. A publisher's own subscription-advert thumbnail stored as article content
   (§4.1).

The corpus is the reason those were found in an afternoon rather than in a
year. Growing it is the maintenance task that keeps extraction honest.

## 9. Limits

- **Page metadata does not flow back to the article.** A page's
  `datePublished`, author and language are frequently better than the feed's,
  but they are stored on the content document rather than overwriting the
  article's fields — otherwise ordering and language provenance would depend on
  when a body happened to be fetched. This interacts with the feed contract's
  chronological ordering: see [feed-source.md](feed-source.md) §4.1.
- **The `body` rung is the weak tail** (§3.4).
- **Storing full article text has copyright implications.** Fine for private
  research; if this output is ever published, quote-plus-link is the defensible
  form.
