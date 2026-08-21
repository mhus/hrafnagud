# Console

## 1. Purpose

Three questions, one page: **is it collecting**, **how much**, and **is what it
collected any good**. Everything in the console exists to answer one of those;
anything that answers none of them was left out.

It is not an administration UI. No delete, no re-queue, no source editing, no
creating anything. Those verbs exist in the API and are one `curl` away, and
putting them behind a button that a mis-click reaches is a different decision
from showing what is going on. Should they ever be wanted here, they arrive
with a confirmation step and a reason, not as a convenience.

**Two subsystems are exceptions**, written down rather than quietly taken. The
Kataloge view (§4) can switch a catalogue on or off and re-read it now, and the
Einstellungen view (§4a) can change an operational value. The line is not "no
writes" but *which* subsystem the console operates — every one of those actions
is reversible with the same click and none destroys anything, whereas deleting
an article or editing a source costs data. Switching a catalogue on is also the
point: catalogues ship disabled, so this is where a fresh installation is told
what to collect.

## 2. Shape

Static HTML and plain JavaScript at `classpath:/console/`, served at
`/console/` with `/` redirecting there. No build step, no bundler, no
framework: the console reads a handful of endpoints and renders one view per
subsystem, and a framework would be more machinery to install than the code it
replaced. It has to stay readable to whoever is debugging an ingest problem at
the time.

Bootstrap 5 comes from a CDN. That is the one deliberate trade in it: the
console needs internet access in the *browser* even where hrafnagud itself has
none, in exchange for not carrying ~60 KB of CSS in the JAR. Vendoring it is a
one-file change if that trade ever stops being right.

It lives outside `src/main/resources/static/`, which Spring Boot serves
unconditionally — a console kept there could not be switched off, and "off"
has to mean *not served*, not *served but please do not use it*
(`munin.api.consoleEnabled`).

## 3. Authentication

`munin.api.token` guards `/api/v1/**` through `ApiTokenInterceptor`: an
interceptor rather than a check per controller, because four controllers and
two dozen methods would be two dozen chances to forget the next one. Constant
time comparison, `401` plus `WWW-Authenticate: Bearer` on refusal, and never a
body — the party being refused is the last one that should learn which half of
its credential was wrong.

**Empty means no check.** That is how every installation of this service has
run so far, and a version that starts answering 401 to the operator's own
scripts would be a worse surprise than an unguarded API behind a loopback
binding. The startup log states which of the two states the instance is in,
because the alternative is testing it against a live endpoint and the wrong
answer is the quiet one.

Three paths stay outside the guard, each for its own reason:

| Path | Why |
|---|---|
| `/console/**`, `/` | Holds no data and no credential. Asking for the token to reach the page that asks for the token is a loop, not a security measure. |
| `/actuator/**` | The container health check calls it without credentials; a 401 there reads as an unhealthy container. |
| `/ode/**` | Has its own keys, issued to a brain rather than to a person, guarded by the Ode auto-configuration. |

**Not the Ode guard**, although `vance-ode-core` ships an equivalent and this
duplicates about thirty lines of it: using it would put `de.mhus.vance` on
Munin's import list, and Munin not depending on Vancetope is the one hard rule
of [architecture.md](architecture.md) §2.1. Thirty lines is what that costs.

The token is typed into the console and kept in `sessionStorage` — gone when
the tab closes — moving to `localStorage` only if the operator ticks
"remember". A token that can delete articles does not get a default that
outlives the session.

## 4. What each view answers

**Overview** — the verdict first, the numbers under it. The health block turns
six figures into sentences: no enabled source at all, no new article for over
three hours, a quarter of the registry failing, more failed body fetches than
successful ones, more than a fifth of articles with no language. Reading those
figures and deciding whether they are fine is exactly the work the page should
be doing instead of the operator.

Ages are computed against `MuninStatsDto.serverTime`, not the browser clock. A
laptop with a skewed clock would otherwise report "newest article in 3 h".

**Sources** — the registry with its failures: last outcome, consecutive
failures, article count, next poll. The health block's failing count links
straight into this view with `failing=true` set, because a number an operator
cannot resolve into rows is a number that only creates work.

**Catalogues** — where the source lists come from, what each catalogue last
did, and the three controls the console has: a switch per catalogue, its
interval class, and "re-read now". Catalogues ship **disabled** ([catalogs.md](catalogs.md) §7),
so the switch is how a fresh installation starts collecting at all; the button
is for when waiting for the next scheduled pass is the wrong answer. The card
says what "off" means, because a switch alone does not: no automatic reads, no
new source lists, and everything already imported stays.

The interval class is a select rather than a text box — the profile name is the
one field nobody can guess, and the classes are configured server-side
([collection.md](collection.md) §6.1a). Each option shows its window (`news
(5 min – 12 h)`), and the card states that a change applies to what is
imported from now on: rows already in the registry keep the class they were
created with, and a dropdown that silently did not apply to them would be
worse than one that explains itself.

**Einstellungen** (§4a) — see below.

**Articles** — what was collected, filtered by source, language, **origin**,
body state, text and time window. The origin column and filter read the
publisher's place path: picking *Asia* finds every article from an Asian
publisher, because the article stores the whole containment chain rather than
just a country. Names come from `GET /api/v1/places` — the article carries
ids, since a display name depends on the reader's language and is not a
property of the article. The dialog spells the chain out and says what it is
not: the publisher's seat, not the subject. See [geo.md](geo.md). The detail dialog is where data quality is actually
judged: the extracted body verbatim, its word count, the language and how it
was determined, the translation if one exists, and the error strings when a
step failed.

### 4a. Einstellungen

One row per declared setting, grouped by the section of its key, edited in
place. There is nothing behind a row that the row does not already show — key,
what it does, the value, the default, which of the two is in force — so a
dialog would only add a click.

Three things it shows that a bare list of values would not:

- **Where the value comes from.** *Konfiguration* or *geändert* plus the time,
  because "did somebody change this" is the question an operator actually
  arrives with, and the answer has to outlive whoever made the change.
- **The default, next to the current value.** That is what makes
  *Zurücksetzen* a decision rather than a guess; it deletes the override and
  the configured value takes over again.
- **What is not here.** The page says in one line that start-up values — tick
  cadences, proxy, token, the Vancetope endpoint switches — live in the
  configuration. Without it, their absence reads as a broken page rather than
  as a boundary (see [settings.md](settings.md) §3).

Booleans render as a select rather than a text field. They are what an operator
comes here to change in a hurry — *stop fetching bodies* — and a free-text box
that accepts `ture` and keeps the old value is the wrong thing to hand somebody
in that moment. The API refuses it either way; the select makes it
unavailable.

## 5. Two API details the console had to respect

**Counting is optional.** `GET /articles` returns `total: -1` unless
`count=true`, because an unfiltered count over a multi-million-row collection
is a full scan. The console therefore never counts on a page turn: "next" is
offered whenever the page came back full, and the total is a button. One
wasted request at the end of a list beats a scan on every turn.

`GET /sources` does count, unconditionally — the registry is thousands of rows,
not millions, and the difference is what makes the two pagers behave
differently on purpose.

**`failing` was added for this.** `MuninStatsDto.sourcesFailing` existed with
no way to list the sources behind it. The filter uses exactly the predicate
`countFailing()` uses (`consecutiveFailures > 0`); two definitions of "failing"
would let the overview report a number that its own link cannot reproduce.

## 6. Untrusted by construction

Every string the console renders came from a feed the operator does not own:
titles, teasers, author names, category labels, error messages, URLs. All of it
goes through `esc()` or `textContent`, never raw into `innerHTML`, and every
`href` through `safeUrl()`, which passes `http`/`https` and drops everything
else — a `javascript:` URL in an article link is one click from executing with
the token in scope.

The extracted body is rendered as pre-wrapped **text**, not as HTML. The point
of looking at it is to judge the extraction, including its line breaks; rendering
a publisher's markup would both hide extraction faults and hand the page to
whoever wrote it.

## 7. Where it stops

- **Read-only apart from a catalogue's switch, its interval class and re-read,
  a category mapping, a filter rule, and an operational value** (§1).
- **Settings have no history in the view.** A changed value shows when it was
  written, not what it was before or who wrote it — the record does not exist
  (see [settings.md](settings.md) §7).
- **No charts.** A time series of ingest volume would answer "how much" better
  than a 24-hour figure does, and it needs a metrics store; the actuator
  already exposes Prometheus.
- **No source-list view.** Catalogues are visible and sources are visible; the
  lists between them are managed by the catalogue layer and by the API, and
  nothing about them was needed to answer the three questions.
- **No catalogue editing beyond the switch.** The filter that decides how much
  of a directory this installation pulls in is a `PUT` away in the API, and it
  belongs where it survives — on the catalogue, not in a view (see
  [catalogs.md](catalogs.md) §4). Registering and deleting catalogues stays in
  the API too.
- **No pagination beyond next/previous.** Jump-to-page needs a total, and the
  article endpoint deliberately does not have one.
- **One token, no accounts.** Everyone who has it can do everything.
