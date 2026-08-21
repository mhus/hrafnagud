# Kit endpoint: the read keys travel with the map

> **Status:** known and deliberately left as is (2026-08-21). Not a defect —
> a decision taken with the trade named. This file exists so the trade does not
> have to be rediscovered.
>
> Built state: `specs/kits.md` §3. Touching this means `kit/ArchiveKitSource`,
> `kits/archive/`, and `kits/translation/documents/_vance/events/`.

## The problem in one sentence

`hrafnagud-archive` ships the API keys of the feed, research and mount surfaces
inside the kit bundle, so **the kit token is worth as much as all three read
keys together** — one secret guards everything instead of two guarding each
other.

## Three properties, and only two are covered

| | who provides it | today |
|---|---|---|
| **Access** — who may ask at all | `vance.ode.kit.apiKey` | covered once set; a WARN names it when unset |
| **Transmission** — who may read along | TLS | **not** the kit token |
| **At rest** — where it ends up | `PASSWORD` setting, encrypted in the reader's database | covered |

The middle row is the gap, and it is narrow: on loopback it does not matter, and
inside one cluster network it is the same exposure the `Authorization` headers of
centauri, zarniwoop and jaglan already have. It stops being academic the moment
the brain and the archive sit on different hosts without TLS between them.

## Why encrypting the bundle with the kit key is not the fix

The obvious idea — encrypt the key material with the kit token so it is
"protected the whole time" — does not add a factor. The recipient needs the same
token to decrypt that it already needed to ask, so against a token holder it
protects nothing, and without the token there is no response to read along.
One secret in two roles is the key printed on the envelope.

It would help in exactly one case: **no TLS**. Then the token travels in a
cleartext header and the bundle in a cleartext body, and encrypting the body
would at least save the body. Worth knowing, but it is a patch for a missing
transport, not a distribution of trust.

## Two fixes that are real

### A. Keys out of the kit (recommended)

The kit carries `.protocol`, `.baseUrl` and the skill — a **map**. The read keys
reach the reader over the channel that already distributes secrets to both
sides: sops in `../mhus-infrastructure/loc1_ho/`, the same path the Gemini key
takes.

Then the kit token buys addresses and nothing else, and there is no secret in
the bundle to protect in the first place.

Cost: rotating a read key means deploying both sides. At one archive and a
handful of projects, that is how everything here moves anyway.

This reverses the decision taken when the kit was built, and the reason is worth
recording: the choice was framed then as "ship the keys or a human copies them",
and hand-copied keys travel through chats and screenshots. That framing was
wrong — the second automated channel exists, so "separate" never meant "by
hand".

### B. Wrap key (only if rotation has to be automatic)

The kit ships the read keys **encrypted** to a long-lived wrap key that the
reader received over sops. Card and PIN: the kit token opens the envelope, the
wrap key opens the contents.

The payoff is real — the archive can rotate its read keys and readers pick them
up on the next provisioning check, with no deployment on either side. The price
is a small piece of crypto machinery in a repository that deliberately has no
encrypted setting type (`specs/settings.md` §5), plus a key whose own rotation
is then the thing nobody does.

Not worth it yet. Worth it if the number of reading projects grows enough that
"deploy both sides" stops being a sentence somebody is willing to say.

## The same problem, second location

`kits/translation/documents/_vance/events/translate-article.yaml` carries its
token **inline** (`auth.token`). Card and PIN in one envelope, and the envelope
is a document in cleartext in the reader's database that operators edit.

The kit's own `kit.yaml` already describes the way out — `auth.tokenSetting:`
pointing at a `PASSWORD` setting — and nobody has taken it. Two reasons it is
still open:

- Substituting it from the host would mean rewriting a nested YAML value in a
  document somebody else authored. String surgery on foreign YAML is exactly
  what this repository's rules forbid.
- The value has to agree with `vance.ode.events.translate-article.token` on this
  side, which lives in the environment (`VANCE_TRANSLATE_TOKEN`) and never in
  the database. So the fix is the same sops handoff as A.

**Fix them together.** Distributing the archive keys while the translation token
stays in a document leaves the split as a façade at one corner.

## What would make this urgent

- The brain and the archive move to different hosts without TLS between them.
- The kit endpoint is enabled on anything reachable from outside the deployment
  network (today: `munin.kit.enabled` is off and no key is set in the cluster,
  so nothing is exposed).
- A third party gets provisioning access — at that point "trusted to read the
  archive" and "trusted with the archive's keys" stop being the same sentence.
