# Categories

Turning what publishers call their sections into something comparable across
publishers and languages — without losing what they actually said.

> **Status: design.** Nothing here is built. The numbers below were measured
> against a live archive of 21,327 articles, because the shape of this problem
> is not guessable and the first design that felt right turned out to be worth
> a fifth of what it claimed.

## 1. What is actually in the data

`ArticleDocument.categories` holds feed and source categories **verbatim**, and
that stays ([collection.md](collection.md) §5): publishers disagree about what
a category is, and folding them at ingest destroys information that cannot be
recovered. Everything here is **additive** — a second field beside the
original, never a replacement.

Measured on 21,327 articles from 840 feeds: **7,365 distinct categories**,
44,542 uses. They are not 7,365 topics. At least four different kinds share one
field:

| Kind | Examples, with use counts |
|---|---|
| Topics | Cryptocurrency 2050, Business & Economy 1891, Science 1323, Tech 731, Sports 627 |
| Topics, three levels down | Cricket 1621, Chess 406, Android Development 357, iOS Development 332 |
| **Places** | Ukraine 707, Germany 583, Russia 448, India 437, France 417, Poland 381, Iran 305 |
| Entities, formats, noise | News 650, `curated` 580, René Habermann 1, Warframe 1, `baked` 1, SASSA Old Age Grant 1 |

That mix is the central design constraint. A mapping whose only answer is
"which topic" cannot describe this data: it would have to invent a topic for a
person's name, and it would be asked again next week.

**Some of the noise is self-inflicted** and cheaper to fix at the source than
to map: `curated` comes from a source list's own `defaultCategories`, and
`Germany`/`Ukraine` partly from the OPML parser attaching folder labels — which
for `countries/with_category/Germany.opml` is a country name.

## 2. Normalise against a standard, not an invention

**IPTC Media Topics** is the news industry's vocabulary for exactly this.
Checked rather than remembered:

- **1,393 concepts**, five levels deep, 17 top-level terms
- **13 languages** in the labels: `medtop:04000000` is *economy, business and
  finance* / *Wirtschaft und Finanzen* / *Économie et finances* / *经济、商业和金融*
- One download, SKOS JSON: `http://cv.iptc.org/newscodes/mediatopic/?format=json`
  (2.4 MB), plus RDF and NewsML-G2
- **CC BY 4.0** — *"all IPTC NewsCodes are licensed with the Creative Commons
  CC-BY 4.0 licence. This means that you can use them for free in any way, but
  we request that you give IPTC credit somewhere on your website."*
- IPTC also maintains mappings from Media Topics to Wikidata concepts

Attribution follows the rule already written for GeoNames ([geo.md](geo.md)
§5.1): README, a `NOTICE`, and something reachable from the running service —
added with the data, not before.

Being hierarchical, it gets the same treatment as places: the **ancestor path
is materialised**, so "everything about sport" finds an article tagged
*Cricket* with one equality match instead of a tree walk.

## 3. The mapping table

A collection keyed by the raw string, `category_mappings`:

```
key         cryptocurrency          # normalised: lowercased, accents folded,
raw         Cryptocurrency          # punctuation to spaces. The original is kept.
status      RESOLVED
topicId     medtop:20001279
topicPath   [medtop:13000000, medtop:20001279]
confidence  1.0
source      LABEL_EXACT             # which stage decided
attempts    1
```

The key is normalised so that `Personal finance`, `personal-finance` and
`Personal  Finance` are one entry rather than three. The raw form is kept
beside it, because the normalisation is lossy and an operator looking at a
questionable mapping needs to see what the publisher actually wrote.

### 3.1 Status is the queue

Same mechanism as the two asynchronous pipelines that already exist
([architecture.md](architecture.md) §4.1): a status field plus a partial index
on the pending values, so the index stays proportional to the backlog rather
than to the vocabulary. `nextAttemptAt` doubles as the claim lease (§4.2).

| Status | Meaning |
|---|---|
| `NEW` | seen at ingest, nothing tried |
| `GUESSED` | stage 1 matched, but not well enough to trust (see §4) |
| `RESOLVED` | stage 2 decided |
| `CONFIRMED` | a human agreed; never revisited |
| `NOT_A_TOPIC` | a format, a person, a product, junk — **terminal** |
| `IS_PLACE` | a place, not a topic — terminal here, useful elsewhere (§6) |
| `FAILED` | stage 2 could not decide after its attempts |

The two terminal non-topic states are the point of §1. Without them the job
asks about *René Habermann* forever, and every run costs the same tokens to
learn the same nothing.

## 4. Two stages, and what each is worth

**Stage 1 — string matching against the vocabulary's own labels.** No model, no
network, runs at ingest. Measured against the real 7,365:

| | Categories | Uses |
|---|---|---|
| Exact label match, all 13 languages | 476 (6.5 %) | 9,326 (20.9 %) |
| plus token-set and singular equality | 540 (7.3 %) | 10,142 (22.8 %) |
| plus single-word subset match | 844 (11.5 %) | 14,686 (33.0 %) |

So stage 1 is worth building and nowhere near sufficient. It is cheap and it
clears the frequent, unambiguous head — *Cryptocurrency*, *Cricket*, *Chess*,
*Tennis*, and with token equality *Sports* → `medtop:15000000`. It leaves the
obvious near-misses to stage 2, which is the honest reading of the misses:
*Business & Economy* (against *economy, business and finance*), *Tech* (against
*technology*), *Science*, *News*.

The single-word subset row is included because it is tempting and should be
treated with suspicion: it reaches 33 % of uses by mapping any category whose
one word appears in any label, which is how *standard* becomes a topic. If used
at all, its results belong in `GUESSED`, not `RESOLVED`.

**Stage 2 — one LLM call per unresolved category**, over the Ursa event path
the translation provider already uses (`OdeTranslationProvider` as the
blueprint; the brain side is a kit, so prompt and model stay editable there).
It answers with a Media Topic id, or with `NOT_A_TOPIC` / `IS_PLACE`.

## 5. Cost is bounded by the vocabulary, not the volume

This is what makes stage 2 affordable where article translation is not. A
category is resolved **once**: 7,365 entries for an archive of 21,327 articles,
and the ratio improves with every article collected. The 25 most frequent
categories cover a large share of all uses, so even a partial run is useful
immediately.

Growth is the long tail — one-off tags like *Warframe* — which is exactly what
`NOT_A_TOPIC` is for, and why the terminal states matter more than the
resolution rate.

## 6. What lands on the article

```
categories  ["Business & Economy", "Ukraine"]                    # untouched
topicIds    [medtop:04000000]                                     # + ancestors
```

`topicIds` is a **derived read model**, written by the step that records the
mapping — the same relationship `pivotTitle` has to a translation enrichment
([enrichments.md](enrichments.md) §2.1). The mapping table stays the record of
what was decided and why; the array on the article is what an index can use.

**A mapping change does not rewrite history by itself.** Articles keep the
`topicIds` they were written with until something backfills them — the same
property the fetch profiles have, and it needs saying because a table that
"learns" invites the assumption that everything updates behind it.

`IS_PLACE` entries are not dead ends elsewhere: *Ukraine* as a category on a
German outlet is evidence about what the article is **about**, which is the
`contentLocation` that [geo.md](geo.md) §3.2 leaves unbuilt. The place
hierarchy is already there to receive it. Not now, but the status exists so the
information is not thrown away.

## 7. Where it stops

- **No multi-label classification from text.** This maps *categories the
  publisher gave*, not article bodies. Classifying an untagged article is a
  different job with a different cost curve.
- **No confidence on the article.** `topicIds` is either there or not; the
  uncertainty lives in the mapping table where it can be inspected and fixed
  once, rather than being re-decided per article.
- **One vocabulary.** Media Topics only — a second target taxonomy would double
  every mapping and immediately raise the question of which one is authoritative.
- **No automatic re-resolution.** A `CONFIRMED` mapping is never asked again,
  and a `FAILED` one waits for a person or a better prompt rather than
  retrying on a timer.
