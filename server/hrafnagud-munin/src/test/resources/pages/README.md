# Extraction fixtures

Pages for developing and regression-testing `ContentExtractor`.

**These are synthetic, not saved copies of real pages.** They reproduce the
*structures* found on real news sites — JSON-LD blocks, semantic containers,
lazy-loaded images, consent banners, related-story rails, paywall markers —
with placeholder prose written for this repository.

That is a deliberate choice over checking in real pages:

- a saved news page is a copy of a copyrighted article;
- real pages are 200–500 KB each, mostly minified script and inline CSS,
  which makes a diff useless and the repository large;
- they rot — the site relaunches and the fixture no longer represents it,
  but nothing tells you.

What matters for extraction quality is the *shape* of the markup, and that
is what these reproduce. Each file names the structure it covers and the
rung of the ladder it should land on.

When a real page extracts badly, the fix is to reduce it to its structural
skeleton, add it here with placeholder prose, and assert the expected
outcome — not to paste the page in.

| File | Language | Structure | Expected rung |
|---|---|---|---|
| `de-jsonld-full.html` | de | JSON-LD with `articleBody`, figure + figcaption, lazy `srcset` | `json-ld` |
| `en-semantic.html` | en | JSON-LD metadata only (no body), semantic `<article>` | `semantic` |
| `fr-paywall.html` | fr | teaser plus paywall marker and phrase | `semantic`, gated |
| `es-lazy-images.html` | es | placeholder `src` with real `data-src` | `semantic` |
| `ja-graph.html` | ja | JSON-LD wrapped in `@graph` | `json-ld` |
| `ru-scored.html` | ru | no semantic markup, heavy navigation | `scored` |
| `it-type-array.html` | it | `@type` as array, `image` as object | `json-ld` |
| `de-chrome.html` | de | German chrome classes (`werbung`, `teilen`, `kommentare`) | `semantic` |
| `pt-bare.html` | pt | plain `div` soup, no metadata at all | `scored` |
| `en-short-jsonld-body.html` | en | JSON-LD `articleBody` too short to be the article | `semantic` |
