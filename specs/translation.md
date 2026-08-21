# Translation

Normalising the archive into one language, by asking a Vancetope brain.

## 1. Scope

Off by default, and it takes **two** switches to turn on. They are separate
because they control different things:

| Setting | Controls | Default |
|---|---|---|
| `munin.translation.pivotLanguage` | what everything is translated **into**, and what gets queued — decided at ingest | empty — nothing is ever queued |
| `munin.translation.readableLanguages` | which other languages need **no** translation | empty — only the pivot is exempt |
| `munin.translation.enabled` | whether the **worker** runs | `false` — the tick returns immediately |

```yaml
munin:
  translation:
    enabled: true
    pivotLanguage: de          # the one target
    readableLanguages: en      # plus: an English article needs no work
    translateSummary: true     # titles alone cost a tenth of the text
```

Two switches rather than one because the failure they guard against is
asymmetric. A pivot language with no worker fills a backlog nothing drains; a
worker with no pivot language idles harmlessly. Collapsing them into one flag
would mean an operator who wants to pause translation has to clear the pivot
language, and clearing it makes every article ingested meanwhile `SKIPPED` —
silently and permanently, because nothing revisits that decision.

All three are [settings](settings.md): they can be changed while the service
runs. The worker picks a change up on its next round; the two language values
are read at ingest, so they apply to articles arriving from then on.

The worker being off by default follows the same rule as the body fetch: both
spend somebody else's resources — publisher bandwidth there, model time and
money here — and neither should start because a service was deployed.

`TranslationService` reports at startup which of the four states the instance
is in (no pivot / pivot but disabled / pivot but no provider / working), and
`GET /api/v1/stats` reports `translationBacklog` for the same reason: three of
those four are otherwise silent.

The record of each run lands in [`enrichments`](enrichments.md), not on the
article. The article does carry a searchable copy of the newest one — a
derived read model, written by the same step; see
[enrichments.md](enrichments.md) §2.1.

## 2. Package boundary

`de.mhus.hrafnagud.translate` is its own package so that Munin keeps no
dependency on Vancetope. Munin owns the queue; it owns neither the engine nor
the result — see [architecture.md](architecture.md) §2.1 for how that survives
without a module to enforce it.

Who does the translating is a `TranslationProvider`:

```java
public interface TranslationProvider {
    String name();
    TranslatedText translate(String title, @Nullable String summary, String targetLanguage);
}
```

An interface rather than a direct call, for the same reason `SourceReader` and
`SourceListParser` are interfaces: the choice of engine is configuration, not
architecture. A brain, a model called directly, a machine-translation API — the
queue cannot tell them apart, and swapping one for another touches only the
bean that is wired.

One implementation ships: `OdeTranslationProvider`, which fires a Vancetope
event.

### 2.1 Wiring is an `ObjectProvider`, not `@ConditionalOnBean`

`@ConditionalOnBean` is only dependable inside an auto-configuration. On a
scanned `@Component` there is no defined ordering against user beans, so the
provider could be skipped with Ode fully configured — and the symptom would be
a backlog that never drains, with nothing to point at. `TranslateConfiguration`
asks an `ObjectProvider` instead, and `TranslateWiringTest` pins both states.

## 3. One target, several languages that need no work

`pivotLanguage` is a single value, not a list of targets.

Everything downstream — search, rating, clustering, embeddings — reads one
language. A list of targets multiplies the work by the number of languages to
serve a need nobody downstream has. Translating into a second language is a
*presentation* concern and belongs wherever it is displayed.

**One target does not mean one exempt language.** `readableLanguages` is the
list of languages that need no translation at all, and it exists because a
reader is rarely monolingual: with `pivotLanguage: de` and nothing else, an
archive whose reader is comfortable in English pays a model to translate every
English article into German — half the archive, for nothing. Listing `en` marks
those `SKIPPED` at ingest instead.

The pivot is always exempt and does not have to be repeated in the list — a
model asked to translate German into German can only return what it was given,
at full price.

**The decision is made at ingest**, in `TranslationLanguages.needsTranslation`
via the one function that derives the status
(`ArticleFactory.initialTranslationStatus`), so an article is never queued and
then discovered to be a no-op:

| Pivot | Readable | Article language | Status |
|---|---|---|---|
| unset | anything | anything | `SKIPPED` |
| `de` | — | `de` | `SKIPPED` |
| `de` | — | `en` | `PENDING` |
| `de` | `en` | `en` | `SKIPPED` |
| `de` | `en` | `fr` | `PENDING` |
| `de` | anything | unknown | `PENDING` |

An **unknown** language is queued whatever the list says. The list says which
languages need no work, not that an article nobody could classify needs none;
a provider handed text already in the target returns it unchanged, so being
wrong that way costs one call, while skipping wrongly loses the translation
silently.

Both values are normalised to their BCP-47 primary subtag before comparison, on
both sides — `de-DE` in the setting and `de` on the article are the same
language, and a set that compared them literally would silently never match.
A list entry that is not a language tag is **refused** by the API rather than
dropped: a typo here has no visible effect other than a translation bill.

That makes the backlog the count of actual work. Measured on a live run: 60
German articles from a German source produced **zero** model calls, while 25
English ones were queued and translated.

### 3.1 Why this is not a filter rule

The filter can already match on language, so `DENY translation WHERE
language = en` would keep English articles out of the queue. It is the wrong
tool, and the difference is visible to a reader.

A filter decision records what the archive judged **not worth paying for**, and
the `accepted` facet serves it as `accepted:no` on both Vancetope-facing
surfaces ([filter.md](filter.md) §6, [feed-source.md](feed-source.md)). An
article in a language the reader already understands is fully in scope and
merely needs no work. Expressing the second as the first would make that facet
say the archive discarded something it did not.

The two also compose the way you would want: the filter is asked first, so a
denied article never reaches the language check.

### 3.2 Changing the languages later

Both values are settings, and both are read at ingest — so a change applies to
articles arriving from then on. Stored articles keep the status they were given
until something revisits it, and the thing that revisits it is the filter
re-evaluation:

```bash
curl -X POST 'localhost:9800/api/v1/filter/reevaluate?days=30'
```

That path asks the same `initialTranslationStatus`, so adding `en` to
`readableLanguages` and re-evaluating takes the already-queued English articles
*out* of the queue, and removing it puts them back in. A queue only moves when
the decision actually flips, so a finished translation survives a run
([filter.md](filter.md) §7).

## 4. Title and teaser are one unit of work

One call for both. The earlier design translated each separately so that a
failing teaser could not sink the title.

That trade is only worth it when the two calls cost the same. Here the recipe
prompt is several times the length of the text being translated, so splitting
doubles the expensive half to protect the cheap one. A failed pair is retried
whole.

`TranslatedText` carries `title`, `summary` and `model` — the last of which is
per result, not per provider, because a provider with a fallback chain answers
with different models on different calls. See
[enrichments.md](enrichments.md) §3.1.

## 5. The queue

A state field plus a partial index, not a query — see
[architecture.md](architecture.md) §4.1. `TranslationStatus` is `PENDING`,
`DONE`, `SKIPPED`, `FAILED`.

`TranslationTick` claims a batch (`munin.translation.batchSize`) every
`tickInterval`; `translationNextAttemptAt` is both schedule and lease.

**Permanent failures spend the whole retry budget at once.** Retrying a
rejected token four more times produces four more rejections. The provider says
whether its failure is worth repeating, through
`TranslationException.isRetryable()`; the queue does not second-guess it.

`POST /api/v1/articles/{id}/translate` requeues one article.

## 6. The Vancetope side

The provider fires a synchronous event and reads the answer out of the same
response:

```yaml
vance:
  ode:
    base-url: https://brain.example.com
    tenant: acme
    project: news
    events:
      translate-article:
        token: ${VANCE_TRANSLATE_TOKEN}
        timeout: PT150S
```

Request: `{ title, summary, targetLang }`.
Response `output`: `{ title, summary, model }`.

### 6.1 Why an event and not a model call

The brain side is the `translation` kit — three documents:

```
_vance/events/translate-article.yaml     the event: synchronous, bearer auth
_vance/scripts/translate-article.js      calls vance.llm.callForJsonWithModel
_vance/recipes/article-translate.yaml    the LightLlm config profile
```

The prompt and the model therefore live in documents an operator can edit
without redeploying this service. That is the entire reason to integrate
through an event rather than to call a model from here: this module holds no
prompt, no model choice and no notion of how a translation is produced. It does
*report* the model, because it reads it off the answer rather than deciding it.

The event is a `script:` action and so synchronous by default — the script runs
to completion and its return value comes back as `output`. No process spawn, no
lane lock, no polling.

### 6.2 No output is a configuration mistake, not an empty result

An event that answers with no `output` at all is almost always `async: true` on
the brain side, or an output the caller is not permitted to see. The provider
raises a permanent failure that says so, rather than storing an empty
translation.

A blank *title* in a non-blank request is the same class of problem: the event
answered with something that is not a translation. Storing it would put an
empty headline in the archive and mark it translated.

### 6.3 Retry verdicts are not re-derived

Ode has already classified whether the far end might behave differently next
time. Re-deciding that from the HTTP status here would be a second, divergent
opinion — `TranslationException` carries Ode's verdict through.

## 7. Timeouts, end to end

Three of them, and they have to nest:

| Layer | Value | Why |
|---|---|---|
| Kit script | `@timeout 120s` | the script, including the model call |
| Ode client | `PT150S` | must clear the brain-side timeout, not undercut it |
| LightLlm sync deadline (brain) | 90 s | bounds retries + fallbacks inside the brain |

A model call is slow by HTTP standards. The failure mode of getting this wrong
is the caller giving up while the brain is still working, which looks like a
brain fault and is not.

## 8. Limits

- **`model` can be null**, and must stay null — see
  [enrichments.md](enrichments.md) §4.
- **Full-text is not translated.** Only title and teaser. Bodies are an order
  of magnitude more tokens, and the case for them is a reader who wants to read
  the article rather than triage it.
- **No significance gate.** Every article in a language that is neither the
  pivot nor readable is queued. Translating only what matters requires knowing
  what matters, which is an enrichment that does not exist yet.
- **A readable language does not shorten the archive's own record.** The
  article keeps its language and its own title; `readableLanguages` only means
  no enrichment is produced for it. A reader asking the feed for one of those
  articles gets it in its own language, which is what it would have got anyway
  while the translation was pending.
