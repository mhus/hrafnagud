# Kits

> The two kits this service serves, and why they live in this repository
> rather than in a collection of kits.
>
> The Vancetope side — what a kit is, how it is installed, provisioning,
> authority levels — is `../vance-wb/specification/public/kits.md`. This
> document is only about what hrafnagud decides.

## 1. Why the kits are here

`kits/` has been in this repository from the start, with the reason written
into `CLAUDE.md`: a payload in `hugin/` and the recipe that answers it change
in the *same commit*.

That was a statement of intent, and nothing enforced it. The kit was installed
by hand, so the code here and the recipe there could drift, and the symptom
would be a translation that fires an event a stale script answers.

Serving the kits over `vance-ode-kit` turns the convention into a mechanism:
**the kit comes out of the same jar that fires the event.** A version skew stops
being unlikely and becomes impossible.

## 2. Two kits, opposite directions

| Kit | Configures | Contains |
|---|---|---|
| `hrafnagud-translation` | a brain to **answer** this service | the event, the script, the recipe |
| `hrafnagud-archive` | a project to **read** this service | endpoint and mount settings, plus a skill |

They are separate because their audiences are: one is installed into whatever
project `hugin.translation` points at, the other into any project that wants
the news. `OdeKitDeclaration#id` dispatches per bean, so two beans is simply
what two kits look like.

### 2.1 What the archive kit sets

Six settings, three surfaces, and the same shape each time — `.protocol` pinned
to `ode`, `.baseUrl`, and an `.apiKey` where one is configured:

```
centauri.endpoint.hrafnagud.*     "what is new?"
research.endpoint.hrafnagud.*     "what is there about…?"
jaglan.mount.hrafnagud.*          "give me these bytes"
```

Getting one of those wrong produces **no error** — an empty mount folder, a
research call that returns nothing. Which is the argument for the kit: an hour
was spent on exactly that failure, and the missing piece turned out to be a
switch on this side while the six settings were right.

`.protocol` matters more than it looks: without it the reader's factory skips
the endpoint silently. It is also the intended off switch, which is why the kit
sets it explicitly rather than relying on a default.

The kit carries **no recipes and no server-tool configs**. Reading the archive
is what the three surfaces already are; a recipe would be an opinion about what
to do with news, and that belongs to the project.

## 3. Addresses come from the reader, keys from us

**The base URL is not written by this service.** It travels as
`{{ accessUrl }}` and is substituted by the reader, using the address it
actually reached us on — a different one locally than in a cluster, and not
something this service can know about itself. A host that answered with an
address of its own choosing could also point a project somewhere else entirely,
which is why the Vancetope side does the substituting.

**The keys are the opposite case.** They are runtime configuration —
environment variables in a deployment — so they cannot be baked into a file in
the jar, and only this service knows them. `ArchiveKitSource` fills them in at
build time, which is precisely what implementing `KitSource` rather than using
`StaticKitSource` is for.

### 3.1 An unset key is omitted, not shipped empty

The rule worth knowing. A delivered `PASSWORD` is written when the project has
none and **never touched again** — that is what keeps a rotated key rotated.
Shipping an empty one would therefore install a permanent blank that no later
configuration corrects, and the operator would have no way to see it happened.

So a surface without a configured key contributes no file at all. A kit with no
keys is still a useful kit: the addresses and the skill are the bulk of it.

### 3.2 The revision covers the keys

`KitSource` requires the revision to move exactly when the bytes move, and the
bytes here are the classpath tree *plus* the keys. So the revision is the tree
hash folded with a hash of each key.

Without the fold, a rotated key would leave the revision standing still, the
reader's periodic check would report "nothing to do" forever, and the project
would keep a key that no longer opens anything. Folded rather than replaced,
because the tree's own hash still has to be in there — otherwise an edited kit
file would go unnoticed.

The keys are hashed, not folded in verbatim: a revision is handed out on a
cheap, cacheable call, and a secret belongs in the bundle behind a token rather
than in the answer that says whether the bundle changed.

## 4. On by default, and guarded by a key rather than by a switch

`munin.kit.enabled` ships **on**, unlike `munin.jaglan.enabled` and unlike the
two read surfaces. It costs nothing until a reader asks, and a kit that has to be
switched on before a project can configure itself is not much of an improvement
over configuring by hand.

The risk it carries is unchanged, and it is sharper here than anywhere else in
the service: **this endpoint hands out the keys to the others.** An unguarded
path gives away read access to the whole archive. What guards it is
`vance.ode.kit.apiKey`, not the switch — running without one logs a WARN at every
start, and that WARN is now the normal state of an unconfigured instance rather
than something you only see after opting in.

That is also the trade the whole design makes, and it is worth stating plainly:
the kit token becomes as valuable as the three read keys together. It is meant
to be — whoever may provision from the archive is already trusted to read it —
but it is a larger grant than a provisioning token looks like.

**The trade is written down rather than settled:**
[`planning/kit-secret-distribution.md`](../planning/kit-secret-distribution.md)
holds why one secret guards three, why encrypting the bundle with the kit token
would not be a second factor, and the two ways to split it that are real. Also
recorded there is the second place with the same shape — the translation kit's
inline token — because the two should be fixed in one go or not at all.

## 5. Packaging

`kits/` stays where it is and is copied into the jar at `process-resources`
(`maven-resources-plugin`), because the directory has two jobs: it is the
classpath content this service serves, and it is a git-based kit source in its
own right — `kits/<name>/kit.yaml` is the convention that lets the repository be
cloned and used directly.

`README.md` and `catalog.yaml` beside the kit directories are excluded from the
artifact. They describe the directory for a human; nothing reads them at
runtime.

## 6. Where this stops

- **The translation kit's token stays inline.** It is a field inside an event
  document, and rewriting a nested YAML value from here would be string surgery
  on a document somebody else authored. The kit documents how to move it to a
  `tokenSetting:` instead, which is the supported way and leaves the document
  alone. It is the one remaining place where the two sides can drift.
- **Nothing is provisioned from here.** Which projects get a kit is the
  reader's decision, written in its own `provisioning.yaml`, and the authority
  levels (`notify` / `update` / `manage`) live there too. This service answers;
  it does not push.
- **No signature.** For an `ode` host, author and deliverer are the same
  machine — the Vancetope side sets `signature: off` for this source type
  deliberately, and the trust anchor is the hand-written source entry.
