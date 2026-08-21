# Images

> How the archive keeps copies of the images an article references, and why
> that is optional, per-image and off by default.
>
> Extraction — which images an article has, and which one is the lead — is
> `content-extraction.md` §4. This document is about the bytes.

## 1. The problem

An article's text survives in the archive. Its images do not: they are
references into publisher CDNs, and those rot faster than anything else about
a news story. A relaunch, a CDN migration, a retention policy at the
publisher's end, and a stored article renders with holes — while the archive
itself never noticed anything happening.

Storing URLs and not bytes was the original decision, and it was made for two
reasons at once: storage and copyright (`ArticleImage`,
`content-extraction.md` §4). Only one of them is settled by choosing to store
— the archive is a private knowledge base, so re-serving what it collected is
not a publication — and the other one is a number, which §4 puts on the table.

## 2. The model

**A copy is optional and per-image.** `munin.image.enabled` decides whether
bytes are fetched at all; `munin.image.leadOnly` narrows it to the article's
representative image. An image that was never queued, is still pending, or
failed permanently keeps being referenced by its publisher URL — exactly the
behaviour that existed before this subsystem. Nothing in the archive depends
on a copy existing.

That is what makes the whole thing switchable rather than a migration: turning
it off stops the traffic, and what has already been stored stays usable.

**The bytes live in the document.** Not GridFS. GridFS earns its complexity
above the 16 MB document limit and for range reads into large files, and a
news image is neither — the largest in a sample of mainstream outlets was
384 KiB, and `munin.image.maxBytes` caps it at 4 MiB. A second storage
mechanism with its own bucket, chunk collection and indexes would buy nothing.

The consequence is a rule rather than a footnote: **a query that does not need
the bytes projects them away.** `ImageService.stat` and `claimDue` do; `load`
is the only method that returns an image file, and it is meant to be called
once per served request.

### 2.1 The address is derived from the URL

`id = sha256(url)`, hex. Not a generated id, and the reason is the same one
Jaglan gives for its own derived ids: whoever holds an image URL must be able
to compute the record's address **without** a lookup. That turns "does the
archive have this image?" into a primary-key read, which is what a serving
path needs when it decides between a local copy and the original link for
every image it renders.

The URL is hashed exactly as extraction stored it. Normalising here would be a
second, invisible normalisation rule, and the two drifting apart would leave
images stored under an id nothing computes.

### 2.2 No byte-level deduplication

`contentHash` is recorded and not unique; identical bytes under two URLs are
stored twice.

Measuring is why. The intuitive saving — one agency photo carried by fifty
outlets — is not there: each outlet re-encodes its own crop, so the files are
not byte-identical between publishers. What does repeat is one publisher
reusing a photo and an article being re-extracted, and both of those are
already collapsed by the URL-derived id. Refcounting a shared blob would be
real complexity bought for a saving that does not exist.

## 3. The queue

A status field with a partial index, like every other queue here
(`architecture.md` §4.1): `image_queue_idx` on `{status, nextAttemptAt}`
filtered to `PENDING`. Claims are leases — claiming pushes `nextAttemptAt` out
— so one field is both the schedule and the lock.

Three states and no fourth: `PENDING`, `STORED`, `FAILED`. Notably absent is a
"not wanted" state. An image the current settings exclude is **not queued at
all**, so relaxing `leadOnly` later picks images up from the articles collected
from then on, rather than leaving a stratum of records that say "skipped under
rules that no longer apply".

**Giving up is cheap here**, cheaper than anywhere else in the collector: the
article keeps referencing the publisher URL, so a `FAILED` image costs the
independence from that URL and nothing else. There is no reason to keep
hammering a host for one.

### 3.1 Queueing happens only while the feature is on

The opposite of how article bodies work — ingest queues every article whether
or not the body fetcher runs, deliberately, so that switching the fetcher on
later works through the backlog.

The difference is what a queue entry costs. For an article it is a status field
on a document that exists anyway. For an image it is a **new document per
image**, and creating millions of them for a switched-off feature is storing a
decision rather than recording a fact.

Nothing is lost by it, because the image list is kept on the article's content
document: what was extracted while copying was off can be queued from stored
data, with no queue record needed in advance.

## 4. What it costs

Measured, not estimated — `og:image` across four mainstream outlets: **median
94 KiB, mean 111 KiB, largest 384 KiB**. Article volume in steady state, from
the publication-date distribution of a fresh archive: **3,000–5,000 a day**
for ~800 sources (the initial fill is several times that and is one-off — feed
windows reach back years).

| Scope | per month | per year |
|---|---|---|
| lead image only (~80 % of articles have one) | ~9–13 GiB | ~110–160 GiB |
| lead plus inline images | 30–50 GiB | 350–600 GiB |

So `leadOnly` is not a nicety, it is the difference between a subsystem that
fits on a disk and one that does not. Anything finer belongs in the filter
rules (`filter.md`), and retention is a decision this document does not make:
nothing here deletes an image, and at the volumes above that is a choice worth
taking consciously rather than by default.

Which is why the numbers are **reported rather than merely computable**.
`/api/v1/stats` carries `imagesByStatus` and `imageBytesStored`, and the console
grows a card for them as soon as anything has been queued — the copies live in
MongoDB documents, so this is database volume, and an operator who turned the
switch on and cannot see the gibibytes accumulate has been handed a bill with no
statement. The card is absent while nothing is queued, which keeps a feature
that is off out of a row built for six.

## 5. Fetching

Through the shared `HttpFetcher` (`collection.md` §6.3), so an image request
takes the same per-host pacing, user agent and proxy as everything else that
leaves the service. Adding a fourth kind of outbound traffic did not need a
fourth piece of politeness.

**`robots.txt` is not consulted**, consistent with the existing split — obeyed
for article pages, not for feeds. An image is a subresource of a page we were
already allowed to fetch, which is the relationship a browser has to it, and
browsers do not consult robots for subresources either. What does apply is the
per-host pacing, which is the part a publisher notices.

**What counts as a storable image** is one function (`ImageFetchService.reject`)
and it is deliberately narrow: `image/{jpeg,png,webp,gif,avif}`, non-empty,
within the size cap. The failure mode it exists to prevent is not an error but
a success — a consent wall or an error document arriving behind an `<img>` URL
and being stored under `image/*`, to be served back later as somebody's lead
photo. SVG is excluded for a different reason: it is a document that can carry
script and remote references, and serving one from our own origin is not the
same act as serving a JPEG.

## 6. Where this stops

- **Nothing deletes an image.** No TTL, no retention sweep. See §4 — the
  numbers are the argument for deciding this deliberately, not for a default.
- **No resizing, no re-encoding.** Bytes are kept as the publisher served
  them; a derived thumbnail is a different feature with a different cost.
- **No image serving endpoint** in Munin. Copies exist to be handed to
  something that addresses them — see `jaglan` — and an operator API that
  streams image files would be a second, unrelated surface.
- **No backfill command.** Articles whose content was fetched while copying was
  off have their image list stored and can be queued from it; the walk that
  does that is not built.
