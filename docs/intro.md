# Let an AI agent test and debug your IDM policies

**DirXML Policy Simulator** is a skill that turns policy testing — today a slow,
manual chore — into something an AI agent does for you. Hand the agent your driver
config — a driver export, a Designer project, the **live vault over LDAP**, or an
**LDIF export** — plus real events from a trace, a driver's **event cache**, or your
**Event Logger database**, ask a plain-English question, and it runs your *actual*
policies through the *real* Identity Manager engine, reads the result, finds the
problem, and can propose and verify a fix.

No eDirectory. No running driver. No clicking through Designer one event at a time.

---

## The problem it solves

IDM policies are the brittle heart of every driver — matching, placement,
creation, schema mapping, command and event transforms, entitlement logic. When
something syncs wrong, finding out *why* means:

- hand-crafting input events in Designer's Policy Simulator, one at a time;
- guessing what the directory would have returned for each query;
- stepping a single policy manually and squinting at a trace log;
- having no way to *prove* a change didn't break the cases that already worked.

It's manual, it doesn't scale, and there's no regression safety net.

## What the agent can do for you

Because the skill drives the engine's own policy interpreter headlessly, an agent
can:

- **Run a real event through your real channel** — the whole subscriber or
  publisher chain (event → matching → create → placement → command → schema
  mapping → output transform), assembled straight from your driver export, your
  Designer project, the **live vault over LDAP**, or an **LDIF export** of it.
- **Drive it with real events, not hand-crafted ones** — pulled from a DSTrace, a
  stopped driver's queued **event cache**, or a searchable history in the **Event
  Logger database** (by object, driver, type, or date).
- **Step it stage by stage, or rule by rule** — and show you the exact document
  before and after each policy, every query it issued, and the engine trace —
  pinpointing the rule that dropped, vetoed, or mistranslated a value.
- **Answer the directory's queries for you** — an in-memory fake directory,
  seeded from real data in your trace **or an LDIF dump of your vault**, responds
  to the lookups your policies make (matching, attribute reads) so they behave as
  they do in production. Or point it at **live eDirectory over LDAP** and queries
  are answered from the running vault.
- **Validate inputs against the real schema** — loaded from a Designer project, a
  schema file, or read **live from eDirectory** — so typo'd classes/attributes are
  caught before they mislead you.
- **Test a change and prove it's safe** — capture the current correct output as a
  golden, make the policy edit, and re-run to confirm nothing regressed.
- **Validate against the real connector** — optionally hand the policies' final
  command to the **actual driver shim** to confirm it consumes what your policies
  produced (REST/SCIM/SOAP/JDBC and other pure-Java connectors).
- **Handle the hard stuff** — Global Config Values, ECMAScript (`es:`) functions,
  XSLT stylesheets, and Java extension functions all execute; missing pieces are
  reported clearly instead of failing silently.

All of it is scriptable and repeatable, so the agent can iterate — change a
policy, re-run, read the trace, change again — without you in the loop for every
turn.

## The basic process

```
  ┌──────────────────────┐   ┌──────────────────────┐   ┌────────────────────────┐
  │ driver config:       │   │ real events + data:  │   │ "Why isn't the email   │
  │ live LDAP, LDIF,     │ + │ trace, event cache,  │ + │  attribute syncing?"   │
  │ export, or project   │   │ event DB, or LDIF    │   │                        │
  └──────────────────────┘   └──────────────────────┘   └────────────────────────┘
         │                            │                            │
         ▼                            ▼                            ▼
   real policy chain          realistic inputs +          the agent runs, steps,
   + GCVs + scripts           directory state +           reads the trace, finds
   (+ schema)                 schema                       the rule, proposes a fix
```

1. **Give the agent the driver config** — read live over LDAP, or from an LDIF
   export, a Designer driver export, or a Designer project. It assembles your real
   channel chain with its filter, GCVs, ECMAScript resources, and (where available)
   the schema.
2. **Give it real events and data** — an input event from a trace, a stopped
   driver's event cache, or the Event Logger database; and the directory data its
   queries look up, from the trace, an LDIF dump, or live eDirectory. The test
   reflects actual data, not a guess.
3. **Ask it something** in plain English (see below). The agent builds the test
   case, runs it, and reports what happened — and can edit the policy and re-run
   to validate a fix.

## What you provide, and how to get it

### A driver's configuration — four ways

Any of these gives the agent the real driver config (policy chain, GCVs, filter,
shim parameters); pick whichever you have.

- **The live Identity Vault, read directly over LDAP — nothing to export.** Point
  the agent at the DriverSet and it reads the driver subtree straight from the
  running vault, *and* gets the eDirectory **schema** and live **query answers** in
  the same connection.
- **An LDIF export of the vault — one file, the whole driver set.** A single LDIF
  dump of the DriverSet subtree carries the policy chain, GCVs, filter, and shim
  parameters, *and* — from the same file or a companion dump — realistic
  **directory data** to seed the fake directory. No Designer, no per-driver export.
- **A driver export.** In **Designer**: right-click the driver → **Export to
  Configuration File**. That single `.xml` contains the policy chain, schema
  mapping, filter, GCVs, and ECMAScript resources.
- **A Designer project on disk.** Point the agent at your `designer_workspace`
  project and name the driver — no export step. The agent walks the project to
  assemble the same chain, **plus** the eDirectory **schema**.

Any one of them is the only required artifact to run your real policies.

### A trace (optional but recommended)

A standard **DSTrace** / driver trace log from your environment. Turn the driver
trace level up (e.g. 3+), reproduce the event, and save the trace. The agent
extracts from it:

- the **input event** that drove the channel (a real add/modify/delete);
- the **directory's responses** to the policies' queries (real instance data);
- every document the engine produced at each step, for reference.

A single per-transaction trace is the easiest starting point; a full driver log
works too (the agent picks the first event and merges the directory data).

### Or: a stopped driver's event cache (no trace needed)

If you have a live connection, the agent can read a **stopped** driver's **event
cache** — the real subscriber-channel transactions queued up waiting for it — and
turn them straight into test inputs. No trace capture, no hand-authoring: point it
at the driver and it pulls the actual pending events. (Running drivers are detected
and reported — stop the driver first.)

### Or, best of all: the Event Logger database

If the **[DirXML Event Logger](https://github.com/jcombs-pointblue/DirXMLEventLogger)**
is deployed — a small driver that records every event passing through a driver set
to PostgreSQL — you have a standing, **searchable history of real events**. The
agent queries it by object DN, driver, event type, class, or date ("the last 10
modify events on this user," "every add the AD driver saw last week"), and each
matched event is a real, ready-to-run transaction. It's the richest input source:
real production traffic, already captured, precisely selectable — and it survives
driver restarts (unlike the cache) without ever turning trace levels up.

### Directory data and schema

The answers to the policies' **queries** come from your trace, an **LDIF dump** of
the relevant objects, or **live eDirectory** — whichever you have. And a **schema**
(from a Designer project, a schema file, or read **live from LDAP**) lets the agent
flag typo'd classes and attributes in your inputs before they mislead you.

> Driver exports, projects, LDIF/LDAP data, traces, and the event database are
> configuration and operational data from your environment — treat them like any
> other sensitive artifact. The tooling keeps them out of source control by default.

## Sources & integrations at a glance

Everything the simulator can read from or connect to. Mix and match — use a file
when that's all you have, or a live connection when you want the richest data.

**Driver configuration** (policies, GCVs, filter, shim params)
| Source | How |
|---|---|
| Live Identity Vault, over LDAP | reads the DriverSet subtree directly |
| LDIF export of the vault | one file, the whole driver set |
| Designer driver export (`.xml`) | the classic config file |
| Designer project on disk | walks the project; also gives the schema |

**Input events** (what drives the channel)
| Source | How |
|---|---|
| DSTrace / driver trace log | mined into a runnable case |
| A stopped driver's event cache | real queued transactions, read live (DxCMD over LDAP) |
| The Event Logger database | searchable history; pick events by DN/driver/type/date |
| Hand-authored XDS | when you want a specific case |

**Directory data** (answers to the policies' queries)
| Source | How |
|---|---|
| A trace | the directory's real responses, mined automatically |
| LDIF dump of objects | loaded as instance state, normalized to native form |
| Live eDirectory | queries answered from the running vault |
| Hand-authored instances | for a controlled fixture |

**Schema** (validate inputs)
| Source | How |
|---|---|
| Designer project | automatic |
| Schema file or project dir | explicit |
| Live eDirectory subschema | read over LDAP (recovers real names + syntaxes) |

**Live integrations** (optional, opt-in)
| Integration | What it adds |
|---|---|
| The real driver shim | run the policies' output through the actual connector to confirm it's accepted |
| Live eDirectory (LDAP) | answer queries, read the schema, and read driver config from the running tree |
| DxCMD (LDAP extended ops) | read a stopped driver's event cache |
| Event Logger DB (PostgreSQL) | query the captured-events history |

## Examples of what you could ask

- *"Run this employee-add event through the publisher channel and show me where
  the `Given Name` gets set."*
- *"The manager's email isn't syncing to the app. Step the subscriber channel and
  find the rule that's dropping it."*
- *"I'm changing the matching policy to match on `workforceID` instead of email.
  Prove it still matches the existing accounts in this trace."*
- *"This driver collects 17,000 accounts. Walk a terminated-employee modify
  through the command transform and tell me whether it would create an active
  account."*
- *"Here's an output-transform stylesheet I edited — run a sample event through it
  and confirm the date format comes out as `yyyyMMdd`."*
- *"Why is this rule vetoing the operation? Step it rule by rule and read the
  condition that's failing."*
- *"Capture the current output as a baseline, then tell me if my policy change
  alters it for any of these three sample events."*

## How it compares to Designer's simulator

| | Designer Policy Simulator | This skill (agent-driven) |
|---|---|---|
| Fidelity | Simulated | The **real** IDM engine interpreter |
| Inputs | Hand-crafted, one at a time | **Real events** from a trace, a driver's event cache, or the event-log database |
| Directory queries | You stub each one manually | **Answered from real trace/LDIF data, or live LDAP** |
| Scope | One policy at a time | The **whole channel chain** from export, project, live LDAP, or LDIF |
| Fidelity options | — | Validate against the **real connector**; answer queries from **live eDirectory** |
| Iteration | Manual, click-through | **Automated** edit → run → verify loop |
| Regression safety | None | **Golden tests** with pass/fail |
| Who drives it | You | An **agent**, from a plain-English ask |

## Why you can trust the results

This isn't a reimplementation of policy behavior — it loads and runs the engine's
own `DirXMLScript`/XSLT/schema-mapping processors, the same code that runs in
production, with the same XPath, tokens, actions, GCV resolution, and ECMAScript
and Java extension functions. When the agent says a rule vetoes an event or a
value comes out a certain way, it's because the real engine did exactly that.

---

*Ready to use it? See the [README](../README.md) for setup and the `bin/sim`
commands, and the skill's `SKILL.md` for how the agent drives it.*
