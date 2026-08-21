# Deployment

Everything needed to build hrafnagud as a container image, push it to a
registry, and run it on Kubernetes. No state lives outside this directory —
the only thing you supply is one env file with credentials.

```
deploy/
  lib/common.sh            Shared helpers: paths, image coordinates, env-file
                           lookup, SSH-to-cluster-host access.
  secrets.env.example      Template for that env file. Copy, fill, chmod 600.
  docker/
    Dockerfile             Two stages: split the uber JAR into Spring Boot
                           layers, then assemble the runtime image.
    application-prod.yaml  Prod profile, baked in at /app/config/.
    docker-entrypoint.sh   JAVA_OPTS word splitting + exec, nothing else.
    build-image.sh         mvn install + docker build (or buildx per arch).
    push-image.sh          Tag + push; --multi-arch merges amd64 and arm64.
    docker-compose.yaml    hrafnagud + MongoDB, for trying the image locally.
    .env.example           Template for that compose stack.
  k8s/
    base/                  Namespace, ConfigMap, Deployment, Service.
    overlays/mini/         Sequential rollout + NodePort for the k3s Mini.
    render-secret.sh       Builds the Secret from the env file, prints a path.
    apply.sh               kubectl apply, locally or over SSH.
  mini/
    deploy.sh              build → push → apply → restart → route, plus
                           status / logs / forward.
    install-caddy.sh       Public route on the cluster host, opt-in.
    caddy/hrafnagud.caddy  That route's template.
```

## Quick start

```bash
# 1. Build the image (runs the Maven build first)
deploy/docker/build-image.sh

# 2. Try it out
cd deploy/docker && cp .env.example .env && docker compose up -d
curl localhost:9800/api/v1/stats

# 3. Publish it
cp deploy/secrets.env.example deploy/secrets.env && chmod 600 deploy/secrets.env
$EDITOR deploy/secrets.env
deploy/docker/build-image.sh --amd64
deploy/docker/build-image.sh --arm64      # add SKIP_MAVEN=1 to reuse the JAR
deploy/docker/push-image.sh --multi-arch

# 4. Deploy it
deploy/k8s/apply.sh                        # current kubectl context
kubectl -n hrafnagud port-forward svc/hrafnagud 9800:9800
```

## The image

`docker.io/mhus/hrafnagud`, single service, stateless. Listens on 9800, runs
as uid 1500, no writable path except `/tmp`.

The JAR is built on the host and copied in, not built in a builder stage:
hrafnagud depends on `de.mhus.vance.ode:*` artifacts that come from a sibling
checkout's `mvn install`, not from Maven Central. Install those first if the
Maven build fails on them.

It is copied in **as Spring Boot layers**, not as one file. The uber JAR is
~145 MB and ~138 MB of that is dependencies — Lingua's language models above
all. As a single `COPY` every code change would re-push all of it; extracted
into layers, a routine rebuild pushes the ~400 KB application layer and
leaves the rest in the registry's cache.

Cross-building needs a buildx builder with qemu. If `--amd64` on an Apple
Silicon machine fails with `exec format error`:

```bash
docker buildx create --use --name multi-arch
docker run --privileged --rm tonistiigi/binfmt --install all
```

## Configuration

Three layers, outermost wins: the bundled `application.yml` → the baked-in
`application-prod.yaml` → environment variables.

Non-secret values live in `deploy/k8s/base/configmap.yaml`, which documents
each one inline. The ones worth deciding before a first run:

| Variable | Default | Why you would change it |
|---|---|---|
| `HRAFNAGUD_USER_AGENT` | project URL | Give publishers a contact. An anonymous crawler is one that gets blocked. |
| `HRAFNAGUD_LANGUAGES` | all | Narrowing to the languages actually in the registry is faster, more accurate and much cheaper on memory. |
| `HRAFNAGUD_CONTENT_ENABLED` | `false` | Fetch full article text from publisher pages. A separate activity from reading their feeds — hence a separate decision. |
| `HRAFNAGUD_INSTALL_BUNDLED_CATALOG` | `true` | Installs the bundled catalogue (~840 feeds), **disabled**. Nothing is crawled until it is switched on in the console — ~1,700 outbound requests an hour once it is. |
| `HRAFNAGUD_TRANSLATION_ENABLED` | `false` | Runs the translation worker. Costs model time on the brain, so it is a decision rather than a default. |
| `HRAFNAGUD_PIVOT_LANGUAGE` | empty | What gets queued, decided at ingest. Only set it once the brain binding below is complete and the worker is enabled, or the backlog grows with nothing draining it. |
| `HRAFNAGUD_PROXY_HOST` / `_PORT` | direct | Route every fetch through a proxy. A host without a valid port fails at startup rather than quietly going direct. |

Secrets go into the `hrafnagud-secrets` Secret, which
`deploy/k8s/render-secret.sh` generates from the env file — there is no
committed Secret manifest and no template with placeholders, so the keys have
exactly one definition:

| Key | Required | Notes |
|---|---|---|
| `HRAFNAGUD_MONGO_URI` | yes | Full connection string. The prod profile deliberately has no default. |
| `HRAFNAGUD_API_TOKEN` | in a cluster | Bearer token for `/api/v1/**` and the console. Empty = no check, and this API deletes as well as reads. |
| `VANCE_TRANSLATE_TOKEN` | no | Ursa event token for the brain-side `translation` kit. |
| `HRAFNAGUD_CENTAURI_API_KEY` | no | Bearer key for the feed endpoint. Empty = no check at all. |
| `HRAFNAGUD_ZARNIWOOP_API_KEY` | no | Bearer key for the search endpoint. Empty = no check at all. |

If a GitOps tool owns your secrets (sealed-secrets, SOPS, External
Secrets), create a Secret of that name with those keys and pass
`--no-secret` to `apply.sh`. The Deployment only references the name.

### Talking to a Vancetope brain

Two independent directions, both off by default:

- **Translation, through a brain** — hrafnagud calls Vancetope. Needs
  `VANCE_BRAIN_URL`, `VANCE_TENANT` and `VANCE_TRANSLATE_TOKEN` *and* both
  translation switches (`HRAFNAGUD_TRANSLATION_ENABLED=true` plus
  `HRAFNAGUD_PIVOT_LANGUAGE`). In-cluster, if the brain runs in the `vance`
  namespace of the same cluster:
  `VANCE_BRAIN_URL=http://brain.vance.svc.cluster.local:9990`.
- **Translation, calling a model directly** — the other path. Needs
  `GEMINI_API_KEY` (a Secret key, so it goes in the env file that
  `render-secret.sh` reads) plus the same two switches. `HRAFNAGUD_GEMINI_MODEL`
  overrides which model, since Google renames and retires them. **With both
  paths wired, nothing translates until `HRAFNAGUD_TRANSLATION_PROVIDER` names
  one** (`vance-ode` or `gemini`) — picking one of two ways to spend money by
  accident would be worse than saying so, and the startup log says so.
  `HRAFNAGUD_READABLE_LANGUAGES` is worth setting either way: languages listed
  there are never queued, and for a bilingual reader that is half the bill.
- **Centauri** — the brain calls hrafnagud, to read the archive as a feed
  source. Needs `HRAFNAGUD_CENTAURI_ENABLED=true` and, before you expose it,
  `HRAFNAGUD_CENTAURI_API_KEY`.
- **Zarniwoop** — the brain calls hrafnagud, to search the same archive.
  Switched separately: `HRAFNAGUD_ZARNIWOOP_ENABLED=true` plus
  `HRAFNAGUD_ZARNIWOOP_API_KEY`. Serving a timeline is not a reason to answer
  queries, so neither flag implies the other.

Both keys are one static shared secret each, compared in constant time, and
empty means no check. That is the right size here: hrafnagud serves one brain,
and the endpoints read a public news archive. A source with several readers, or
one that sells access, publishes an `OdeAuthService` instead — see the
`vance-ode` README.

## MongoDB

Not part of the deployment. The Deployment reads a connection string from the
Secret and expects a database to already exist — on the Mini that is the
shared MongoDB on the host, `10.42.40.23:27017`, database `hrafnagud`.

`spring.data.mongodb.auto-index-creation` is on, so the indexes the entities
declare are created on first connect. That matters: without the unique index
on `articles.dedupKey`, deduplication degrades to a best-effort application
check.

### Changing an index means dropping it first

Auto-creation only *creates*. MongoDB will not redefine an existing index, and
when the declaration no longer matches what is stored it answers
`IndexKeySpecsConflict` (86) — which Spring rethrows, so the pod does not start
with a stale index, it does not start at all. Against an empty database nothing
happens; against a database that has been collecting, the deploy crash-loops.

`source_catalogs.catalog_url_idx` also moved once: it was unique, and is not
any more, because two catalogues over one repository with different filters are
two legitimate things (see `specs/catalogs.md` §6a). An instance that ran the
earlier version has to drop it —

```
db.source_catalogs.dropIndex("catalog_url_idx")
```

— or the pod fails to start with `IndexOptionsConflict`, which is the intended
outcome: loud rather than silently keeping a constraint the code no longer
believes in.

The text index is the one that moves most, because both its fields and its
options have changed once already (`pivotTitle`/`pivotSummary` were added, and the
language override was pointed at `textLanguage` — see `specs/collection.md`
§4.1). Its generated name stays `ArticleDocument_TextIndex` throughout, which is
exactly why the conflict is possible.

So before rolling out a release that changes an index, drop the old one and let
the new pod recreate it:

```bash
mongosh "$HRAFNAGUD_MONGO_URI" --eval '
  db.articles.dropIndex("ArticleDocument_TextIndex")'
```

Search is unavailable until the new index finishes building; collection is not
affected. Check what is actually stored with
`db.articles.getIndexes()` before assuming which state you are in.

## Kubernetes

`kubectl apply -k` against `base/` or an overlay; `apply.sh` wraps it and
adds the two things kustomize does not do — rendering the Secret, and
applying in the right order (namespace → Secret → rest, because the other
way round starts a pod whose env references a Secret that does not exist
yet).

```bash
deploy/k8s/apply.sh                            # base, current context
deploy/k8s/apply.sh --overlay mini --remote    # kubectl on the cluster host
deploy/k8s/apply.sh --restart                  # + rollout restart
deploy/k8s/apply.sh --no-secret                # GitOps owns the Secret
```

`--restart` is not cosmetic. The images are published under a floating
`:latest`, and re-applying an unchanged manifest gives the kubelet no reason
to pull anything. Either restart, or push version tags and set the tag in the
manifest.

**Base is deliberately not reachable from outside.** The REST API has no
authentication of its own: `/api/v1` can add sources, trigger refreshes and
read everything collected. So the Service is a `ClusterIP` and the way in is
`kubectl port-forward`. The mini overlay makes it a NodePort for the reverse
proxy on that host, and the Caddy route publishes only the API-key-protected
`/ode/feed`.

### Probes and memory

Liveness and readiness use the actuator's probe groups, with the cold-start
budget on a `startupProbe` (5 minutes, against an observed ~30 s) so that a
slow boot can never be mistaken for a fault by the liveness probe.

Memory limit is 1536Mi against a 512Mi request. Lingua in low-accuracy mode
over every language it knows is the dominant consumer; if the pod hits the
limit, set `HRAFNAGUD_LANGUAGES` before raising it.

`/actuator/prometheus` is listed in the exposed endpoints but returns 404
until a Micrometer registry is on the classpath — `management` currently
brings the actuator only. `/actuator/health` and `/actuator/metrics` work.

## The Mini (k3s on the shared Mac Mini)

Same machine and same reverse proxy as Vancetope, different namespace
(`hrafnagud`, alongside `vance`). NodePort 30980 stays clear of 30090/30091.

```bash
deploy/mini/deploy.sh              # build both arches → push → apply → restart → route
deploy/mini/deploy.sh status
deploy/mini/deploy.sh logs 200
deploy/mini/deploy.sh forward      # API on localhost:9800 for as long as it runs
deploy/mini/deploy.sh caddy        # (re-)install the public route
```

kubectl runs on the host over SSH, so `SSH_HOST` / `SSH_USER` / `SSH_KEY`
have to be set. The rendered Secret is uploaded to `/tmp` on the host,
applied, and deleted — it never lands on disk inside the repository.

The public route is opt-in: without `HRAFNAGUD_PUBLIC_HOST` no route is
installed. When it is set, `install-caddy.sh` replaces the block between
`# >>> hrafnagud >>>` markers in the host's Caddyfile and leaves the rest
alone. That file is a single-file bind mount into the caddy container, so
the script rewrites it with `cat >` — anything that swaps the inode
(`sed -i`, `mv`, `cp -f`) breaks the mount and the container keeps serving
the old content until it is restarted.
