# Settings

Operational values live in the database and can be changed while the service
runs. This is the layering that makes that safe, and the boundary that keeps it
honest — because not everything hrafnagud is configured with *can* be changed
at runtime, and pretending otherwise would be worse than not offering it.

## 1. Two layers, one name

A value comes from one of two places, and they are asked in this order:

```
settings collection          an operator changed it here
    ↓ absent or blank
MuninProperties              application.yml, HRAFNAGUD_*, code default
```

The keys are **the property names unchanged**. `munin.feed.batchSize` in the
YAML is the default for the setting called `munin.feed.batchSize`; there is no
second vocabulary to learn and no mapping table to keep in step.

Three roots, one per layer, so a key names what owns the value: `munin.*`
collects and stores, `hugin.*` hands text to a model, `hrafnagud.*` is what
belongs to neither (today only the settings layer itself). One property class per
root — `MuninProperties`, `HuginProperties`, `HrafnagudProperties` in `config/` —
because a class holding all three would be back to a name that claims a layer it
does not own. The one seam runs through category normalisation and is deliberate:
`munin.category.acceptConfidence` tunes the local table match at ingest, while
`hugin.category.*` is the worker that asks a brain about what the match could
not settle.

Only overrides are rows. A setting nobody has touched has no document at all,
which is what makes *back to the default* a delete rather than a second copy of
the configured value — a copy would drift the moment somebody edited the YAML.

**A blank override counts as no override.** One gesture — clear the field —
means the same thing everywhere, and the cost is that a setting whose default is
non-empty cannot be set to the empty string. The one place that would matter,
`hugin.translation.pivotLanguage`, has an empty default already, so its "off" is
reachable by deleting the row. A blank `PUT` is therefore refused with a message
pointing at `DELETE`, rather than silently storing a row that resolves to the
default and then reads, in the console, as a change somebody made.

### 1.1 Why the database wins

The other arrangement — seed the collection from the YAML at first boot and let
the database be the only truth afterwards — is tidier to describe and worse to
operate: a `HRAFNAGUD_*` variable in a Kubernetes manifest would be silently
ignored from the second start onwards, which is the kind of surprise that costs
an afternoon. So the configuration keeps working as the layer underneath, and
the price is the opposite surprise: editing the YAML has no effect while an
override exists. That one is paid for by showing, per value, which layer it came
from — the console's *Herkunft* column and the API's `source` field.

## 2. A handle, not a value

Every setting the code reads is declared once in `Settings`, and what a
consumer holds is a `Setting` handle:

```java
private final Settings.Feed config;               // in the constructor
...
sourceService.claimDue(now, config.batchSize().value());   // at the moment it matters
```

The handle is the whole mechanism. Consumers here are long-lived singletons — a
tick, a service, a fetcher — and every one of them used to take its numbers in
its constructor, which is precisely why changing one meant a restart. Moving the
read to the call site needs no listener, no refresh scope, no proxied bean and
no second wiring path to get wrong; a change is visible on the next read.

Resolution is cheap because the whole collection is held as one immutable
snapshot with a **generation** counter that only moves when the values actually
differ. A handle re-parses when the generation changes and not otherwise, and
anything that derives something more expensive from settings can watch the same
counter instead of rebuilding per call.

`Settings` is also the only place a key exists. A declaration carries the
type, the default and the sentence an operator reads next to it, so there is no
way to half-define a setting — and a `PUT` to an undeclared key is a 404 rather
than a row nothing will ever read. Declaring one key twice fails at startup.

## 3. What stays a start-up property

A value is *not* a setting when nothing would read it later. Three groups:

| Not a setting | Why |
|---|---|
| `server.port`, `spring.mongodb.*` | the settings live in that database |
| `munin.*.tickInterval`, `munin.*.initialDelay` | baked into `@Scheduled` when the bean is built |
| `munin.http.connectTimeout`, `munin.http.proxy.*` | baked into the one shared `HttpClient` |
| `munin.api.consoleEnabled` | it decides whether an HTTP surface exists; a controller cannot be unregistered at runtime |
| `munin.catalog.installBundled` | only ever applies to an empty database |
| `munin.language.lowAccuracyMode`, `munin.language.languages` | the detector's models are loaded once, and hold gigabytes when they are not narrowed |
| `munin.feed.profiles` | structure rather than a number: adding an interval class is a deployment |
| `hrafnagud.settings.refreshInterval` | a setting that decided how settings are read would be a loop |

These are deliberately **absent from the settings API** rather than present and
marked read-only. A key that can be stored but never read is a trap, and the
console says in one line where they are instead.

The tick **cadence** stays a property while the tick's **switch** is a setting,
and that split is the one worth spelling out: `munin.feed.enabled` used to be
`@ConditionalOnProperty` on the tick, which is the strongest form of off there
is — the bean does not exist. It also made turning a worker on a restart of the
collector, and restarting a collector means bunched-up poll times and leases
waiting to expire. The switch is now asked at the start of each round through
`WorkerSwitch`, which logs the answer whenever it changes, so a worker that
stopped collecting appears in the log rather than being inferred from a graph
going flat.

## 4. When a change takes effect

Three answers, and the difference is not cosmetic:

- **Switches and counts** — `enabled`, `batchSize`, `maxAttempts`, thresholds:
  at the start of the next round. A round already in flight finishes with the
  values it started on.
- **Interval bounds** — `defaultInterval`, `minInterval`, `maxInterval`,
  `maxFailureInterval`: the next time a source is *rescheduled*. The poll times
  already written to `sources.nextFetchAt` stay as they are until each source
  comes round, so widening a ceiling looks gradual. Nothing rewrites the
  registry, because a settings change that touched a million rows would be a
  migration wearing a text box.
- **`pivotLanguage` and `readableLanguages`** — at ingest, for articles
  arriving from now on. Articles already stored keep the decision they were
  given; the way to apply a change to them is a filter re-evaluation, which asks
  the same function (see [translation.md](translation.md) §3.2).

## 5. Secrets are not settings

`munin.api.token` and the two Ode keys stay in the environment, where the
deployment already keeps them, and there is no encrypted setting type.

Moving them here would mean a master key to manage, a second place a credential
can leak from, and a rotation path through a web form — in exchange for
convenience nobody needs, because a token is rotated about as often as the
service is deployed. Vancetope's settings system does carry encrypted types, and
the difference is that a brain reads provider keys per tenant at runtime;
hrafnagud has three secrets and one operator.

`SettingType` therefore has value types only. If a real need for a stored
secret appears, the honest version is AES with a key from the environment and a
write path that refuses to store anything when that key is unset — not a masked
field over cleartext.

## 6. Reading and writing

```bash
curl localhost:9800/api/v1/settings                     # the catalogue
curl localhost:9800/api/v1/settings/munin.feed.batchSize
curl -X PUT localhost:9800/api/v1/settings/munin.feed.batchSize \
     -H 'Content-Type: application/json' -d '{"value":"40"}'
curl -X DELETE localhost:9800/api/v1/settings/munin.feed.batchSize   # back to the default
```

Each entry reports the effective value, the default, and which of the two is in
force. Both are reported because the question an operator actually has is "did
somebody change this, and what was it before", and the answer has to outlive the
person who made the change.

The value is **text**, and the type belongs to the key rather than to the
request. A caller that could declare a type could declare a duration to be a
string, and the mismatch would surface in a worker at three in the morning;
instead the value is parsed against the declared type before anything is stored,
so `PT5X` is a 400 at the API.

**The type carries a range where the type alone would not be enough**, because
"parses" and "a worker can use it" are not the same thing, and the gap is
occupied by exactly one value: zero.

- A count (`INT`, `LONG`) must be positive. `munin.feed.batchSize: 0` claims
  zero sources per round, for ever, while `WorkerSwitch` keeps logging the
  worker as on — a service that has stopped doing anything and says it is fine.
  `hugin.translation.maxSourceChars: 0` is worse than idle: it truncates every
  title to nothing, and the article is then charged its whole attempt budget for
  having no title to translate, so a single write empties the backlog into
  `FAILED`.
- A fraction (`DOUBLE`) must be between zero and one, and finite. `NaN` and the
  infinities parse happily and then poison every comparison they reach without
  ever throwing; a confidence above one is compared against a score that cannot
  reach it, and a temperature outside the range is rejected by the provider one
  call later.
- A duration must not be negative — the oldest of these checks, and the model
  for the rest.

The range lives in the `Setting` subclass rather than at each declaration,
because it is a property of what the type *means* here: every whole number in
this catalogue is a count of something to do. A setting that genuinely wanted
zero would be a different type, not an exception.

The console renders the same catalogue under **Einstellungen**, grouped by
section, editing in place. That is within what the console is for — it changes
what an operator is looking at while looking at it, and every change is
reversible with the same click (see [console.md](console.md) §1).

## 7. Where it stops

- **No history.** A row carries `updatedAt` and the current value; what it was
  before, and who changed it, is not recorded. The log line for the write is the
  only trace, and it is not queryable.
- **No validation across settings.** Each value is checked against its own type
  and range (§6), and nothing checks a pair: `minInterval` accepts a value above
  `maxInterval`, and the result is the one you would expect. Bounds per
  individual key — a batch size capped at some sane maximum, say — are a
  plausible next step and are not there either.
- **An undeclared override is reported, not removed.** A key nothing declares —
  most often one renamed in a release — is named in the log once at startup and
  once whenever it first appears, and then left alone. Deleting somebody's
  stored value because this build does not recognise it is the more destructive
  reading of the same fact.
- **Reload is a poll.** Writes through the API are visible at once because the
  write path reloads; an edit made straight in the database is picked up within
  `hrafnagud.settings.refreshInterval` (30 s). A second instance therefore converges
  rather than sharing state — which matches the single-instance assumption in
  [architecture.md](architecture.md), and would need to be revisited before it
  stops being true.
- **A stored value the code no longer declares is ignored**, with a warning per
  reload naming the key. Renaming a setting in a release leaves the old row
  behind, and nothing deletes it for you.
- **Descriptions are English**, like the rest of the code, while the console
  around them is German. The alternative is a user-facing string in the source
  that breaks the repository's one-language rule; this way the mixture is
  visible instead of the rule being.
