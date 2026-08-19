# Enrichments

Where the output of a processing step over an article goes.

## 1. What an enrichment is

An article is what was collected. An **enrichment** is what some later step
made of it: a translation today; a rating, keywords, a sentiment score or an
embedding later.

One document per **run**, not per article and not per kind. Running the same
step again adds a document; it does not replace one.

## 2. Why not a field on the article

This is the decision the collection exists for, and it went the other way
first — translations were originally an embedded map on `ArticleDocument`.

A processing step can be repeated: with a better model, a fixed prompt, a
different provider. Each run is a fact worth keeping — *what* was produced, *by
which model*, *when*. Written onto the article that becomes a single mutable
field, and re-running destroys the comparison that is the whole reason to
re-run.

So the collection is append-only and read newest-first. An improved run
supersedes an older one without erasing it:

```
2026-08-19T09:31:33Z  producer: vance-ode  model: openai:deepseek-v4-flash-0731
2026-08-19T09:04:50Z  producer: vance-ode  model: null
2026-08-19T09:00:38Z  producer: vance-ode  model: null
```

That listing is from the live archive, and it shows the point: the two older
runs carry no model because they predate the ability to report one. The newest
does. Had the translation been a field, that history would read as if it had
always been there.

Rating, keywords and sentiment have the same shape, which is why the collection
is not called `translations`.

## 3. Model

`EnrichmentDocument`, collection `enrichments`:

| Field | Meaning |
|---|---|
| `articleId` | the article this is about |
| `type` | `EnrichmentType` — `TRANSLATION` today |
| `producer` | *what* produced it, e.g. `vance-ode`. Stable across models. |
| `model` | *which model* answered. Nullable — see §4. |
| `language` | the language of the result, where that is meaningful |
| `createdAt` | when this run happened; the newest-first key |
| `content` | the result itself, shape defined by `type` |

Indexes:

```
article_type_idx  { articleId: 1, type: 1, createdAt: -1 }
type_model_idx    { type: 1, model: 1 }
```

The first serves both single-article lookup and the newest-first read. The
second is for asking what a given model has produced — the query you want when
deciding whether a re-run was an improvement.

`content` is a free-form map rather than a typed field per enrichment kind.
The alternative is a schema change per new step, and the shape of a rating is
not knowable before the rating exists.

### 3.1 `producer` and `model` are different questions

`producer` names the mechanism; `model` names the thing that answered. They
change independently: the same producer can answer from a fallback model, and
the same model can be reached through a different producer.

`model` moved from the provider to the *result* for exactly that reason — a
provider with a fallback chain answers with different models on different
calls, and the call where they differ is the one worth being able to identify
later.

## 4. `null` means unknown, and stays unknown

`model` is nullable, and a nullable field invites filling it with something
plausible. It must not be.

A guessed model in an archive does not look like a guess — it looks like
evidence. An empty field is honest about what was not observed; a plausible
substitute makes the comparison in §2 worthless without announcing it.

The whole chain holds this line: the brain reports `null` when a call left no
trace, the kit script passes `null` through rather than substituting the
recipe's alias, the Ode provider reads absent-or-blank as absent, and the
service stores it as `null`. There is a test at each step.

## 5. Reading them

```
GET /api/v1/articles/{id}/enrichments        all of them, newest first
GET /api/v1/articles?withTranslation=true    folds the newest TRANSLATION into each row
```

The list endpoint uses `EnrichmentService.latestForEach(ids, type)`: one query
per page, newest-wins per article. Not one query per row — a listing endpoint
that issues N+1 queries is how a page turn becomes the slowest thing in the
service.

Deleting an article deletes its enrichments (`ArticleService.delete` calls
`EnrichmentService.deleteForArticle`). An enrichment about an article that no
longer exists is not history, it is a leak.

## 6. Limits

- **Enrichments are not indexed for text search.** The article's text index
  covers its own title and teaser, so a translated article is searchable by its
  original words and not by the translated ones. This is visible in the feed
  contract — see [feed-source.md](feed-source.md) §5.1 — and making
  translations searchable means indexing this collection.
- **No retention policy.** Enrichments grow with the archive and with every
  re-run, and nothing prunes them.
- **One type so far.** `TRANSLATION` is the only `EnrichmentType`. The
  forecast — rating, keywords, sentiment, significance, embeddings — is what
  the collection was shaped for, and deliberately not built before there is a
  consumer that would tell us which of them matters.
