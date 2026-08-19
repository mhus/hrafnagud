# mickey-Deployment ist umgezogen

Das **k8s-Deployment** von hrafnagud läuft jetzt zentral über:

    ../../../mhus-infrastructure/loc1_ho/    (vance-Suite — hrafnagud gehört dazu)

- Deploy nach mickey: `./vance_deploy.sh hrafnagud`  (bzw. `all`)
- kubectl/k9s: `./k9s.sh -- get pods -n hrafnagud`
- Manifeste (kustomize base+mini): `loc1_ho/apps/hrafnagud/`
- Secret (sops+age): `loc1_ho/secrets/apps-hrafnagud.env` (+ MONGO_URI aus dem geteilten Mongo-PW)

**Trennlinie:** dieses Repo **baut das Image** und pusht es → `docker.io/mhus/hrafnagud`.
`deploy/mini/deploy.sh build|push` bleiben hier gültig; `apply|caddy|status|restart` sind **überholt**
(zeigten auf minnie-k3s) → stattdessen `vance_deploy.sh`.

## Offen: Image
`docker.io/mhus/hrafnagud:latest` existiert (noch) nicht bzw. ist privat. Vor dem ersten Deploy:
`deploy/mini/deploy.sh build push` (baut + pusht multi-arch). Ist das Repo auf Docker Hub **privat**,
braucht mickeys k3s zusätzlich einen imagePullSecret (die vancetope-* sind öffentlich, daher bisher keiner nötig).
