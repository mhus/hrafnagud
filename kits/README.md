# Kits

The brain side of what `hugin/` calls. A [Vancetope](https://github.com/mhus/vance)
kit is a bundle of documents, settings and tools that a brain imports into a
project; these are the ones hrafnagud's outbound halves depend on.

They live here rather than in the kit collection next door because the two
sides are one contract. `OdeTranslationProvider` sends `{title, summary,
targetLang}` and reads `{title, summary, model}` back; the script in
`translation/` is what produces exactly that. When the payload changes, both
halves change in the same commit — which is not possible when they sit in
different repositories, and the failure mode is a translation queue that drains
into errors nobody can attribute.

| Kit | For |
|---|---|
| [translation](translation/) | `hugin.translation.*` — the event `OdeTranslationProvider` fires |

## Installing one

A kit is installed from a Git repository, so this repository is the source:

```bash
# in a Vancetope brain, for the project hrafnagud is configured against
kit install https://github.com/mhus/hrafnagud.git --sub-path kits/translation
```

Or add the whole repository to a tenant's kit catalogue, which reads every
directory under `kits/` and the `name`/`title` overrides in
[catalog.yaml](catalog.yaml):

```bash
anus project-kits update --tenant <tenant> --git https://github.com/mhus/hrafnagud.git
```

The exact commands belong to Vancetope and are documented there
(`specification/public/kits.md` §6.1 and `project-kits-catalog.md` §7 in the
workbench).

## The two halves have to agree

Three values span the boundary, and hrafnagud holds its side of each:

| Brain side (this kit) | hrafnagud side |
|---|---|
| the event's document name, `translate-article` | `hrafnagud.translate.event` |
| `auth.token` on the event | `vance.ode.events.translate-article.token`, from `VANCE_TRANSLATE_TOKEN` |
| the project the kit is installed into | `vance.ode.project` (plus `base-url` and `tenant`) |

The shipped token is a placeholder and every installation of the kit would
share it. Change it before first use; for anything reachable from outside the
deployment's own network, put it behind `tokenSetting` instead — the kit's
`kit.yaml` says how.

## Known gap

**`settings/translation.defaultTargetLang.yaml` is not read by anything.** The
script falls back to a literal `de` when a caller omits `targetLang`, so the
setting ships as documentation of an intention rather than as configuration.
Hrafnagud always sends its pivot language, so the fallback is never on its
path — which is why this was found by reading rather than by running. Wiring it
is one line in the script (`vance.settings.get("translation.defaultTargetLang",
"de")`, guarded against a null `vance.settings`), and it is deliberately not
done here: the chain can only be verified against a live brain, and that
belongs in the same change as the verification.

## What is not here

The near-copy under `qa/kits/translate-event-kit/` in the Vancetope workbench
stays there. It is the fixture for `TranslateEventLlmTest`, deliberately
independent of a sibling checkout being present, and test-shaped: a fixed
inline token, no settings. Two files that look alike and answer to different
owners — when the chain changes, both need touching.
