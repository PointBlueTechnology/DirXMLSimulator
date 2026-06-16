# Reading and mining DirXML traces

A DirXML / DSTrace log is the richest source of both **realistic inputs** and
**debugging signal**. Use it two ways: mine it to bootstrap a case, and read it
to understand what the engine did.

## Trace format

Each line is `[MM/dd/yy HH:mm:ss.SSS]:<driver> <channel>:<message>`, e.g.:

```
[03/13/23 02:48:59.630]:UKG PT:Receiving DOM document from application.
[03/13/23 02:48:59.630]:UKG PT:
<nds dtdversion="2.0" ndsversion="8.x">
  <input> … </input>
</nds>
```

- **Thread/channel**: the part between `]:` and the next `:` is the thread name
  (it has no colons) — e.g. `UKG PT`, `CC ST`, `CC PS02 ST` (driver names can be
  multiple words). Its **last token** is the channel: `PT` = Publisher (app →
  eDir), `ST` = Subscriber (eDir → app), `DOMT` = a DOM/driver thread.
- A **document** is usually logged as a message line, then a header with an empty
  message, then the raw multi-line `<nds>…</nds>` body. It can also sit **inline**
  on the message line (e.g. `CCSubscriptionShim: input doc: <nds…>`).
- The document's meaning comes from the **nearest preceding message** (the text
  before the `<nds>`, or the last non-empty message).

## Document markers (what each `<nds>` is)

`extract` classifies each document into a **kind**:

| kind | from messages like | what it is |
|---|---|---|
| `event` | `Receiving DOM document from application`, `Processing events for transaction`, `… from eDirectory` | the input **event** entering the channel |
| `query` | `Query from policy` | a lookup the policy issued (`do-find-matching-object`, `token-query`, attr read) |
| `query-result` | `Query from policy result` | the directory's answer — **real instances** |
| `policy-returned` | `Policy returned:` | the document after a policy/rule set ran |
| `command` | `Submitting document to subscriber/publisher shim`, `Sending`, `Pumping`, `… input doc:` | the command **leaving** the channel toward the app/eDir |
| `response` | `…Shim.execute() returned:` | the shim's reply to a command |

The **input event** `extract` picks for `input.xds` is the first real operation
event in trace order (an `<input>` with add/modify/delete/rename/move, not a
query) — i.e. the document entering the channel, not the command leaving it.

## Bootstrap a case from a trace

```
bin/sim extract <traceFile> <outDir>
```

This parses every `<nds>` document, classifies it, and writes:
- **`input.xds`** — the first input event (the real event that drove the channel).
- **`directory.xds`** — instances merged from every `Query from policy result`
  (the real data the directory returned — seeds the fake directory so the
  policy's lookups resolve).
- **`case.properties`** stub — with `channel`/`fromNDS` inferred from the event's
  thread (PT ⇒ publisher).
- **`trace-samples/`** — every extracted document, numbered and labeled by
  channel+kind, so you can pick alternate events or inspect each stage's output.

For a **multi-transaction** log (a full driver trace, not a single transaction),
`input.xds` is the *first* event and `directory.xds` merges *all* query results;
per-document samples are capped at 300 (the summary says so). To target a specific
event, pick one from `trace-samples/*-event.xds` and copy it to `input.xds`. The
per-transaction trace files (named `…transaction-N.xml`) are already scoped to one
event and are the easiest to start from.

Then finish the case: set `export=…/YourDriver.xml` in `case.properties`, and
`bin/sim step <outDir>`. You're now replaying real traffic through the real
chain. The trace's `Policy returned` samples are useful as reference for what the
production engine produced at each step (and as a basis for `expected-output.xds`).

## Reading the rule trace for debugging

When you `step` a case (or read a production trace), the rule trace tells you
exactly what happened. Key lines:

- `Applying policy: <name>` / `Applying to add #1` — entering a policy / operation.
- `Evaluating selection criteria for rule 'X'.` then `Rule selected.` /
  `Rule rejected.` — whether a rule's conditions matched.
- `(if-op-attr 'Surname' available) = TRUE` / `= FALSE` — individual condition
  results. **A rule that didn't fire: find its `= FALSE` condition.**
- `Action: do-set-dest-attr-value("L","x").` — an action executing.
- `Token Value: "…"` / `Arg Value: "…"` — what a token/argument resolved to.
  **A wrong/empty value: trace it to the token that produced it** (e.g. a
  `token-global-variable` resolving empty ⇒ missing GCV; a `token-op-attr`
  resolving empty ⇒ the attr isn't on the operation).
- `Mapping attr-name 'Org Level 1' to 'aiOrgLevel1'.` — schema mapping renaming an
  attribute. **An attr that "disappeared" may just have been renamed.**
- `do-veto` / `do-strip-*` actions, or an empty `Policy returned:` ⇒ the operation
  was vetoed/stripped (often correct — check which rule did it and why).
- `Policy returned: <nds>…</nds>` — the document leaving that policy. Diff
  consecutive `Policy returned` docs to see what each stage changed.

## Debugging workflow

1. Reproduce: `extract` the trace → run `bin/sim step` on the bootstrapped case.
2. Find the stage where the document first goes wrong (value missing, vetoed,
   mistranslated) by scanning each stage's OUTPUT.
3. In that stage's TRACE, find the rule and the condition/token responsible.
4. Edit the policy; re-`step`. When correct, `record` a golden and `test`.
