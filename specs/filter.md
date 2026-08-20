# Filter rules

Which articles are worth spending money on.

Two pipelines cost something per article: fetching the page body costs a
request, and translating costs tokens. Today neither can be steered by anything
an operator would recognise as a policy. This document is the design for one.

Status: **v1 built**, verified against a live archive.

## 1. What decides today

**Translation** is gated once, at ingest, by
`ArticleFactory.initialTranslationStatus`: no `pivotLanguage` configured, or the
article is already in it, means `SKIPPED`; anything else is `PENDING`. That is
the whole rule.

**Body fetching** is not gated at all. Ingest queues everything as `PENDING`.
The only way out is `skipContent(articleId)`, one article at a time, by hand —
written for a source that reliably yields nothing extractable.

Between those two there is nothing that can express any of the six things an
operator actually wants to say:

- not YouTube, ever
- these categories, yes
- these regions, yes; those, no
- news but not blogs
- this language
- not this source

## 2. A rule set in the database

A rule is a row, not a line in a config file: the operator is going to write
these while looking at the data, and a redeploy per rule is the difference
between a filter people tune and a filter people stop touching.

```
ruleSet     translation             # which pipeline (§5)
decision    DENY                    # ACCEPT | DENY
type        host                    # what to look at (§3)
value       youtube.com
matchType   SUFFIX                  # how to compare (§4)
enabled     true
note        "video, nothing to translate"
```

### 2.1 Evaluation: accept, then deny, then the default

1. If **any** `ACCEPT` rule matches → accepted.
2. Otherwise, if **any** `DENY` rule matches → denied.
3. Otherwise → the rule set's default, which is `ACCEPT`.

Because both steps are "any rule matches", **rules are a set and not a list**:
no `sortIndex`, no last-match-wins, no first-match-wins. Nothing about a rule
depends on which other rules exist, which is the property that makes a growing
rule set stay understandable. It is also the property that a priority field
would destroy, which is why there is not one.

Two consequences worth stating rather than discovering:

**Accept beats deny.** That is what "accept first" means. It is the right way
round, because the useful shape is a broad exclusion with narrow exceptions:
*deny everything from this region, except what is about sport*.

**With the default at `ACCEPT`, the accept list is only an exception list.** A
rule set with accepts and no denies behaves exactly like an empty one. To say
"translate *only* X" the default has to change, so the default is a field on the
rule set — `defaultDecision`, value `ACCEPT` — rather than a constant. Without
it the whitelist case needs a catch-all deny rule, which is an attrappe: it
matches everything in order to mean nothing.

### 2.2 In memory, refreshed

Rules are tens; articles are millions. The rule set is loaded into memory and
refreshed the way catalogues are, so evaluating an article is not a database
read. A write through the API refreshes immediately; there is no interval to
wait out.

## 3. What can be matched

| `type` | Reads | Available at ingest |
|---|---|---|
| `url` | the article's canonical URL | yes |
| `host` | the host of that URL | yes |
| `source` | source name (§3.4) | yes |
| `language` | detected article language | yes |
| `region` | `originPlaceIds` (§3.2) | yes |
| `category` | the publisher's raw category strings | yes |
| `topic` | `topicIds` (§3.1) | **mostly not** |
| `profile` | the source's fetch profile (§3.3) | yes |

`region` and `topic` match by **containment**, not equality: `m49:142` matches a
Singaporean source and `medtop:15000000` matches an article tagged *Cricket*.
Both are one equality match against a materialised ancestor path — which is
what those paths were materialised for
([geo.md](geo.md) §3.1, [categories.md](categories.md) §2).

### 3.1 Categories resolve later than the filter runs

This is the sharpest interaction in the design. Category normalisation is
asynchronous: stage 1 settles 5 % of categories and 20 % of uses at ingest, and
stage 2 — the model — runs later and is off by default
([categories.md](categories.md) §4). A rule on `topic` therefore sees almost
nothing at the moment a new article is filtered.

Hence two types rather than one, and they are not redundant:

- **`category`** matches the raw strings the publisher wrote. Always available,
  never normalised, thirteen spellings of the same idea. It is the type that
  works today.
- **`topic`** matches the resolved vocabulary. Clean, language-independent,
  hierarchical — and empty until the mapping exists.

It also means **re-evaluation (§7) is structural, not a convenience**. Every
category that stage 2 resolves can change the answer for every article carrying
it. A design where the filter only ever runs once would silently make the
`topic` type useless.

### 3.2 Region is where the publisher sits

`originPlaceIds` is the origin — the seat of the publisher — and not what the
article is about. `contentLocation` is deliberately not built
([geo.md](geo.md) §3.2).

For *this* filter that is the right signal anyway: language follows the
publisher, not the subject, and the question here is what to translate. But
"regions I want translated" means "sources from those regions", and a rule
written in the belief that it selects articles *about* Singapore will be wrong
in a way that looks like it works.

### 3.3 `profile` is a cadence class, not a genre

There is no `news` / `blog` attribute on a source. `SourceType` is an enum with
one value, `RSS` — that is the protocol. What exists is `fetchProfile`, the
polling interval class, which today happens to carry values like `news` and
`blog` because that is how the bundled catalogues were split
([catalogs.md](catalogs.md) §8a).

So `profile` is offered, under its real name, and it answers "how often do we
poll this" rather than "what kind of publication is this". Those coincide right
now by accident. Inventing a second taxonomy to make the accident permanent
would be worse than naming it: when a real genre attribute is wanted, it
becomes a source field and gets its own rule type, and existing `profile` rules
keep meaning what they said.

### 3.4 A `source` rule matches if any source matches

An article can arrive from several feeds — deduplication keeps one record with
a list of sources. A `source` rule matches when **any** of them matches. The
alternative, matching only the first source seen, would make the outcome depend
on which feed happened to be polled first.

## 4. How values are compared

| `matchType` | Meaning |
|---|---|
| `EXACT` | equal, case-insensitive |
| `PREFIX` | starts with |
| `SUFFIX` | ends with |
| `CONTAINS` | substring |
| `REGEX` | full regular expression |

`SUFFIX` on `host` is the one that matters and the reason `host` exists as a
type at all: `CONTAINS youtube.com` on a full URL also matches a foreign URL
that merely mentions YouTube in a query parameter. "No YouTube" is the first
rule anybody writes, and getting it as a substring of the whole URL is subtly,
silently wrong. `SUFFIX` on the host is right for domains because it is exactly
how domains nest.

**A regular expression is compiled when the rule is saved, not when an article
is evaluated.** An invalid pattern is rejected by the API with an error the
operator sees in the dialog. The alternative — discovering it at evaluation
time — turns a typo into a rule that quietly matches nothing, or a rule that
throws once per article.

## 5. Two pipelines, one engine

Body fetching and translation get **separate decisions from the same
mechanism**: rules carry a `ruleSet` of `content` or `translation`, and each
pipeline evaluates its own.

They are separately worth deciding because they are separately expensive. A
request is not tokens. YouTube belongs in neither. But a paywalled foreign
source is worth translating for its teaser while its body is not worth
fetching, and a source whose text is fine already needs no translation at all.

One mechanism, because the alternative is discovering the need for the second
decision after the first one is built, and implementing the same matching twice.

## 6. What lands on the article

Per pipeline, a decision plus the rule that made it, rather than a flag:

```
contentPolicy             ACCEPT | DENY
contentPolicyRule         <rule name>        # null when the default applied
translationPolicy         ACCEPT | DENY
translationPolicyRule     <rule name>
policyAt                  <timestamp>        # shared: one run decides both
```

The rule name is the point. A filter whose decisions cannot be explained is a
filter nobody can fix — the same argument as `decidedBy` on a category mapping
([categories.md](categories.md) §3). "Why is this article not translated" has
to be answerable by looking at the article.

Which is why the record is written on **every** evaluation and not only when
the answer changes. That looks like a saving and is not: an accept rule
produces `ACCEPT`, which is also the default, so an article rescued by an
exception would be stored exactly like one that no rule ever touched. Writing
only on a change lost precisely the case the accept list exists for — measured,
on a live archive, as one visible rescue out of several thousand decisions.

**The status field stays the queue.** `DENIED` means `translationStatus:
SKIPPED`; `ACCEPTED` means the language check decides as it does today. No
second queue, no new index — [architecture.md](architecture.md) §4.1 stands.

Which makes the policy field the thing that distinguishes two `SKIPPED`s that
mean different things: *filtered out* and *already in the pivot language*. Only
the first is reversible by changing a rule.

Ingest and re-evaluation must therefore compute the status the **same way**:
policy first, then language. That is one function called from both places, not
two implementations that agree at the time of writing — and the first version of
this did have two. Lifting a deny rule set `PENDING` directly instead of asking,
which queued four thousand articles for a worker that could never have run them,
because no pivot language was configured. Accepting an article is not the same
statement as needing it translated.

(It also means the new fields join the enumerated `setOnInsert` list in
`ArticleService.upsert` — a new article field that is not enumerated there is
silently never written, which has already happened once with the origin place.)

## 7. Re-evaluation

A button, because rules change and articles do not re-arrive.

- **Bounded by time**, as proposed: a window such as the last 10 days or the
  last year. The archive is millions of rows and a full pass is not the normal
  operation.
- **Capped**, and it reports what it did — how many were re-decided, how many
  changed, per decision. `policyAt` doubles as the progress marker, so a run
  that stops at its cap continues where it left off instead of chewing the same
  head again.
- **A queue moves only when the decision flips.** Not "when the status is
  `SKIPPED`", which sounds equivalent and is not: an article skipped because it
  is already in the pivot language, and one taken out of the body queue by hand,
  both carry `ACCEPT`. A run that finds `ACCEPT` again therefore leaves them
  alone, and only a real flip moves anything.
- **Never `DONE`.** A translation that exists is not made wrong by a rule
  change, and re-queueing it would pay for it twice.

This also clears a standing backlog. Everything ingested while `pivotLanguage`
was unset is `SKIPPED` for ever, and there is currently no way to bring it
back; re-evaluation is that way.

## 8. Where it stops

- **No subject-region filtering.** `contentLocation` does not exist
  ([geo.md](geo.md) §3.2), and the `region` type is honest about being origin.
- **No priority, no ordering, no rule expiry.** §2.1 explains what those would
  cost; if a rule should stop applying, disable or delete it.
- **No per-article exception through rules.** `skipContent` stays what it is —
  an explicit act on one article. A rule that names one article is a rule that
  will be forgotten.
- **This is not a query filter.** It decides what to *spend money on*, not what
  a client is shown. The article API keeps its own filters, and none of them
  read these rules.
- **No automatic learning.** Unlike the category table, nothing here proposes
  rules from observed data. What that would even optimise for is not clear
  enough to design against.
