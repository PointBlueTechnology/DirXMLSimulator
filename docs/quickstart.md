# Quickstart

From zero to stepping through your own driver's policies. Commands are
copy-pasteable; `$` lines are shell.

> **Developing a driver shim?** If you're writing the connector itself in its own
> IntelliJ project and want to exercise it through the simulator on each build,
> see [shim-dev-workflow.md](shim-dev-workflow.md) — it keeps the two repos
> separate with your test cases in the shim repo.

## 1. One-time setup

**JDK 21** is required (the engine jars are Java 21 bytecode). The `bin/sim`
launcher finds it automatically, or set `SIM_JAVA_HOME`.

**Stage the jars.** Copy the nine NetIQ jars into `lib/` (see the
[README](../README.md#requirements) for the list and where they come from on a
server). Then build and self-check:

```bash
$ mvn compile
$ bin/sim doctor
DirXML Policy Simulator — doctor
  java.version: 21.0.8  OK
  engine jars: OK
  engine smoke run: OK
  sample case cases/copy-surname: PASS
DOCTOR: OK
```

`DOCTOR: OK` means you're ready.

**Using it as a skill.** This project ships a Claude Code skill. Working *in* this
repo, it's active automatically — just ask the agent to test or debug a policy. To
use it from any other project, install it globally:

```bash
ln -s "$(pwd)/.claude/skills/dirxml-policy-testing" ~/.claude/skills/dirxml-policy-testing
```

(The skill drives `bin/sim`, so keep this built repo reachable.) See the
[README](../README.md#install-it-as-a-claude-code-skill) for details. The rest of
this guide is what the agent does for you — and what you can run by hand.

## 2. Prove it on the bundled sample (no export needed)

A complete sample case ships with the project:

```bash
$ bin/sim step cases/copy-surname
```

You'll see the single stage's input and output, the `<query>` the policy issued,
the directory's answer, and the rule trace — the value `Surname=Doe` flowing from
the fake directory into a `CopiedSurname` attribute. That's the whole loop in
miniature.

## 3. Run your own driver on real events

### a. Get the artifacts

- **Driver config** — any one of:
  - the **live vault over LDAP**, or an **LDIF export** of it (easiest — one
    source carries the policy chain, GCVs, filter, and shim params for the *whole
    driver set*, and live LDAP also gives you the schema and query answers; see
    [3c](#c-point-the-case-at-the-driver-config)),
  - a **driver export** (`.xml` from Designer → *Export to Configuration File*),
  - a **Designer project** on disk (your `designer_workspace` project + driver
    name) — also carries the schema, so inputs get validated.
- **An input event** — a DSTrace / driver trace log (turn trace up, reproduce the
  event, save the log) is the classic source. But with a live environment you have
  two better ones: a **stopped driver's event cache** (`bin/sim dxcache`, step e),
  or — best of all — the **Event Logger database** (`bin/sim dbevents`, step f), a
  searchable history of real events. Directory data (the answers to the policies'
  queries) comes from the trace, an LDIF dump, or live LDAP.

### b. Bootstrap a case from the trace

```bash
$ bin/sim extract /path/to/driver.trace cases/my-test
parsed 21 XDS documents from driver.trace
  event: 1
  query-result: 3
  ...
wrote input.xds  (channel=Subscriber)
wrote case.properties stub (channel=subscriber)
wrote directory.xds  (3 query results merged)
```

This creates `cases/my-test/` with:
- `input.xds` — the real event from the trace,
- `directory.xds` — the directory data the policies looked up,
- `case.properties` — a stub with the channel inferred,
- `trace-samples/` — every document in the trace, labeled.

### c. Point the case at the driver config

Edit `cases/my-test/case.properties`. Use an **LDIF vault export** + driver name:

```properties
ldifConfig=/path/to/IDM_subtree.ldif
driver=CyberArk
channel=publisher        # or subscriber (the extract step inferred one)
driverDN=\TREE\system\driverset\MyDriver
```

**or** a **driver export**:

```properties
export=/path/to/driver.xml
channel=publisher
driverDN=\TREE\system\driverset\MyDriver
```

**or** a **Designer project** + driver name:

```properties
project=/path/to/designer_workspace/MyProject
driver=CyberArk-PROD
channel=publisher
driverDN=\TREE\system\driverset\MyDriver
```

**or** read the config **live from LDAP** — no file at all; the harness pulls the
driver subtree straight from the running vault:

```properties
ldap=ldaps://host:636
ldapBindDn=cn=admin,ou=sa,o=system
ldapBindPassword=...
ldapConfig=cn=driverset1,o=system    # the DriverSet DN to read
driver=CyberArk
channel=publisher
schema=ldap                          # also read the eDir schema live (validates inputs)
```

Any of them assembles your real channel chain (in IDM policy-set order) and loads
the driver's GCVs and ECMAScript resources. With `project=` it also loads the
**schema** and validates `input.xds`/`directory.xds` against it. With a live `ldap=`
connection you get more for free: `schema=ldap` reads the eDirectory schema
directly (no project needed), and the policies' queries can be answered from **live
eDirectory** instead of `directory.xds`. (TLS cert validation is off by default —
test directories use self-signed certs; set `ldapTrustAll=false` to require a valid
cert.)

> **Producing the LDIF** — a plain `ldapsearch *` omits the DirXML policy/config
> attributes, so request them explicitly:
> ```bash
> ldapsearch -o ldif-wrap=no -b "cn=<DriverSet>,o=system" -s sub "(objectclass=*)" \
>   '*' XmlData DirXML-Policies DirXML-ShimConfigInfo DirXML-ConfigValues \
>   DirXML-JavaModule DirXML-DriverFilter DirXML-ShimAuthServer DirXML-ShimAuthID
> ```
> The same file (or a `'*'`-only dump) can seed the fake directory with real
> objects — add `ldif=that-file.ldif` to the case.

### d. Step through it

```bash
$ bin/sim step cases/my-test
```

For each stage you get the document before and after, any queries/commands it
issued, and its trace. Add `--rules` to expand a policy rule by rule:

```bash
$ bin/sim step cases/my-test --rules
```

Find the stage (or rule) where a value first appears, gets vetoed, or comes out
wrong — and read that stage's trace to see why.

### e. (Alternative input) pull a stopped driver's event cache

No trace? If the driver is **stopped** and you have a live connection, read its
queued subscriber events directly into the case:

```properties
# case.properties — connection + the driver whose cache to read
ldap=ldaps://host:636
ldapBindDn=cn=admin,ou=sa,o=system
ldapBindPassword=...
cacheDriver=cn=MyDriver,cn=driverset1,o=system
```

```bash
$ bin/sim dxcache cases/my-test
wrote cases/my-test/cache.xds  (22 cached events, …)
wrote input.xds (the cached events as one <input> batch)
```

It writes the real pending events as `input.xds`. A **running** driver is detected
and reported (stop it first). Needs the optional `lib/ldap.jar` (Novell LDAP SDK).

### f. (Best input source) pick real events from the Event Logger DB

The richest source of test inputs is the **[DirXML Event Logger](https://github.com/jcombs-pointblue/DirXMLEventLogger)** —
a small subscriber-channel driver that records *every* event passing through a
driver set to a PostgreSQL table, keeping the original XDS next to searchable
metadata. If it's deployed in your environment, you have a standing library of real
production events to test against.

**Why this is the option to reach for:**

- **It's real production traffic, already captured.** No turning trace levels up, no
  reproducing an event, no hand-authoring — the exact documents the engine processed
  are sitting in a table, with their real attributes, associations, and metadata.
- **It's a persistent, searchable history.** Unlike a trace (one capture) or the
  driver cache (the transient pending queue, gone after the driver drains it), the
  log accumulates. You can pull an event from months ago.
- **It's precisely selectable.** Query by object **DN**, **driver**, **event type**,
  **class**, or **time range** — "the last 10 modify events on this user," "every
  add the AD driver saw last week," "that one delete that broke production."
- **It builds regression corpora for free.** Pull a representative set of real
  events, save them as goldens, and prove a policy change is safe against actual
  traffic — not contrived inputs.
- **It's ideal during driver development.** Develop policies against the real events
  your driver is already seeing, iterate, and re-run.

Point a case at the database and filter:

```properties
# case.properties — connection + filters
db=jdbc:postgresql://host:5432/idmEvent
dbUser=postgres
dbPassword=...
# pick what you want (all optional):
eventType=modify              # add | modify | delete | sync | rename | move
eventClass=User
eventsDnLike=%jdoe            # match srcdn (no backslash escaping)
# eventsForDn=\\TREE\\data\\users\\jdoe   # exact DN — note the doubled backslashes
eventsDriver=cn=CyberArk,cn=driverset1,o=system
eventsSince=2026-06-01        # eventsUntil=…   eventLimit=50   eventOrder=desc
# eventsWhere=<raw SQL>       # power user: e.g. a jsonb predicate on eventjson
```

```bash
$ bin/sim dbevents cases/my-test
8 event(s) — each a distinct transaction; pick one as input.xds:
  [  1] modify  User  \IDM_IG4_TREE\data\jdoe   2026-06-05 15:26:43   -> events/001-modify-jdoe.xds
  [  2] modify  User  \IDM_IG4_TREE\data\asmith 2026-05-21 09:39:06   -> events/002-modify-asmith.xds
  ...
```

**Each logged row is its own transaction**, so `dbevents` writes one file per event
under `events/` and lists them — it never merges them into one batch (that would
run distinct events as a single shared operation). You stay in control: pick the
one you want and make it the input —

```bash
$ cp cases/my-test/events/001-modify-jdoe.xds cases/my-test/input.xds
$ bin/sim step cases/my-test
```

No jar to stage: the open-source PostgreSQL JDBC driver is fetched by Maven on
build and bundled in releases. (The Postgres password is the **database** password,
which is typically *not* your eDirectory password.) See
[docs/db-events-design.md](db-events-design.md) for the table schema and all filters.

## Directory data: what the policies look up

When a policy issues a `<query>` (matching, attribute reads, `do-find-matching-object`),
the answer comes from the **fake directory**. Seed it any of these ways — they can be
combined:

- **`directory.xds`** — hand-authored `<instance>` state, or written by `bin/sim
  extract` from a trace (the directory's real query answers).
- **An LDIF dump** — load real objects with `ldif=`:
  ```properties
  ldif=/path/to/users.ldif
  ```
  Dump a few objects with `ldapsearch`/ICE (any `'*'` export works):
  ```bash
  ldapsearch -o ldif-wrap=no -b "ou=users,o=data" -s sub "(objectclass=*)" '*' > users.ldif
  ```
  Entries are mapped to native XDS via the schema — attribute/class names go
  DirXML-ward and values are normalized by syntax (a base64 `::` GUID stays
  base64/octet, generalized time → seconds, a DN value → slash form). A
  `dirxml-associations` value matching the case's `driverDN` becomes the instance's
  `<association>`.
- **Live eDirectory** — with `ldap=` set, the chain's queries are answered straight
  from the running vault (no seeding needed):
  ```properties
  ldap=ldaps://host:636
  ldapBindDn=cn=admin,ou=sa,o=system
  ldapBindPassword=...
  ldapSearchBase=o=data
  ```
  Values come back normalized the same way (so a live `GUID` arrives as `type="octet"`
  base64, not raw bytes). Best with a schema available (next), so binary/time/DN
  attributes are recognized.

## Schema: catch typo'd inputs

A schema lets the harness flag mistakes in `input.xds`/`directory.xds` — an unknown
class, an attribute not in the schema (a typo), an attribute not valid for its class,
or multiple values on a single-valued attribute. Load one any of these ways:

- **From a Designer project** — automatic when `project=` is set (the project's
  `*_schema.xml`).
- **From a file or project directory** — explicit:
  ```properties
  schema=/path/to/EMX2D58K_schema.xml      # or a Designer project directory
  ```
- **Live from LDAP** — read the eDirectory subschema (`cn=schema`) directly:
  ```properties
  schema=ldap        # or: automatic whenever ldap= is set and no other schema is given
  ```
  This is a full equivalent of the project's `*_schema.xml`, no project needed — it
  recovers the true NDS/DirXML attribute names (from each definition's `X-NDS_NAME`)
  and the eDir syntaxes that drive value normalization. A read failure is non-fatal
  (it just warns; validation is skipped).

Schema warnings are printed up front by `run`/`step`/`test`, e.g.
`WARNING: schema validation … unknown attribute 'Sumame' (typo?)`.

## 4. Test a change

Lock in the current behavior as a golden, edit the policy, and verify:

```bash
$ bin/sim record cases/my-test          # save expected-output.xds
# ... edit the policy file the case points at ...
$ bin/sim test cases/my-test            # exit 0 = unchanged, 1 = changed (with a diff)
```

Use `test` to prove a fix does what you intend and nothing else regresses.

## Command reference

```
bin/sim run    <caseDir> [--trace]   run the chain; final output (+ full trace)
bin/sim step   <caseDir> [--rules]   per-stage (or per-rule) input/output/queries/trace
bin/sim dxcache <caseDir>            read a stopped driver's event cache (live) into the case
bin/sim dbevents <caseDir>           list/pick logged events from the Event Logger DB
bin/sim test   <caseDir>             diff vs goldens; exit 0 pass, 1 mismatch
bin/sim record <caseDir>             write expected-output.xds / expected-directory.xds
bin/sim extract <trace> <outDir>     mine a DSTrace log into a case
bin/sim doctor                       setup self-check
```

## Letting an agent drive

Everything above is what an agent does on your behalf — point it at an export and
a trace and ask in plain English ("why isn't email syncing — step the subscriber
channel and find the rule that drops it"). See [intro.md](intro.md) for the
pitch and example asks, and the skill's `SKILL.md` for how the agent uses these
commands.
