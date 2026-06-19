# Design note: batch runner + regression mining

Status: **design** (2026-06-19). Two paired features that turn the simulator from
a one-case-at-a-time debugger into a **regression safety net built from real
production traffic** — the capability `docs/intro.md` already promises ("capture
the current correct output as a golden … re-run to confirm nothing regressed"),
scaled from one case to a whole corpus.

They build entirely on shipped pieces: golden compare (`record`/`test`,
`XmlCompare.canonical`), the Event Logger DB source (`dbevents`/`DbEventReader`),
trace mining (`extract`/`TraceExtract`), and the channel runner. No new engine
seams.

- **Feature 1 — batch runner (`test-all`)**: run an entire directory of cases,
  summarize pass/fail, emit machine-readable output for CI.
- **Feature 2 — harvest (`harvest`)**: generate that directory of cases
  automatically from real events, snapshotting current engine output as the golden.

Build order: **Feature 1 first** (it's small, and it's the consumer of whatever
Feature 2 produces), then Feature 2.

---

## Feature 1: batch runner

> **Status: implemented** (2026-06-19) — `BatchRunner` + `bin/sim test-all`, 6
> offline tests. Discovery marker is `input.xds` (a runnable case), refining the
> "directory with a `case.properties`" wording below — a `case.properties` is
> optional, but a golden test needs an input to run. Validated on the bundled
> cases (4 PASS, live/no-golden SKIP, an incomplete fetch-target ERROR, exit 1)
> with JUnit + JSON written.

### CLI

```
bin/sim test-all <dir> [--json out.json] [--junit out.xml] [--continue]
```

Discovers every case under `<dir>` (a directory containing a `case.properties`
*is* a case; recurse into the rest), runs each as `bin/sim test` does, and reports.
Exit non-zero if any case fails — the CI gate.

```
$ bin/sim test-all cases/regression
  PASS  add-user
  PASS  modify-email
  FAIL  terminate-employee   output differs (3 nodes)
  ERROR matching-change      stage 'matching' threw: …
  ─────────────────────────────────
  18 cases: 16 passed, 1 failed, 1 error   (2.3s)
```

### Behavior

- **Reuses `Case.load` + the existing compare.** A case "passes" exactly as
  `bin/sim test` defines it today (diff vs `expected-output.xds`, and
  `expected-directory.xds` when present). No new comparison logic.
- **Three outcomes per case**: `PASS`, `FAIL` (ran, output differs), `ERROR`
  (load/stage threw, or a configured shim/LDAP host is unreachable — the
  fail-loud rule from the shim design note). `FAIL` and `ERROR` are distinct so CI
  can tell "behavior changed" from "case is broken."
- **A case with no `expected-output.xds` is reported `SKIP`** (nothing to assert),
  not an error — so a half-authored corpus still runs.
- **`--continue` is the default for the suite** (run all, report all); a non-zero
  exit still results if anything failed. (Per-case errors never abort the suite.)
- **Isolation**: each case loads its own `EngineContext`/`FakeDirectory`, exactly
  as a single run does, so cases can't leak directory state into each other.

### Output formats

- **stdout**: the human summary above.
- **`--junit <file>`**: JUnit XML (`<testsuite><testcase>` with `<failure>`/
  `<error>` and the diff text as the message) — consumable by GitHub Actions,
  Jenkins, GitLab, etc. This is what makes a driver's policy corpus a CI gate.
- **`--json <file>`**: a structured array (case name, outcome, timing, diff
  summary, stage that errored). Feeds an agent or a future viewer, and aligns with
  the broader `--json` mode (backlog #4).

### Implementation

One new class, **`BatchRunner`**, plus a `Cli` subcommand:

- `discover(Path root) → List<Path>` — directories containing `case.properties`.
- `run(Path caseDir) → CaseResult{name, outcome, millis, detail}` — wrap the
  existing single-case run/compare in try/catch; classify the outcome.
- `report(List<CaseResult>, formats)` — stdout always; JUnit/JSON writers behind
  the flags. JUnit/JSON serialization is pure and unit-testable offline (feed
  synthetic `CaseResult`s; assert the XML/JSON).

No proprietary deps; the engine work is already inside `Case`. Testable offline
with the existing sample cases (a known-good and a deliberately-broken fixture).

---

## Feature 2: regression mining (harvest)

### The idea

You already have two sources of **real events** (`dbevents` from the Event Logger
DB, `extract` from a trace) and a way to **capture output as a golden** (`record`).
Harvest composes them: for each selected real event, build a case, run it through
the **current** policies, and write the produced output as `expected-output.xds`.
The result is a directory of cases Feature 1 can run — a regression baseline minted
from production traffic in one command.

The workflow it unlocks:

1. `harvest` → snapshot today's behavior over N real events as goldens.
2. Edit policies (or hand the driver to the shim developer).
3. `test-all` → every case where the new policies diverge from the captured
   baseline shows up as a `FAIL`, with the diff.

That is "prove my change is safe" at the scale of real history, not three
hand-authored events.

### CLI

```
bin/sim harvest <outDir> [source + filter keys] [config source] [--channel subscriber]
```

The source and filter keys are the **same ones `dbevents`/`extract` already
accept**, so harvest is a thin orchestration over existing readers:

```properties
# harvest.properties (or flags) — pick events from the Event Logger DB
db=jdbc:postgresql://host:5432/idmEvent
dbUser=…  dbPassword=…
eventsDriver=cn=CyberArk,cn=driverset1,o=system
eventType=modify   eventLimit=200   eventOrder=desc

# the policies to run them through (one config source, as in any case)
export=../CyberArk.xml
channel=subscriber
```

### What it writes

For each selected event, a self-contained case directory:

```
<outDir>/
  0001-modify-jdoe/
    case.properties        # config source + channel, copied from the harvest config
    input.xds              # the real event (from dbevents/extract)
    directory.xds          # seed data, when the source provides it (trace queries; see below)
    expected-output.xds    # ← the CURRENT engine output, captured as the golden
  0002-modify-asmith/ ...
  HARVEST.md               # provenance: source, filters, when, engine/policy identity, counts
```

- **`input.xds`** is the faithful real event — from `DbEventReader` (the `xmlevent`
  column) or `TraceExtract` (the first operation of each transaction).
- **`expected-output.xds`** is produced by running the chain *now*. Harvest is
  **only ever a baseline of current behavior** — it records what the policies do
  today, which is the definition of the regression anchor. (It does **not** claim
  the output is *correct* — see "Honesty" below.)
- **`directory.xds`**: when harvesting from a trace, the mined query responses seed
  it (as `extract` already does). When harvesting from the DB (events only, no
  query data), the case relies on `ldap=` (live answers) or runs query-light; the
  `HARVEST.md` records which, and cases whose policies issue unanswerable queries
  are flagged.

### Key design decisions

- **One case per event — never coalesced.** Same rule the DB source already
  enforces: each real transaction is its own case with its own golden. Reuses the
  per-event materialization in `DbEventReader`/`dbevents`.
- **Capture = run + record, reusing existing code.** Harvest calls the same
  channel run and the same golden-write path `bin/sim record` uses; it adds
  orchestration (iterate events → make dirs → write `case.properties`), not new
  engine logic.
- **Provenance is mandatory.** `HARVEST.md` records the source, the exact filters,
  the config source identity (export file + a hash, or driver DN), the channel, the
  count, and that the goldens are *captured current behavior*. A regression corpus
  whose origin is unknown is untrustworthy.
- **Deterministic ordering & names.** `NNNN-<type>-<cn>` (stable, sorted) so reruns
  and diffs are reviewable; no timestamps in the case identity (keeps goldens
  reproducible — ties into the deterministic-clock backlog item for time tokens).
- **Re-harvest is explicit.** Writing into an existing `<outDir>` requires
  `--refresh` (re-capture goldens for existing cases — i.e. accept current behavior
  as the new baseline) so you never silently overwrite a reviewed baseline.

### Honesty / fidelity gaps

- **A harvested golden encodes *current* behavior, not *correct* behavior.** If
  today's policy is buggy, harvest bakes the bug in as "expected." It is a *change
  detector*, not a correctness oracle. `HARVEST.md` and the docs must say so
  plainly. (The correctness oracle is backlog #10, DxCMD Phase 2: diff the harvested
  output against what the *live engine* produces for the same event.)
- **DB-sourced cases have no query/seed data.** Policies that read the IDV during
  processing need `ldap=` (live) at harvest time, or their output is partial.
  Harvest flags such cases rather than emitting a misleading golden.
- **Subscriber-out, as elsewhere.** Publisher *policy* chains harvest fine; shim
  execution stays subscriber-only.

### Implementation

- **`Harvester`** — orchestration: resolve the source (delegate to `DbEventReader`
  or `TraceExtract`), iterate events, for each: write `input.xds` (+ `directory.xds`
  when available) + a `case.properties` derived from the harvest config, run the
  chain, write `expected-output.xds`, append to `HARVEST.md`.
- **`Cli`: `harvest`** subcommand — parse the harvest config (a `.properties` file
  and/or flags), call `Harvester`, print a summary (`200 events → 200 cases, 7
  flagged query-light`).
- Most logic is reuse; the genuinely new, unit-testable surface is the
  case-directory writer and the `HARVEST.md`/flag logic (offline, with stub
  events). End-to-end validation uses the live DB (as `dbevents` did) → `test-all`
  the result → confirm a deliberate policy edit turns specific cases red.

---

## Why this is the right next step

Every building block exists; the new code is orchestration + reporting, not engine
work. Together they deliver the headline promise — **safe policy change at the
scale of real production events** — and they make the shim-developer loop
(`docs/shim-dev-workflow.md`) and any policy refactor regression-testable in CI.
Feature 4 (`--json`), Feature 5 (assertion DSL), and Feature 10 (Phase 2 as a
correctness oracle) layer on top of this naturally.
