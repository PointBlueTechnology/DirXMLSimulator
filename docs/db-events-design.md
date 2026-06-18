# Design note: Event Logger DB as an input source

Status: **implemented** (2026-06-18). A second live event source alongside the
driver cache (`dxcache`): the **DirXML Event Logger** database — a persistent,
queryable history of real subscriber-channel events.

## The source

The Event Logger (a subscriber-channel driver, `jcombs-pointblue/DirXMLEventLogger`)
writes every event to a PostgreSQL table `public.dxmlevent`, keeping the **original
XDS** in `xmlevent` next to queryable metadata:

| column | use |
|---|---|
| `xmlevent` (text) | the original XDS event — the input we want |
| `srcdn` `classname` `eventtype` `srcdriver` | filters (indexed on reversed `srcdn`, `srcdriver`) |
| `cachedtime` (timestamptz) | time-range filter / ordering |
| `eventid` (PK) `srcentryid` `eventjson` | identity + JSON (we use the XML; the JSON is for the logger's web UI) |

Connection is plain JDBC: `jdbc:postgresql://host:port/db` + user/password.

## Why it complements the other sources

A **persistent, queryable corpus** of real events — more than a trace or the
transient `dxcache`:

- survives driver restarts (the cache is the pending queue; this is the full history);
- query by object **DN, driver, event type, class, or time range** — e.g. "the last
  10 modify events on this user," "driver X's events last week";
- already populated; no trace capture needed.

The captured XDS is a genuine subscriber event (what the IDV produced), so it drives
any subscriber chain under test.

## Key design decision: each row is a distinct transaction

Every logged row is its **own** transaction and is materialized as its **own**
sample — never coalesced into one `<input>` batch. (Flattening them into one
`<input>` would run them as a single operation context, which misrepresents how they
actually occurred.) `dbevents` writes one file per event under `events/` and prints
a listing; **you pick** which to run as `input.xds`. The query filters narrow the
candidate set; the per-event files make the final selection explicit.

## Implementation

- **`DbEventReader`** — JDBC `SELECT … FROM dxmlevent WHERE <filters> ORDER BY
  cachedtime <dir> LIMIT <n>`, returning each row as an `Event` (metadata + its XDS).
  The SQL is parameterized (`buildSql`, unit-tested) and read-only.
- **CLI: `bin/sim dbevents <caseDir>`** — connection + filters from `case.properties`;
  writes `events/NNN-<type>-<cn>.xds` per event and a listing:

  ```properties
  db=jdbc:postgresql://host:5432/idmEvent
  dbUser=postgres
  dbPassword=…
  dbTable=public.dxmlevent        # default
  # filters (all optional):
  eventType=modify   eventClass=User
  eventsForDn=\\TREE\\data\\users\\jdoe   # exact DN — note: \\ in a .properties file
  eventsDnLike=%jdoe                       # or a LIKE, no backslash escaping needed
  eventsDriver=cn=CyberArk,cn=driverset1,o=system
  eventsSince=2026-06-01   eventsUntil=2026-06-30   eventLimit=50   eventOrder=desc
  eventsWhere=<raw SQL predicate>          # power-user escape hatch (e.g. a jsonb test)
  ```

  Then copy the chosen sample to `input.xds` and `run`/`step` it.

- **`.properties` backslash caveat** — a Java properties file treats `\` as an escape
  (`\u…` especially), so a slash-form DN in `eventsForDn` must double its backslashes.
  `eventsDnLike=%cn` sidesteps it entirely.

## Dependency

**PostgreSQL JDBC** (`org.postgresql:postgresql`, runtime scope) — open-source
(BSD). No manual staging, unlike the NetIQ jars:

- **Building from source**: Maven fetches it from Central and the dependency-plugin
  stages it into `lib/postgresql.jar` (for the `bin/sim`/exec-jar classpath)
  automatically during the build.
- **Running a release**: the dist zip **bundles** `lib/postgresql.jar` (it is freely
  redistributable), so it works out of the box.

Only the `dbevents` feature loads it (via the JDBC ServiceLoader).

## Validation

Verified live against a real Event Logger DB: queried `modify` events (8 across
several users and months), each written as its own runnable transaction
(`<nds><input><modify …></modify></input></nds>` with full event metadata), and the
DN/`LIKE`/type filters all narrowed correctly.

## Not in scope

Read-only by design. The logger's `eventjson` column enables rich JSON queries
(via `eventsWhere`) but we always materialize the faithful `xmlevent` XML, not the
JSON.
