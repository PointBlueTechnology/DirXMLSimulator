---
name: dirxml-policy-testing
description: >-
  Test and debug NetIQ / OpenText Identity Manager (DirXML) channel policies with
  the DirXML Policy Simulator in this repo. Use when asked to test, debug, trace,
  or iterate on IDM/DirXML policies, rules, style-sheets, schema mapping, matching,
  placement, or creation logic — including running a driver export's policies
  against sample events, finding why an attribute/value is wrong or missing, or
  verifying a policy change. Drives the real engine headlessly (no eDirectory),
  steps a channel stage by stage, and answers the policy's queries from an
  in-memory fake directory.
---

# DirXML policy testing

This repo is a headless harness that runs the IDM engine's *own* policy
interpreter against sample inputs. You author an event and directory state, run a
policy chain, read the per-stage output and trace, edit the policy, and re-run —
all from the `bin/sim` CLI. It is higher fidelity than Designer's simulator (it's
the real engine) and scriptable.

## First: verify setup

Run the self-check before anything else:

```
bin/sim doctor
```

`DOCTOR: OK` means JDK 21, the engine jars, and a smoke run are all good. If it
reports problems:
- **JDK 21 missing** — the 4.10.1 engine jars are Java 21 bytecode. Install a
  JDK 21 or set `SIM_JAVA_HOME`.
- **engine jars INCOMPLETE** — the 9 proprietary NetIQ jars must be staged in
  `lib/` (they are gitignored). See `lib/README.md` for the list; copy them from
  an IDM install or an "IDM Driver Dependencies" set.
- Build with `mvn compile` (or `bin/sim` auto-builds on first run).

> **Where to run:** `bin/sim` lives in the harness/project directory. Run commands
> from there, or invoke it by full path (e.g. `/path/to/DirXMLSimulator/bin/sim`)
> from another working directory. `caseDir` paths are relative to your cwd. On
> **Windows** use `bin\sim.cmd` with the same arguments.

## Where inputs come from

Don't guess what an event or directory state should look like — **mine a
production trace**. Given a DirXML / DSTrace log, extract real events, queries,
and the directory's responses into a ready-to-run case:

```
bin/sim extract <traceFile> <outDir>
```

This writes `input.xds` (the real input event), `directory.xds` (instances the
directory actually returned, so the policy's lookups resolve), a
`case.properties` stub with the channel inferred, and `trace-samples/` (every
document the trace carried, labeled by channel + kind). Then set `export=` in
`case.properties` and `step` it. See
[reference/traces.md](reference/traces.md) for the trace format, what each
document marker means, and how to read the rule trace to debug.

## The loop

1. **Create a case** — `extract` from a trace (above), copy `cases/copy-surname`,
   or hand-author a directory under `cases/`. See "Case layout" below.
2. **Run it**: `bin/sim run <caseDir>` (final output + per-stage summary), or
   `bin/sim step <caseDir>` to see every stage's input→output, the queries it
   issued, and its rule trace.
3. **Diagnose** from the step output: find the stage where a value first appears,
   gets vetoed, or comes out wrong. Read that stage's `TRACE`. To narrow within a
   policy, `bin/sim step <caseDir> --rules` expands each DirXML Script policy into
   one snapshot **per rule**, so you see exactly which rule changed the document or
   vetoed it. (Caveat: a `scope="policy"` local variable set in one rule isn't
   visible to a later rule when stepped per-rule — driver-scoped locals persist;
   the rule trace is always accurate. Use whole-policy `step` if a policy relies on
   policy-scoped locals across rules.)
4. **Edit the policy** (the `.xml` file the case points at — a real Designer
   `*_contents.xml`, an exported policy, or one you write).
5. **Re-run**, or `bin/sim test <caseDir>` to check against a recorded golden.

## Commands

```
bin/sim run    <caseDir> [--trace]   # run chain; final output (+ full trace)
bin/sim step   <caseDir> [--rules]   # per-stage (or with --rules, per-rule) i/o/queries/trace
bin/sim test   <caseDir>             # diff vs expected-*.xds; exit 0 pass, 1 mismatch
bin/sim record <caseDir>             # write expected-output.xds / expected-directory.xds
bin/sim extract <trace> <outDir>     # mine a DSTrace log into a case (input + directory + samples)
bin/sim doctor                       # setup self-check
```

`test` is the agent signal: exit 0 = pass, exit 1 = mismatch (with a diff). Seed a
golden once the output looks right with `record`, then `test` guards regressions.

## What each command prints (so you can interpret it without a trial run)

- **`doctor`** — a checklist (`java.version`, `engine jars`, `engine smoke run`,
  `sample case`) ending in `DOCTOR: OK` (exit 0) or `DOCTOR: PROBLEMS FOUND`
  (exit 1), naming any missing class/jar.
- **`run`** — `# stages: N`, then one line per stage
  `- <name> [changed]|[no-op] [queries=N] [commands=N]`, then
  `=== final output ===` and the final XDS. With `--trace`, also `=== trace ===`
  and the full rule trace. Exit 0.
- **`step`** — per stage, a block:
  ```
  STAGE: <name>  [changed] | [no-op] | [ERROR]
  INPUT:  <xds>            OUTPUT: <xds>
  QUERIES (n): …           COMMANDS (n): …      TRACE: …
  ```
  With `--rules`, DirXML Script stages expand to `STAGE: <stage> ▸ #N <rule desc>`.
  A failed stage shows `[ERROR]` + an `ERROR: <message>` line and the run stops
  there (earlier stages still shown) — it does not crash.
- **`test`** — `output: PASS|FAIL|SKIP`, a first-difference diff on FAIL, optional
  `directory: PASS|FAIL`, then `RESULT: PASS|FAIL`. Exit 0 pass, 1 mismatch.
- **`extract`** — `parsed N XDS documents`, per-kind counts (event/query/
  query-result/policy-returned/command/response), and which of `input.xds` /
  `directory.xds` / `case.properties` / `trace-samples/` it wrote.
- **Missing Java extension class** — `run`/`step`/`test` print a
  `WARNING: Java extension classes not on the classpath: …` up front; calls to
  them then surface as a stage `[ERROR]` ("function not found").
- **Named passwords** — supply with `namedPassword.<name>=<value>`; a referenced
  name with no value prints `WARNING: named password(s) referenced but not supplied`
  and resolves to empty.
- **Faked external actions** — actions that would make a live call (REST, email,
  RBPM role/resource SOAP, workflow, XDAS, SSO) are **faked by default**: no
  connection, recorded as `FAKED: <action> …` in the trace, and the policy
  continues. A `note: external actions are faked …` prints up front. For
  `do-invoke-rest-endpoint`, supply a canned body (`restResponse=…` /
  `restResponse.<urlSubstring>=…` / `rest-response.json`) and it's injected into
  `success.do-invoke-rest-endpoint` so downstream rules work. Set `fakeActions=false`
  to disable (then those actions attempt the real call and fail/hang). (**Entitlements
  are not external** — op-driven `DirXML-EntitlementRef` values; include the
  entitlement change in the input op.)

Operation outcomes to recognize in output: an empty `<input/>` means the operation
was **vetoed/stripped**; `[no-op]` means the stage's conditions didn't match or its
actions were inert (the trace shows which).

## Case layout

```
cases/<name>/
  case.properties        # config (see below)
  chain.txt              # ordered stages: "stageName = policy.xml" per line  (OR use export=)
  input.xds              # the operation to run (the event)
  directory.xds          # optional: initial fake-directory state (<instance> set)
  gcv.xml                # optional: GCV definitions (overrides export GCVs)
  ecmascript/            # optional: *.js defining es: functions the policies call
  rest-response.json     # optional: canned body for a faked do-invoke-rest-endpoint
  expected-output.xds    # golden (written by `record`)
  expected-directory.xds # optional golden: directory end-state
```

`case.properties` keys (all optional): `driverDN`, `dnFormat` (default `slash`),
`fromNDS` (default `true` = eDir→app / Subscriber-side), `traceLevel` (1–5);
`namedPassword.<name>=<value>` to supply a named password (a secret value, kept out
of exports — same idea as a GCV; referenced names you don't supply resolve to empty
and are warned); `fakeActions` (default `true`); and `restResponse=<body>` (or
`restResponse.<urlSubstring>=<body>`, or a `rest-response.json` file) to supply the
canned body a faked `do-invoke-rest-endpoint` returns.

Two ways to define the chain:
- **Explicit** — `chain.txt`, one stage per line in channel order.
- **From a driver export** — set in `case.properties`:
  ```
  export=../../MyDriver.xml
  channel=publisher      # or subscriber
  filter=true            # optional: prepend the driver's filter (drops ignored classes/attrs)
  ```
  The harness reads the Designer "Export Driver Configuration", assembles the
  real subscriber/publisher chain in IDM policy-set order, and loads that
  driver's GCVs and ECMAScript resources automatically. With `filter=true` it also
  prepends a filter stage that drops classes/attributes the driver filter ignores
  on that channel (off by default).

## Authoring inputs, directory state, and reading output

XDS event/instance/query shapes, the fake-directory model, channel order, trace
reading, and how to drive a real driver export are in
[reference/xds-and-cases.md](reference/xds-and-cases.md).

Common pitfalls (DOM Level 2, GCV `display-name` requirement, `fromNDS`
direction, empty-output vetoes, JDK 21) are in
[reference/troubleshooting.md](reference/troubleshooting.md). Read these before
hand-writing XDS or diagnosing a confusing result.

## Worked example

`cases/copy-surname/` is a complete, passing case: a policy that reads `Surname`
from the directory via a query and stamps a copy. Run `bin/sim step
cases/copy-surname` to see the issued `<query>`, the directory's answer, and the
value flowing into the output. Copy it as a starting template.
