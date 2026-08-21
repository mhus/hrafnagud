# Hrafnagud — CLAUDE.md

> Leitlinien für die Arbeit an diesem Repo. `README.md` ist der Einstieg (was
> das ist, wie man es startet, welche Endpunkte es gibt), `specs/` die Schicht
> darunter (Modell, Mechanismen, Entscheidungen mit Begründung). Diese Datei
> sagt, **wie hier gearbeitet wird** — sie wiederholt README und Specs nicht,
> sondern verweist.

Hrafnagud war bis August 2026 `repos/hrafnagud/` im Vancetope-Workbench und ist
jetzt ein eigenes Repo. Die Konventionen sind bewusst dieselben wie dort — ein
Satz Regeln für beide Codebasen.

## Directories

- Repo-Root: `/Users/hummel/sources/mhus/hrafnagud`
- Java-Code (GPLv3, siehe `LICENSE`): `server/` — ein Maven-Artefakt, kein
  Reactor. Der Zwischenordner ist Absicht, damit später ein `client/` daneben passt.
- Referenz-Doku: `specs/` — ein Dokument pro Thema, Englisch. Kein Changelog,
  keine Task-Liste (siehe `specs/README.md`).
- Planung / Brainstorming: `planning/` — Vorüberlegungen, Session-Exports,
  verworfene Wege. Was gebaut ist, wandert nach `specs/`, nicht hierhin.
- Container-Build und Alt-Manifeste: `deploy/` (siehe unten)
- Datengeneratoren: `scripts/` — z.B. `generate-mediatopics-tsv.py` für
  `server/src/main/resources/topics/iptc-mediatopics.tsv`
- **Vancetope-Gegenseite:** `../vance-wb` — dort liegt `repos/vance-ode/`, also
  der REST-Contract, den `hugin/`, `centauri/` und `zarniwoop/` bedienen, plus `specification/public/centauri-service.md` und
  die `planning/centauri-*.md`. Contract-Fragen fangen dort an, nicht hier.
  **Nichts dort committen**, wenn hier gearbeitet wird — getrennte Repos.
- **Cluster-Deployment:** `../mhus-infrastructure/loc1_ho/` — dieses Repo baut
  und pusht nur das Image, siehe `deploy/DEPLOYMENT_MOVED.md`.

## General

- Source Code, Kommentare, `README.md` und `specs/` in **Englisch**
- Kommunikation mit mir: **Deutsch**
- Keine TODOs im Code — implementierte Funktionalität
- Keine toten Stubs, kein `throw new UnsupportedOperationException("not yet")`
  ohne konkreten Grund
- **KEINE HACKS.** Typisierte Klassen, Services mit klarer Verantwortung,
  Builder statt Map-Gebastel. Keine JSON-Manipulation, keine Reflection-Tricks
- Schritt für Schritt — nicht mehrere Subsysteme parallel umbauen, wenn eines
  Thema ist
- **Kommentare erklären das Warum.** Der Stil in diesem Repo ist, die nicht
  offensichtliche Entscheidung neben den Code zu schreiben, der sie umsetzt
  (siehe `server/pom.xml`, `application.yml`, `ModuleBoundaryTest`). Eine
  Entscheidung mit Tragweite über eine Klasse hinaus gehört zusätzlich in
  `specs/`

## Tech Stack

| Komponente | Technologie |
|---|---|
| Java | 25 |
| Build | Maven, **ein** Modul (`de.mhus.hrafnagud:hrafnagud`, gebaut aus `server/`) |
| Framework | Spring Boot 4.1 |
| Boilerplate | Lombok |
| Null-Safety | JSpecify 1.0.1 (`@NullMarked` pro Package) |
| Persistenz | MongoDB via Spring Data MongoDB |
| Atomare Operationen | `MongoTemplate` (`findAndModify` für Claims/Leases) |
| Feeds | Rome (RSS 0.9x/1.0/2.0 + Atom) |
| HTML / OPML | jsoup |
| Spracherkennung | Lingua |
| Utility | Apache Commons (`StringUtils.isBlank(…)`) |
| Vancetope-Anbindung | `de.mhus.vance.ode:vance-ode-{ursa,centauri,zarniwoop}` |
| Tests | JUnit 5, Mockito, AssertJ |
| Console | statisches HTML + Vanilla-JS, kein Build-Step |

Bewusst derselbe Stack wie Vancetope: ein Satz Konventionen für beide Repos.
Warum ein Artefakt statt sechs Module: `specs/architecture.md` §2.0 und der
Kommentar im POM.

## Package-Struktur

```
de.mhus.hrafnagud.
  api/        DTOs und Enums über die REST-Grenze. Kein Spring, kein MongoDB.
  munin/      GEDÄCHTNIS: Source-Registry, Feed-Ingest, Dedup, Volltext-Fetch,
              Enrichments, Persistenz, Operator-REST (/api/v1/**), Console
  hugin/      GEDANKE: alles, was Text an ein Modell gibt
    translate/  arbeitet Munins Übersetzungs-Backlog über ein Brain ab
    classify/   entscheidet über ein Brain, was eine Publisher-Kategorie bedeutet
  centauri/   serviert das Archiv als Centauri-Feed-Quelle (/ode/feed/**)
  zarniwoop/  beantwortet Research-Queries aus dem Archiv (/ode/search/**)
  facet/      Filter-Dimensionen, die centauri und zarniwoop teilen
  config/     die drei Property-Wurzeln: munin.*, hugin.*, hrafnagud.*
  settings/   die geltenden Werte — config plus die Overrides des Operators
  server/     Entrypoint plus Runtime-Verdrahtung, nichts sonst
```

**Die zwei Raben sind die Schichtung, nicht Deko.** Munin erinnert (sammeln,
speichern, ausliefern), Hugin denkt (alles mit einem Modell dahinter). Wohin ein
neues Subsystem gehört, entscheidet eine Frage: gibt es Text an ein Modell?
Zugleich die Grenze zwischen zwei Budgets — Munin zahlt mit fremder Bandbreite
und läuft von selbst, Hugin zahlt mit Modellzeit und ist aus, bis jemand es
einschaltet. `config/` und `settings/` liegen neben beiden, weil sie beiden
dienen.

### Die eine harte Regel

**Munin hat keine Abhängigkeit auf Vancetope.** Vancetope-zugewandt sind
`hugin/` (ruft raus), `centauri/` und `zarniwoop/` (antworten) und `facet/`
(deklariert für die zwei Antwortenden); alle importieren aus `munin`, keines
wird von `munin` importiert. **`hugin/`, `centauri/`, `zarniwoop/` und `facet/`
löschen muss einen kompilierenden Collector hinterlassen.**

Das war früher der Modulgraph, heute ist es `ModuleBoundaryTest` — der liest
die Sources unter `munin/`, `api/`, `settings/` und `config/` und verbietet
`de.mhus.vance` sowie Imports jener Packages (Kommentare sind ausgenommen).
`settings/` und `config/` stehen mit in der Liste, weil `munin` sie importiert:
ein Vance-Import dort wäre der Weg, die Regel hintenrum zu umgehen. Wenn etwas
in Munin ein Brain braucht, gehört es nach `hugin/` — nicht in eine Ausnahme im
Test. Begründung: `specs/architecture.md` §2.1.

Runtime-Schaltbarkeit gehört dazu und bleibt so: `hugin/*` ist inert bis
`vance.ode.base-url` gesetzt ist (es ruft raus), `centauri`/`zarniwoop` sind
**an, außer jemand schaltet sie ab** (sie antworten).

## Null-Safety mit JSpecify

- **Jedes Package hat `package-info.java` mit `@NullMarked`.** Neues Package
  anlegen → Datei mitanlegen, kein Code ohne Null-Marking.
- Nur `@Nullable` wird explizit annotiert; non-null ist der Default.
  **Kein** `@Nonnull`, kein `javax.annotation.*`, kein Checkerframework.
- Lomboks `@NonNull` ist etwas anderes (Runtime-Check) und darf genutzt werden.
- Kein Build-Fail daran — die Annotationen sind Dokumentation und IDE-Warnung.

## Architektur-Konventionen

### Inbound / Outbound / Business

- **Inbound:** Controller in `munin/web/` (`*Controller`). Parameter
  validieren, DTO ↔ Service, Ergebnis als DTO zurück. **Keine Business-Logik.**
  Fehler zentral im `MuninExceptionHandler`, Mapping im `MuninMapper`.
- **Outbound:** `*Repository` (Spring Data) und die HTTP-Clients in
  `munin/net/`. Werden nur von Services gerufen.
- **Business:** `*Service`. Hier liegt die Domänenlogik.
- **Ausgehendes HTTP** läuft über `munin/net/` — nur dort wird gefetcht, damit
  Proxy, User-Agent, Timeouts und `robots.txt` eine Stelle haben.

### Datenhoheit

Ein Service besitzt seine Entities. Zugriff **immer** über den zuständigen
Service, **nie** direkt auf ein Fremd-Repository oder per `MongoTemplate` auf
eine Fremd-Collection. `ArticleService` hat die Hoheit über `ArticleDocument`
und `ArticleContentDocument`, `SourceService` über `SourceDocument`, usw.

### DTOs vs. Documents

- `api/`: `*Dto` — was über REST geht. Kein Spring, kein MongoDB, keine Logik.
- `munin/**`: `*Document` — persistierte MongoDB-Objekte.
- Niemals ein `*Document` über die API rausgeben, niemals ein `*Dto`
  persistieren. Dazwischen: `MuninMapper`.
- Namenskonvention drumherum: `*Candidate` = noch nicht persistierter Kandidat
  aus einem Parser, `*Query`/`*Cursor` = Abfrage-Parameter, `*Policy` = reine
  Entscheidungslogik ohne Spring (das ist die Stelle, die Unit-Tests bekommt).

### Asynchrone Arbeit

- Ein `@Scheduled`-Tick pro Pipeline (`FeedIngestTick`, `ContentFetchTick`,
  `CatalogRefreshTick`, `SourceListRefreshTick`, `CategoryResolutionTick`,
  `TranslationTick`). Der Tick holt Arbeit und delegiert; die Logik liegt im
  Service.
- **Queues sind Status-Felder mit Partial-Index**, keine Queries — der Index
  bleibt proportional zum Backlog, nicht zum Archiv
  (`specs/architecture.md` §4.1).
- **Claims sind Leases in Mongo** (`findAndModify` + Ablaufzeit), keine
  In-Memory-Locks. Eine Instanz wird angenommen, zwei würden nichts kaputt
  machen.
- **Ergebnis eines Verarbeitungsschritts → `enrichments`**, append-only, ein
  Dokument pro Lauf. Nicht als Feld auf dem Artikel; die durchsuchbare Kopie
  am Artikel ist ein abgeleitetes Read-Model (`specs/enrichments.md`).

### Konfiguration und Settings

Zwei Schichten, und die Reihenfolge ist die ganze Regel:

1. **`config/`** bindet `application.yml` und die `HRAFNAGUD_*`-Env, eine Klasse
   pro Wurzel: `MuninProperties` (`munin.*`), `HuginProperties` (`hugin.*`),
   `HrafnagudProperties` (`hrafnagud.*`). Das ist die **Default-Schicht**, nicht
   der Wert.
2. **`Settings`** legt darüber, was ein Operator in der `settings`-Collection
   überschrieben hat — änderbar im Lauf, über `/api/v1/settings` oder die Console.
   Ein Key trägt die Wurzel der Ebene, die den Wert besitzt.

**Ein Betriebswert wird über ein Handle gelesen, nicht als Zahl gehalten:**

```java
private final Settings.Feed config;                       // im Konstruktor
sourceService.claimDue(now, config.batchSize().value());   // beim Gebrauch
```

Das ist der Punkt der ganzen Konstruktion — die Konsumenten sind langlebige
Singletons, und ein im Konstruktor gelesener `int` ist genau der Grund, warum
eine Änderung früher einen Neustart brauchte. Neuer Wert → Deklaration in
`Settings` (Key = Property-Name inklusive Wurzel, Default als Methodenreferenz
auf die passende Properties-Klasse, plus ein Satz, was er tut). Kein `settings.get("…")` mit
String-Literal an der Lesestelle.

**Nicht jeder Wert darf ein Setting sein.** Was beim Start gelesen wird, bleibt
Property und wird gar nicht deklariert — Tick-Takte (`@Scheduled`), Proxy und
Connect-Timeout (der geteilte `HttpClient`), `api.*`, `installBundled`, das
Lingua-Modell, `feed.profiles`. Ein Key, den man setzen kann und niemand liest,
ist schlimmer als keiner. Secrets bleiben in der Env; es gibt bewusst keinen
verschlüsselten Typ. Begründung und die vollständige Grenze:
`specs/settings.md`.

Ein Zustand, der nicht offensichtlich ist (Feature aus, Token leer, Provider
fehlt), wird **beim Start geloggt**, und ein Worker-Schalter zusätzlich bei
jedem Wechsel (`WorkerSwitch`) — das ist hier Konvention, nicht Kür: die
Alternative ist, ihn am Live-Endpunkt zu entdecken.

### Console

`server/src/main/resources/console/` — drei Dateien, kein Bundler, kein
Framework, bewusst (`specs/console.md` §2). Bootstrap kommt vom CDN.

**Die eine Regel, die nicht Stil ist:** jeder String aus der API ist
publisher-kontrolliert. Er geht durch `esc()` oder `textContent` in das DOM,
niemals roh in `innerHTML`; jedes `href` durch `safeUrl()`.

Die Console liegt **nicht** unter `static/`, weil `munin.api.consoleEnabled:
false` „nicht ausgeliefert" heißen muss.

## Tests

- JUnit 5 + Mockito + AssertJ. Bei neuem Code **ohne Extra-Aufforderung**
  Unit-Tests mitliefern. Schwerpunkt: Services, Parser, Policies, Normalisierer,
  Failure-Pfade.
- **Pure-Logik-first, kein Spring-Context.** Repositories und HTTP via Mockito.
  `@SpringBootTest`/`@WebMvcTest`/Testcontainers sind **opt-in** — auf Anfrage
  oder wenn ein Bug ohne sie nicht reproduzierbar ist, nicht prophylaktisch.
- **Extraktion wird gegen den Fixture-Korpus getestet**, nicht gegen das Netz:
  `server/src/test/resources/pages/` (siehe dessen `README.md`). Eine Seite,
  die schlecht extrahiert, wird auf ihr Skelett reduziert und kommt dort dazu —
  das ist der Weg, `content-extraction` zu verbessern.
- Nicht getestet: triviale Getter/Setter, Lombok-Generiertes,
  Framework-Verhalten (Spring, Jackson, Rome), reine Konfiguration. Keine
  flächigen Existenztests.
- Stil: ein Verhalten pro Test, sprechender Methodenname
  (`feature_situation_expectedOutcome`), AssertJ, AAA-Struktur. Bestehende
  Tests im selben Package als Blueprint.

## Build & Lauf

Braucht eine MongoDB; Defaults erwarten `localhost:27017`.

```bash
cd server && mvn install                  # Build inkl. Unit-Tests
cd server && mvn test                     # nur Tests
cd server && mvn test -Dtest=UrlNormalizerTest   # eine Klasse
java -jar server/target/hrafnagud.jar     # :9800, Console auf /

# Feature-Schalter beim Start, ohne YAML anzufassen
java -jar server/target/hrafnagud.jar --munin.content.enabled=true
```

`mvn install` ist die Fertigstellungsprüfung für eine Änderung — die
Unit-Tests laufen darin mit, und der `ModuleBoundaryTest` ist einer davon.

**Build-Output in ein Temp-File schreiben und danach grep'en**, nicht direkt
durch `| grep` pipen: bei langen Maven-Läufen verschluckt die Pipe Ergebnisse
und der Lauf muss wiederholt werden.

Die Console braucht keinen Build-Step — Datei ändern, neu starten, fertig.

## Deployment

`deploy/` **baut und pusht das Image** (`docker.io/mhus/hrafnagud`), das ist
die Trennlinie. Die k8s-Manifeste hier sind historisch; das Cluster-Deployment
läuft über `../mhus-infrastructure/loc1_ho/` (`./vance_deploy.sh hrafnagud`).
Siehe `deploy/DEPLOYMENT_MOVED.md` und `deploy/README.md`.

## Vancetope-Anbindung

Drei Ode-Artefakte aus Maven Central, Version in einer Property
(`vance.ode.version`). Was hier passiert:

- `translate` und `classify` **rufen ein Brain** über ein Ode-Event. Prompt,
  Skript und Recipe leben als Dokumente auf der Brain-Seite (Kit
  `translation`) — genau deshalb Event statt Modell-Call: der Prompt ist ohne
  Deployment dieses Services editierbar.
- `centauri` und `zarniwoop` **beantworten** ein Brain. Der REST-Contract wird
  von `vance-ode-centauri`/`-zarniwoop` serviert; dieses Repo liefert nur eine
  `FeedSource`- bzw. `SearchSource`-Bean.

Braucht der Contract eine Änderung: erst in `../vance-wb/repos/vance-ode/`,
dann Release nach Maven Central (`wb ode release <X.Y.Z>` aus dem Workbench,
siehe `../vance-wb/readme/release-vance-ode.md`), dann hier die Property
hochziehen. Eine veröffentlichte Ode-Version ist endgültig — kein Ersetzen,
kein Löschen.

## Doku pflegen

- **`README.md` ist Teil der Änderung**, wenn sich die Oberfläche ändert —
  neue Config-Property, neuer Endpunkt, neues Verhalten beim Start. Ein
  Abschnitt „Known gaps" existiert, damit Bekanntes dort steht und nicht
  entdeckt werden muss; eine neue Lücke wird dort benannt.
- **`specs/<thema>.md`** bekommt die Entscheidung mit Begründung, sobald sie
  über eine Klasse hinaus trägt. Form: wofür das Ding da ist, welches Modell es
  hat, wie es funktioniert, welche Entscheidungen nicht offensichtlich waren,
  wo es aufhört. Neues Dokument → Zeile in `specs/README.md` **und** in der
  README-Tabelle.
- Specs sind kein Changelog: eine zurückgenommene Entscheidung steht nur drin,
  wenn die Rücknahme selbst wissenswert ist.
- Drittanbieter-Daten kommen mit Notiz — `NOTICE` **und** `info.*` in
  `application.yml`, damit die Angabe auch jemanden erreicht, der nur den
  Container fährt.

## Git

- **Direkt auf `main`**, keine Feature-Branches.
- Commit-Message-Form: `<bereich>: <betreff>` — `bereich` ist das Package oder
  Subsystem (`munin`, `centauri`, `filter`, `specs`, `deploy`, `build`,
  `chore`, `review`). Der Betreff sagt, **was sich fachlich ändert**, nicht
  welche Dateien angefasst wurden.
- Nur explizite Pfade stagen, kein `git add -A`.
- Committen und pushen nur auf Aufforderung.

## Referenz

| Dokument | Deckt ab |
|---|---|
| `specs/architecture.md` | Packages, die Munin-Regel, Collections, Queues als Status-Felder |
| `specs/collection.md` | Source-Identität, Dedup, URL-Normalisierung, adaptives Polling, Sprach-Provenienz |
| `specs/catalogs.md` | Woher Source-Listen kommen, OPML-Directory-Standard, Reader pro Publikationsform |
| `specs/categories.md` | Publisher-Kategorien gegen IPTC Media Topics normalisieren |
| `specs/content-extraction.md` | Die vier Sprossen der Extraktions-Leiter, Bilder, Fixture-Korpus |
| `specs/filter.md` | Accept/Deny-Regeln: was zu holen und zu übersetzen lohnt |
| `specs/enrichments.md` | Warum ein Verarbeitungsergebnis ein Dokument und kein Feld ist |
| `specs/settings.md` | Betriebswerte in der DB: die zwei Schichten, was Startzeit-Property bleibt, wann eine Änderung greift |
| `specs/geo.md` | Drei Arten von Ort, Containment-Hierarchie, Herkunft ≠ Thema |
| `specs/translation.md` | Pivot-Sprache, Provider-SPI, das Vancetope-Event, verschachtelte Timeouts |
| `specs/console.md` | Die Operator-Console, das Token davor, was sie absichtlich nicht kann |
| `specs/feed-source.md` | Das Archiv als Centauri-Feed: Streams, Cursor, deklarierte Capabilities |
| `specs/research-source.md` | Relevanz-Suche über Original und Übersetzung, die zwei Contract-Regeln |
