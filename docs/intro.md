# Let an AI agent test and debug your IDM policies

**DirXML Policy Simulator** is a skill that turns policy testing — today a slow,
manual chore — into something an AI agent does for you. Hand the agent your driver
export and a trace from your environment, ask a plain-English question, and it
runs your *actual* policies through the *real* Identity Manager engine, reads the
result, finds the problem, and can propose and verify a fix.

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
  mapping → output transform), assembled straight from your driver export.
- **Step it stage by stage, or rule by rule** — and show you the exact document
  before and after each policy, every query it issued, and the engine trace —
  pinpointing the rule that dropped, vetoed, or mistranslated a value.
- **Answer the directory's queries for you** — an in-memory fake directory,
  seeded from real data in your trace, responds to the lookups your policies make
  (matching, attribute reads) so they behave as they do in production.
- **Test a change and prove it's safe** — capture the current correct output as a
  golden, make the policy edit, and re-run to confirm nothing regressed.
- **Handle the hard stuff** — Global Config Values, ECMAScript (`es:`) functions,
  XSLT stylesheets, and Java extension functions all execute; missing pieces are
  reported clearly instead of failing silently.

All of it is scriptable and repeatable, so the agent can iterate — change a
policy, re-run, read the trace, change again — without you in the loop for every
turn.

## The basic process

```
  ┌─────────────┐     ┌─────────────┐     ┌──────────────────────────┐
  │ driver       │     │ a trace from │     │  "Why isn't the email    │
  │ export (.xml)│  +  │ your env     │  +  │   attribute syncing?"    │
  └─────────────┘     └─────────────┘     └──────────────────────────┘
         │                    │                         │
         ▼                    ▼                         ▼
   real policy chain    realistic inputs +        the agent runs, steps,
   + GCVs + scripts     directory state           reads the trace, finds
                                                   the rule, proposes a fix
```

1. **Give the agent a driver export.** It reads your channels, policies, filter,
   GCVs, and ECMAScript resources, and assembles the real chain.
2. **Give it a trace** from your environment. The agent mines it for a real input
   event and the directory data your policies looked up — so the test reflects
   actual traffic, not a guess.
3. **Ask it something** in plain English (see below). The agent builds the test
   case, runs it, and reports what happened — and can edit the policy and re-run
   to validate a fix.

## What you provide, and how to get it

### A driver export

In **Designer**: right-click the driver → **Export to Configuration File** (or
export the driver configuration). That single `.xml` contains everything the agent
needs — the policy chain, schema mapping, filter, GCVs, and ECMAScript resources.

That's the only required artifact to run your real policies.

### A trace (optional but recommended)

A standard **DSTrace** / driver trace log from your environment. Turn the driver
trace level up (e.g. 3+), reproduce the event, and save the trace. The agent
extracts from it:

- the **input event** that drove the channel (a real add/modify/delete);
- the **directory's responses** to the policies' queries (real instance data);
- every document the engine produced at each step, for reference.

A single per-transaction trace is the easiest starting point; a full driver log
works too (the agent picks the first event and merges the directory data).

> Driver exports and traces are configuration and operational data from your
> environment — treat them like any other sensitive artifact. The tooling keeps
> them out of source control by default.

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
| Inputs | Hand-crafted, one at a time | **Mined from a real trace** |
| Directory queries | You stub each one manually | **Answered from real trace data** |
| Scope | One policy at a time | The **whole channel chain** from the export |
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
