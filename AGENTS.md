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
bin/sim dxcache <caseDir>            read a stopped driver's event cache (live) into the case
bin/sim dbevents <caseDir>          list/pick logged events from the Event Logger DB
bin/sim doctor                       setup self-check
```

## The loop

1. **Get inputs** — `bin/sim extract <trace> <caseDir>` mines a production DSTrace
   log into a runnable case (real input event + directory state), or hand-author a
   case directory. No trace? Seed the fake directory from an LDIF dump with
   `ldif=<file>` in `case.properties`. **If you're missing the config, the input
   event, or the seed data the policies look up, ask the user for it and say how to
   produce it — don't run on empty data or guess.** The skill's "If an input is
   missing, ask" section maps each gap to what to request.
2. **Point at the driver** — set the chain source in `<caseDir>/case.properties`
   and `channel=publisher|subscriber`. Three sources: `export=<driver.xml>`, a
   Designer `project=<dir>`+`driver=<name>`, or — often easiest — an
   `ldifConfig=<vault.ldif>`+`driver=<name>` (one live-vault LDIF dump carries the
   whole driver set's policies, GCVs, filter, and shim params). The harness
   assembles the real chain and loads GCVs, ECMAScript resources, and `Map` token
   mapping tables (auto-extracted from the config source, or a case `mapping-tables/`
   dir).
3. **Diagnose** — `bin/sim step <caseDir>` (add `--rules`) to find the stage/rule
   where a value first appears, gets vetoed, or comes out wrong; read its trace.
4. **Verify a change** — `bin/sim record` a golden, edit the policy, `bin/sim test`.
5. **(Optional) production fidelity** — `shim=true` drives the real connector with
   the chain's output; `ldap=ldaps://…` answers queries from live eDir. Off unless
   set. See the skill's `reference/xds-and-cases.md`.

## Full instructions

The complete guide — case format, XDS event/instance/query shapes, the in-memory
fake directory, trace reading, ECMAScript / XSLT / Java extension support, and
troubleshooting — lives in `.claude/skills/dirxml-policy-testing/SKILL.md` and its
`reference/` files. **Read those before authoring inputs or diagnosing a result.**

A plain-English overview and example asks are in `docs/intro.md`; a step-by-step
walkthrough is in `docs/quickstart.md`.
