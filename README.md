# TI4 Objective Tracker

A small scoring aid for Twilight Imperium 4th Edition. Runs a local web server on
the game laptop; anyone at the table can open it on a phone or tablet on the same
wifi.

## What it does

- Mark which public objectives are revealed this game (Stage I / Stage II)
- Tap a player against a revealed objective to score it
- Add ad-hoc points with a label, for secret objectives and everything else
- Live running totals per player, with a leader highlight

## Stack

| Piece | Choice |
|---|---|
| Framework | Spring Boot 4.1.1 |
| Java | 21 (Adoptium, via `JAVA_HOME`) |
| Build | Maven |
| Database | SQLite, single file at `data/ti4.db` |
| Data access | `spring-boot-starter-jdbc` + `JdbcClient` |
| Front end | Plain HTML/CSS/JS, no framework |

## Design decisions

**Spring Boot 4.1, not 3.x.** The 3.5 line's free support ended 30 June 2026, so
starting on 3.x would mean starting unsupported. 4.1 covers Java 17–26.

**JDBC, not JPA.** Hibernate has no officially supported SQLite dialect — only
community ones — so `spring-boot-starter-data-jpa` is the awkward path here
rather than the easy one. `JdbcClient` plus an explicit `schema.sql` keeps the
SQL visible and avoids the dialect problem entirely.

**Connection pool of one.** SQLite serialises writers. With several people
tapping at once, a bigger pool produces `SQLITE_BUSY` errors rather than
throughput, so `spring.datasource.hikari.maximum-pool-size=1`.

**JDK 21 pinned explicitly.** This machine has Oracle JDK 17 on `PATH` and
Adoptium JDK 21 at `JAVA_HOME`. The build uses `JAVA_HOME` so there is no
ambiguity, and no need to change system environment variables.

**Requests are form-encoded, responses are JSON.** Form encoding needs no
request DTO per endpoint for an app this small.

**Points are an append-only ledger.** The `score` table holds one row per
scoring event, not a running total. A player's score is a `SUM` over their rows,
so every point is attributable and any mistake is fixed by deleting one row
rather than reverse-engineering a total.

**Objectives are data, not code** — see `data/objectives-seed.csv`. Fixing a
wrong requirement or adding a homebrew card is a one-line edit, no recompile.
The seeder only runs when the table is empty, so it never clobbers cards you
added in the app.

## Objective data accuracy

`data/objectives-seed.csv` carries base-game and Prophecy of Kings objectives
**drafted from memory and not yet verified against physical cards.** Point values
and names are probably right; requirement wording and the exact base/PoK split
are the likeliest errors. Please spot-check it. The app logs a reminder on first
boot.

**Thunder's Edge adds no new public objectives**, confirmed against the physical
expansion, so the 40 base + PoK cards are the complete set. Nothing to add for
it. (It does add factions, worlds, Galactic Events and the Twilight's Fall mode,
none of which this app models.)

**Reveal objective → Card not listed…** stays available for homebrew cards, and
is also how you attach an image to a card that has none.

## Card images

Drop card scans in `data/images/` and put the filename in the objective's `image`
field. They are served straight off disk, so a new image needs no rebuild.

They are **not** committed — that artwork is Fantasy Flight Games' copyright, and
a public GitHub repo is a different proposition from a folder on your own
machine. `.gitignore` keeps them local.

## Running it

Easiest is the green run arrow on `Ti4TrackerApplication` in IntelliJ, or the
Maven panel's `spring-boot:run`.

From a terminal, there is no `mvn` on PATH -- IntelliJ's bundled Maven 3.9.16 is
used instead:

```
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.2.13-hotspot"
"C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" spring-boot:run
```

Then open <http://localhost:8080>. Other devices at the table use the laptop's
LAN address -- currently `http://192.168.100.9:8080`. Windows Firewall may need
to allow inbound 8080 the first time another device connects.

## Layout

```
ti4-tracker/
  pom.xml
  data/
    objectives-seed.csv        the card catalogue -- edit freely
    ti4.db                     live database (not committed)
    images/                    your card scans (not committed)
  src/main/java/ti4/
    Ti4TrackerApplication.java
    Ti4Properties.java         externalised paths
    WebConfig.java             serves /images/** off disk
    ObjectiveSeeder.java        CSV import on first boot
    domain/                    records
    repo/                      JdbcClient queries
    web/ApiController.java     the whole API
  src/main/resources/
    application.properties
    schema.sql
    static/                    index.html, style.css, app.js
```

## API

Responses are JSON. Request bodies are `application/x-www-form-urlencoded`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/state?game=<id>` | Everything the UI needs in one call |
| `GET` | `/api/games` | List saved games |
| `POST` | `/api/games` | New game — `name`, `vpTarget`, `maxSecrets` |
| `GET` | `/api/objectives` | Catalogue, optional `expansion` filter |
| `POST` | `/api/objectives` | Add a card |
| `POST` | `/api/objectives/image` | Attach a scan — `id`, `image` |
| `POST` | `/api/players` | Add player — `gameId`, `name`, `faction`, `color` |
| `DELETE` | `/api/players` | Remove player — `id` |
| `POST` | `/api/reveal` | Reveal — `gameId`, `objectiveId`, `round` |
| `POST` | `/api/unreveal` | Un-reveal, and delete its scores |
| `POST` | `/api/score` | Toggle a player on a public objective |
| `POST` | `/api/points` | Manual entry — `points`, `label`, `kind` |
| `POST` | `/api/points/delete` | Remove a ledger row — `id` |
| `GET` | `/api/factions` | Faction list for the add-player dropdown |
| `GET` | `/api/point-sources` | Label options, seeded plus learned from use |
| `GET` | `/api/audit` | History — optional `game`, `limit` (default 300, max 2000) |

## History

Every mutating action is recorded in the `audit` table and shown under the
**History** edge tab: game and player creation, reveals, scoring and un-scoring,
manual points, and every removal. Removals are highlighted, since "who deleted
that" is the question the log exists to answer.

The table is deliberately **not** foreign-keyed to `game` or `player`. The whole
point is that the record outlives the row it describes — a cascade would delete
the evidence along with the player.

There is no login, so the recorded actor is the requesting device's address.
Requests from the game laptop show as "game laptop"; anything else shows its LAN
address, which is what distinguishes one phone at the table from another.

Audit writes never throw: losing a history line is bad, losing someone's score
because the history write failed would be worse. Lines also go to the
application log, so the trail survives the database file being replaced.

Note that catalogue changes (adding a card, setting an image) are not tied to a
game, so they only appear with **this game only** unchecked.

## Scoring rules encoded

- Public objectives: Stage I = 1 VP, Stage II = 2 VP, taken from the card
- Secret objectives: entered manually, since they are hidden information
- The secret cap (3, or 4 with The Obsidian) is a **warning, not a block** — the
  app should never refuse input mid-game and leave you arguing with it
- Other VP sources — custodians token, Support for the Throne, Imperial, agenda
  outcomes like Shard of the Throne, relics like the Crown of Emphidia — go
  through the same manual entry with a label

## Verified

Compiled and run on 2026-08-25. Checked end to end: seeding (40 cards), game and
player creation, revealing, scoring, toggling a claim off, manual entries,
totals, and the browser UI updating live. Error paths return real messages
(400 bad stage, 409 duplicate name, 404 unknown game, 400 missing parameter).
Data survives a restart.

## Not done yet

- No tests. Everything above was verified by hand, which is not the same thing.
- No image upload through the browser; you copy files into `data/images/`
  yourself and type the filename.
- No "who is winning on tiebreak" logic (TI4 breaks ties by initiative order).
