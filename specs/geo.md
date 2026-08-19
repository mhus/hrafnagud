# Places

Where an article comes from, where it was written, and what it is about — three
different questions that a single `country` field silently answers wrongly.

> **Status.** §3.1 and §3.3 are built: the containment table ships, and every
> article carries its publisher's place path, queryable at any level
> (`GET /articles?originPlace=m49:142`). §3.2's `contentLocation`, §4's `GEO`
> enrichment and §5's gazetteer are still design — they need extraction, which
> is the project rather than the model.
>
> The field names were written down before anything was persisted on purpose:
> renaming a field that sits in a multi-million-row collection and an index
> costs more than deciding early.

## 1. The two mistakes this avoids

**A source's country is not an article's subject.** Deutsche Welle appears in a
German source list and publishes in English about the world; Reuters Singapore
files about Ukraine. Deriving subject from origin produces a filter that is
wrong in exactly the cases that matter — international coverage — and wrong
invisibly, because the value looks plausible.

**Places are containment, not labels.** "Everything about Asia" has to find an
article tagged Singapore. A flat string field cannot answer that, and neither
can a country code: the question spans levels above the country (region,
continent) and below it (state, city).

## 2. Three locations, not two

The distinction is standardised vocabulary; schema.org defines both properties
on `CreativeWork`:

> **`contentLocation`** — "The location depicted or described in the content.
> For example, the location in a photograph or painting."
>
> **`locationCreated`** — "The location where the CreativeWork was created,
> which may not be the same as the location depicted in the CreativeWork."

Dublin Core's `dcterms:spatial` is the same idea as `contentLocation`; IPTC
NewsML-G2 separates them as well.

| | Level | schema.org | Cost | Cardinality |
|---|---|---|---|---|
| Publisher's seat | source, stable | `Organization.location` | free, set once | 0..1 |
| Dateline | article | `locationCreated` | cheap, from the text | 0..1 |
| Subject | article | `contentLocation` | expensive, uncertain | 0..n |

**The publisher's seat is not `locationCreated`.** That property belongs to the
*work*: `SINGAPORE (Reuters) —` is where the piece was filed, and the
publisher's registered seat is London. For agency and syndicated material the
two come apart routinely — which is most of the wire. Naming the source's field
`locationCreated` would leave the dateline homeless and make the field lie for
the highest-volume sources in the registry.

## 3. What each becomes

### 3.1 Source: `country` stays what it is

`SourceDocument.country` already means "ISO 3166-1 alpha-2 country of the
publisher". That is the publisher's seat, and it keeps that meaning. Two things
about it are worth knowing before it is used as a signal:

- **It is usually unset.** A country reaches a source only from a hand-set list
  default. The `github-opml` reader deliberately assigns none: the file is
  called `Germany.opml`, not `DE.opml`, and guessing an ISO code from a
  filename would be the first error in a chain of them.
- **It is weak even when set.** See DW above. It says who published, not what
  about.

Denormalised onto the article it becomes `originCountry`, with
`originPlaceIds` holding its containment path — named for what they are, never
merged into the subject fields. Both are written by `setOnInsert`: origin
belongs to the source that delivered the article *first*, and a second
publisher carrying the same story does not move where it came from.

One query parameter covers every rung, because the path holds all of them:

```
GET /api/v1/articles?originPlace=m49:142   # every Asian publisher
GET /api/v1/articles?originPlace=iso:SG    # only Singaporean ones
```

### 3.2 Article: `contentLocation` as a materialised path

```
placeIds:   ["m49:001", "m49:142", "m49:035", "iso:SG"]   # World, Asia, South-Eastern Asia, Singapore
placeLeaf:  "iso:SG"
```

The ancestor chain is **stored, not resolved at query time**. "Everything about
Asia" is then an equality match on a multikey index rather than a graph
traversal, which is the difference between an index lookup and a join MongoDB
does not have. The same shape the archive already uses for `sourceNames`.

The price is the usual one for a materialised path: when the hierarchy changes,
stored rows are stale. That happens on the timescale of countries splitting, it
is a backfill, and it is cheaper than paying for traversal on every query.

Identifiers are **prefixed by scheme** (`m49:`, `iso:`, `gn:`, `wd:`) so that
the gazetteer choice below is visible in the data and two schemes can coexist
during a migration. A bare number that turns out to be a GeoNames id in one row
and a Wikidata QID in another is not recoverable.

### 3.3 The hierarchy itself

| Range | Standard |
|---|---|
| World → region → sub-region → country | **UN M.49** (`unstats.un.org/unsd/methodology/m49/`), the same containment tree CLDR uses |
| Country | **ISO 3166-1 alpha-2** |
| Below country | **ISO 3166-2** subdivisions, then gazetteer levels |

M.49 above the country and ISO below it means the top of the tree is a small,
stable table that ships with the application, and only the long tail needs a
gazetteer.

## 4. Where it lives: an enrichment

Geoparsing is expensive, model-dependent, re-runnable with a better model, and
worth comparing across runs. That is the argument `enrichments.md` already
makes for the collection, almost word for word — so this is an
`EnrichmentType.GEO`, not a field written at ingest.

The article then carries `placeIds` as a **derived read model**, written by the
step that records the enrichment, exactly as `pivotTitle`/`pivotSummary` are
mirrored for the text index (`enrichments.md` §2.1). The enrichment stays the
append-only record of what each run concluded; the array on the article is what
the index can use.

Each place in the enrichment carries **provenance and confidence**: which step
said so, and how sure. A place derived from the dateline, one extracted from
the body, and one inherited from a source's country are three different claims,
and collapsing them into one array without saying which is which makes the
first bad extraction unfindable.

## 5. Gazetteer options

Licences checked, not remembered:

| | Licence | Size | Notes |
|---|---|---|---|
| **GeoNames** | CC BY 4.0 | 12M features, 25M names, 4.8M populated places | Daily dumps: `allCountries.zip`, `cities1000.zip`, `countryInfo.txt`, `admin1CodesASCII.txt`, and — directly relevant — `hierarchy.zip`, the parent-child chain |
| **Wikidata** | CC0 | — | `P131 located in the administrative territorial entity`; stable QIDs, SPARQL, but a live dependency unless dumped |
| **Who's On First** | CC0 for the structure; some upstream sources require attribution | — | Purpose-built hierarchy with an explicit placetype ladder |

**Recommendation: GeoNames**, for `hierarchy.zip` and `cities1000` — the
hierarchy is the thing being modelled and it ships as a file, the whole set
fits on disk, and attribution is one line. CC BY needs a credit somewhere
visible, which is a decision to take deliberately rather than discover.

### 5.1 Where the attribution goes

CC BY 4.0 wants the notice to reach **whoever receives the material**, "in any
reasonable manner based on the medium, means and context", and it wants
modification declared — and a subset re-keyed with our own id scheme and
flattened into ancestor paths is a modification.

The repository README is necessary and not sufficient: somebody running the
container never sees it. So two places, and neither is expensive:

- **`README.md` and a `NOTICE` file** — for anyone who has the source. The
  repository has no `NOTICE` today; the sibling `vance-ode` does, so the shape
  is already familiar in this family.
- **Something reachable from the running service** — the console footer is the
  honest place, since that is where a person looks at the data. `/actuator/info`
  is the cheap one.

Wording, so it is decided rather than improvised:

> Place data derived from [GeoNames](https://www.geonames.org/), licensed under
> [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Modified: subset,
> re-keyed, hierarchy flattened.

**Added when the data is, not before.** A notice for a dependency the build does
not have is a claim that is not true yet, and it makes the next reader trust the
other notices less.

**How much has to be visible depends on what leaves the service**, and §3.2
already narrows that: ids only, no place names on the article. `iso:` and `m49:`
identifiers are ISO and UN vocabulary rather than GeoNames content, so what the
feed and search contracts hand to Vancetope carries almost nothing of it. What
is genuinely derived from GeoNames is the containment *below* country, and that
is why the attribution stays regardless — the deployment holds a copy of their
hierarchy, and that is the thing being credited.

GPLv3 on the code and CC BY 4.0 on the data are separate works with separate
obligations; there is no copyleft interaction to resolve, only an attribution to
carry.

## 6. Resolution is the hard part

The model is a day's work; turning text into place ids is the project. Known
failure modes, all of them ordinary in news:

- **Ambiguity by name.** *Georgia* (country or US state), *Springfield*
  (dozens), *Vienna* (Austria or Virginia).
- **Datelines that are not subjects.** `BERLIN (Reuters) —` marks where it was
  filed. It belongs in `locationCreated`, and putting it in `contentLocation`
  makes every agency piece look like it is about the bureau's city.
- **Demonyms and adjectives.** "French pension reform" names a place without
  naming it.
- **Organisations that contain places.** "Bank of England", "University of
  Chicago", "Manchester United".

Two rules follow, and both already exist elsewhere in this service:

1. **Abstain rather than guess.** Language detection already refuses below
   `minChars` because "a confident wrong language is worse than an honest
   unknown". A wrong place is worse still: it is not just displayed, it is
   filtered on, so one bad extraction quietly pollutes every query that touches
   that region.
2. **Do not let origin leak into subject.** Tempting, because the source's
   country is free and always there — and it is precisely the mistake in §1.

## 7. Where it stops

- **No coordinates, no geometry, no radius search.** The questions this archive
  gets are containment questions ("about Asia"), and a point on an article is
  neither available nor meaningful for most of them.
- **`locationCreated` is reserved, not built.** The dateline is cheap to
  extract (a regex over the first line of agency copy) and worth having, but it
  is a separate step from subject extraction and should not wait for it.
- **No place *names* stored on the article.** Ids only; display names come from
  the gazetteer and depend on the reader's language, which is not a property of
  the article.
- **No retention policy**, same as every other enrichment.
