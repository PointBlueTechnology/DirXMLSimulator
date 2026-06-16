# DirXML Policy Simulator

A headless, agent-drivable test harness for NetIQ / OpenText Identity Manager
(DirXML) **channel policies**. It runs the IDM engine's *own* policy interpreter
(`DirXMLScriptProcessor`, XSLT, schema mapping) against author-supplied inputs —
no eDirectory, no running engine, no Designer — and lets you **step** through a
channel stage by stage, examining the document, trace, and directory
interactions at each step.

Higher fidelity than Designer's manual Policy Simulator (it's the real engine),
and scriptable so an agent can author inputs, run, read the trace, edit the
policy, and re-run in a loop.

## What it does

- **Runs real policies headlessly** — the engine's `DirXMLScriptProcessor` with
  mocked query/command seams and a null Driver.
- **Per-stage stepping** — each channel stage is driven individually, capturing
  the XDS document entering and leaving it, the rule trace it produced, and the
  queries/commands it issued.
- **In-memory fake directory** — answers the queries a policy makes
  (`token-query`, `do-find-matching-object`, source/dest attribute reads) from
  loaded `<instance>` state, and absorbs write-back commands.
- **Reads driver exports** — point at a Designer "Export Driver Configuration"
  file and the harness assembles the actual subscriber/publisher chain in IDM
  policy-set order (event → matching → create → placement → command → schema
  mapping → output transform, etc.).
- **Golden tests** — compare final output (and directory end-state) against
  recorded goldens; non-zero exit on mismatch.

## Requirements

- **JDK 21** — the 4.10.1 engine jars are Java 21 bytecode.
- Maven. Proprietary NetIQ jars go in `lib/` (gitignored — see `lib/README.md`).

## Build & test

```bash
export JAVA_HOME=.../zulu-21
mvn test
```

## CLI

```bash
bin/sim run    <caseDir> [--trace]   # run chain, print final output (+ trace)
bin/sim step   <caseDir>             # per-stage input/output/queries/trace
bin/sim test   <caseDir>             # diff vs expected-*.xds; exit !=0 on mismatch
bin/sim record <caseDir>             # write expected-output.xds / expected-directory.xds
```

### Case layout

```
cases/<name>/
  case.properties        # driverDN, dnFormat, fromNDS, traceLevel; OR export=..,channel=..
  chain.txt              # ordered stages: "stageName = policy.xml" per line
  input.xds              # the operation to run
  directory.xds          # optional: initial fake-directory state (<instance> set)
  gcv.xml                # optional: GCV definitions
  expected-output.xds    # golden (written by `record`)
  expected-directory.xds # optional golden: directory end-state
```

To drive the chain from a real driver export instead of `chain.txt`, set in
`case.properties`:

```
export=../../MyDriver.xml
channel=publisher        # or subscriber
```

## Layout

`src/main/java/com/pointblue/dirxml/sim/`
- `EngineContext` — builds the headless `RuleStaticContext` + captured trace.
- `CaptureEngineTrace` — captures the rule-by-rule policy trace to a buffer.
- `PolicyLoader` / `PolicyStage` — load a `<policy>`/`<style-sheet>`/`<attr-name-map>`
  and wrap it as a channel stage.
- `FakeDirectory` — in-memory directory implementing the query/command seams.
- `ChannelSimulator` — drives an ordered stage list, capturing `StageSnapshot`s.
- `DriverExport` — assembles channel chains from a Designer driver export.
- `Case` / `XmlCompare` / `Cli` — the case model, golden compare, and CLI.
