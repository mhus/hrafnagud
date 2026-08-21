# Jaglan mount

> The archive as a mounted file tree: article texts as Markdown, stored images
> as bytes, addressable as `vance:/_ext/hrafnagud/…` from a brain document.
>
> The Vancetope side is `../vance-wb/specification/public/jaglan-system.md`.
> This document is only about what this repo decides: how the tree is laid out
> and what a path means.
>
> **Status:** built and serving. `vance-ode-jaglan` is pinned at
> `0.2.0-SNAPSHOT` — the module arrived after 0.1.0, and until 0.2.0 is on
> Maven Central the image built from it is not reproducible. Image bytes need
> `munin.image.enabled`; article text needs nothing.

## 1. Why a mount and not a third feed

Vancetope's three foreign-source contracts ask three different questions:
"what is there about this?" (Zarniwoop), "what is new?" (Centauri), and "give
me *these* bytes under *this* path" (Jaglan). This archive already answers the
first two. The third is the one it cannot, and the reason is worth stating:
Centauri and Zarniwoop deliver *entries* that a brain carries home, while a
mount delivers a **path that means the same file tomorrow**.

That difference decides what is worth mounting. Article images are the obvious
candidate because they are the only bytes in the archive and, referenced by
publisher URL, the first thing that rots. Article text is the cheap one: it is
already stored, so a mount costs a rendering function and no storage at all.

Jaglan's own rule — "a source with changing ids is a search source, not a
mount" — is satisfied by both: an article id and an image's `sha256(url)` are
permanent.

## 2. The tree is partitioned by minute

```
_ext/hrafnagud/article/2026/08/21/14/37/68a7c1f2e4b09d3a5c6e7f80.md
_ext/hrafnagud/img/2026/08/21/14/37/276a1ac0…44ab9d9.jpg
```

**A flat directory is not an option**, and not for tidiness. A listing is not
free on the Vancetope side: it writes one metadata row per file, and Jaglan's
contract insists a folder's count be honest or absent. Several hundred
thousand hash-named files in one folder satisfies that formally and is
unusable.

The depth comes from measurement, and the first answer was wrong. Steady state
is 3,000–5,000 articles a day (`images.md` §4), which makes an hourly folder a
comfortable couple of hundred — so hourly is what this said at first. Then the
leaf listing got built, and the real distribution was measured on an archive
that had been filling for three days:

```
busiest hour    20,229 articles      ← the initial fill
busiest minute   2,009
steady state         ~3 per minute
```

**Arrival is bursty.** Filling a fresh archive means every source delivering
its whole feed window at once, and hourly folders put twenty thousand entries
into one listing — twenty thousand metadata rows written on the reader's side
from one click.

| Folder | Entries in one listing |
|---|---|
| `article/` | one per year the archive holds |
| `article/2026/` | up to 12 |
| `article/2026/08/` | 28–31 |
| `article/2026/08/21/` | 24 |
| `article/2026/08/21/14/` | 60 |
| `article/2026/08/21/14/37/` | 2–4 steady, ~2,000 at an import peak |

The residue at the leaf is a property of bursty arrival, not of the layout: no
time partitioning removes it, it happens once per archive, and nothing forces
anyone to browse into that minute. What it does remove is the twenty-thousand
case.

### 2.1 The date is `firstSeenAt`, in UTC

Not the publication date, for a reason that outweighs how much more natural
publication order would read: **a path has to be immutable.**
`publishedAt` is publisher-controlled and a re-extraction can correct it — the
file would move and every link somebody saved would break. `firstSeenAt` is
written once, by us, and is always present.

It is also the denser axis. Feed windows reach back years, so the initial fill
of a fresh archive spreads over sixteen years of publication dates while being
one afternoon of collection.

UTC because a local zone would move paths: 23:30 UTC is the next day in
Berlin, and the same object must not live at two addresses depending on where
the service was restarted.

### 2.2 The id resolves, the date verifies

The last segment is the object's id, so resolution is a primary-key read and
never needs an index on the date.

The date segments are nevertheless **checked** against the object that comes
back. Without the check, one file answers under every conceivable date — and
since Jaglan derives its document id from `(mount, path)`, each spelling would
become a metadata row, which is to say a visible duplicate document in
somebody's tree.

### 2.3 Paths are rejected, not repaired

`ArchivePath.parse` is strict: seven segments, a known subtree, a numeric
partition of the right widths, and an id that is 24 or 64 lowercase hex
characters. Anything else yields nothing.

The alternative to rejecting a malformed path is resolving it to *something*,
which is the shape of every path-traversal bug there has ever been. The id's
shape is also what keeps a path segment from reaching a query as anything but
hex.

An image's extension comes from the **stored media type**, not from the source
URL — a CDN URL frequently has no extension or a wrong one, and a file named
`.jpg` holding PNG bytes is one some readers refuse. A media type the map does
not know yields no path at all rather than an extension-less name: the fetcher
stores only types that map knows, so a miss means the two have drifted apart,
and inventing a name would hide that behind a file nothing can open.

## 3. Every article is a file, not only the ones with a body

The feed alone yields a title, a teaser, the delivering sources, a language,
categories and a link. That is a document worth reading and citing; the
extracted body is an addition to it, not the precondition.

The alternative was considered and discarded on the evidence: a mount that only
shows articles whose body was fetched appears **empty** while
`munin.content.enabled` is off, and an empty mount reads as "this is broken"
rather than "that switch is off". A file that says
`_No article body has been fetched for this article._` says the second one.

Rendered as Markdown with YAML front matter, because the reader on the other
side is as often a model as a person: a fixed key/value block is cheaper to
consume than a prose header, and it keeps the body free of what is not the
article. Every front-matter value is publisher-controlled, so all of them are
quoted and escaped — a title beginning with `-` would otherwise turn a scalar
into a list, and a raw newline would end it early.

Both timestamps are in the block. `published` and `collected` disagree
routinely, and the second is what the file's own path is derived from.

## 4. Serving

**Rendered on demand, nothing cached.** An article's Markdown is derived data
with the archive as its single source of truth. A cache would be a second copy
to invalidate, and the things that change it arrive later by design: the body
tomorrow, a translation after that, an enrichment an hour on.

**Folders are computed, not stored.** Nothing in MongoDB says "there is a
folder for August". The levels come from the range of collection timestamps the
archive holds — two index reads for the edges, arithmetic in between, clamped
so a fresh archive shows no empty years. A folder that turns out to hold
nothing lists nothing.

That is what keeps a listing cheap enough to be **authoritative**, which the
contract demands: whatever a listing omits, the reader treats as deleted, so
returning a partial page to save time would look like deletion.

**The etag is a hash of the rendering.** The alternative was a timestamp, and
there is no single one that covers every change — a body arrives, a source is
added, a translation lands, each touching different fields. The bytes are in
hand anyway, since the size came from them, so the exact answer is also the
cheap one. For an image the etag is its content hash, which is exact and
already stored: image bytes never change under a path.

**Search is delegated**, over the same text index the research surface uses.
That is the whole reason `canSearch` is true: the alternative is the reader
walking a tree of half a million files to find a phrase.

### 4.1 Off by default, unlike the other two

`munin.jaglan.enabled` ships **off**, where `munin.centauri.enabled` and
`munin.zarniwoop.enabled` ship on. The difference is what the endpoint hands
out: those serve answers assembled from the archive, this one serves file
contents under paths a caller can walk. An unguarded path here is a file
server — the Ode module's own documentation says so — and a switch that started
on would make that the default state of a bare `java -jar`.

Enabling it without `vance.ode.jaglan.apiKey` logs a WARN naming what is
exposed. Being off is *not* announced: doing nothing is the closed state here,
and a service that logs about every feature nobody enabled buries the ones that
matter.

## 5. What the mount will not do

Fixed now because they follow from the contract rather than from scope:

- **Read-only.** `PUT` and `DELETE` answer **405** — a property of the source,
  not of the caller; 403 would send a reader looking for a credential problem.
- **`404` only when the archive really does not have it.** Anything else — a
  database that did not answer — is a 5xx. Jaglan deletes its metadata row on
  404, so confusing the two tells somebody their document no longer exists
  because Mongo hiccuped.
- **No `vance:` URLs in stored data.** An article's stored text keeps the
  publisher's image URLs; the rewrite to `vance:/_ext/hrafnagud/…` happens
  when serving. Munin's stored data carrying a Vancetope-specific addressing
  scheme is the one rule that does not bend (`architecture.md` §2.1) — and it
  is also what keeps the fallback per image: no local copy, no rewrite.
