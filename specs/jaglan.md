# Jaglan mount

> The archive as a mounted file tree: article texts as Markdown, stored images
> as bytes, addressable as `vance:/_ext/hrafnagud/…` from a brain document.
>
> The Vancetope side is `../vance-wb/specification/public/jaglan-system.md`.
> This document is only about what this repo decides: how the tree is laid out
> and what a path means.
>
> **Status:** the addressing is built (`jaglan/ArchivePath`). The serving side
> — the `vance-ode-jaglan` endpoint, the Markdown rendering, caption search —
> is not.

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

## 2. The tree is partitioned by hour

```
_ext/hrafnagud/article/2026/08/21/14/68a7c1f2e4b09d3a5c6e7f80.md
_ext/hrafnagud/img/2026/08/21/14/276a1ac0…44ab9d9.jpg
```

**A flat directory is not an option**, and not for tidiness. A listing is not
free on the Vancetope side: it writes one metadata row per file, and Jaglan's
contract insists a folder's count be honest or absent. Several hundred
thousand hash-named files in one folder satisfies that formally and is
unusable.

The depth comes from measurement (`images.md` §4: 3,000–5,000 articles a day):

| Folder | Entries in one listing |
|---|---|
| `article/` | one per year |
| `article/2026/` | 12 |
| `article/2026/08/` | 31 |
| `article/2026/08/21/` | 24 |
| `article/2026/08/21/14/` | ~200, peaking around 400 |

Per **day** the leaf would hold four to five thousand, which is the problem
this exists to avoid. Per hour every listing in the tree is small, and the walk
down to a leaf is four listings of at most 31 entries.

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

`ArchivePath.parse` is strict: six segments, a known subtree, a numeric
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

## 3. What the mount will not do

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
