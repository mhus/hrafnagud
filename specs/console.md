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

**One exception**, written down rather than quietly taken: a catalogue can be
re-read from the Kataloge view (§4). It is idempotent, it is what the schedule
does by itself every day, and it creates nothing that was not going to be
created anyway. That is the bar an action has to clear to appear here.

## 2. Shape

Static HTML and plain JavaScript at `classpath:/console/`, served at
`/console/` with `/` redirecting there. No build step, no bundler, no
framework: the console reads four endpoints and renders four views, and a
framework would be more machinery to install than the code it replaced. It has
to stay readable to whoever is debugging an ingest problem at the time.

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
did, and the one button in the console: re-read this catalogue now. It is a
deliberate exception to §1, kept as narrow as the rule allows — re-read, and
nothing else. Everything the catalogue layer does happens on a schedule
anyway; the button is for the case where waiting for the next pass is the
wrong answer. See [catalogs.md](catalogs.md).

**Articles** — what was collected, filtered by source, language, body state,
text and time window. The detail dialog is where data quality is actually
judged: the extracted body verbatim, its word count, the language and how it
was determined, the translation if one exists, and the error strings when a
step failed.

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

- **Read-only apart from re-reading a catalogue** (§1).
- **No charts.** A time series of ingest volume would answer "how much" better
  than a 24-hour figure does, and it needs a metrics store; the actuator
  already exposes Prometheus.
- **No source-list view.** Catalogues are visible and sources are visible; the
  lists between them are managed by the catalogue layer and by the API, and
  nothing about them was needed to answer the three questions.
- **No catalogue editing.** The filter that decides how much of a directory
  this installation pulls in is a `PUT` away in the API, and it belongs where
  it survives — on the catalogue, not in a view (see
  [catalogs.md](catalogs.md) §4).
- **No pagination beyond next/previous.** Jump-to-page needs a total, and the
  article endpoint deliberately does not have one.
- **One token, no accounts.** Everyone who has it can do everything.
