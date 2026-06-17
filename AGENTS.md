# Agent guide — DirXML Policy Simulator

This repository is a **headless test harness for NetIQ / OpenText Identity Manager
(DirXML) channel policies**. When you are asked to test, debug, trace, or iterate
on IDM / DirXML policies, rules, style-sheets, schema mapping, matching,
placement, or creation logic, **use this harness via the `bin/sim` CLI** — it runs
the real IDM engine. Do not reimplement policy behavior.

## Setup

Requires **JDK 21** and nine proprietary NetIQ jars in `lib/` (see the README for
the list and where they come from). Verify with:

```
bin/sim doctor      # -> DOCTOR: OK
```

## Commands

```
bin/sim run    <caseDir> [--trace]   run the chain; final output (+ full trace)
bin/sim step   <caseDir> [--rules]   per-stage (or per-rule) input/output/queries/trace
bin/sim test   <caseDir>             diff vs goldens; exit 0 pass, 1 mismatch
bin/sim record <caseDir>             write expected-output.xds / expected-directory.xds
bin/sim extract <trace> <outDir>     mine a DSTrace log into a case
bin/sim doctor                       setup self-check
```

## The loop

1. **Get inputs** — `bin/sim extract <trace> <caseDir>` mines a production DSTrace
   log into a runnable case (real input event + directory state), or hand-author a
   case directory.
2. **Point at the driver** — set `export=<driver.xml>` and `channel=publisher|subscriber`
   in `<caseDir>/case.properties`; the harness assembles the real chain and loads
   GCVs + ECMAScript resources.
3. **Diagnose** — `bin/sim step <caseDir>` (add `--rules`) to find the stage/rule
   where a value first appears, gets vetoed, or comes out wrong; read its trace.
4. **Verify a change** — `bin/sim record` a golden, edit the policy, `bin/sim test`.

## Full instructions

The complete guide — case format, XDS event/instance/query shapes, the in-memory
fake directory, trace reading, ECMAScript / XSLT / Java extension support, and
troubleshooting — lives in `.claude/skills/dirxml-policy-testing/SKILL.md` and its
`reference/` files. **Read those before authoring inputs or diagnosing a result.**

A plain-English overview and example asks are in `docs/intro.md`; a step-by-step
walkthrough is in `docs/quickstart.md`.
