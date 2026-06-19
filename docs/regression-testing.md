# Regression testing & CI/CD

Two commands turn the simulator from a one-event debugger into a **regression
safety net for your IDM policies** — and one you can run in CI on every change:

- **`bin/sim test-all <dir>`** — run a whole directory of cases as golden tests,
  with a pass/fail summary, a CI-friendly exit code, and optional JUnit/JSON
  reports.
- **`bin/sim harvest <configDir> <outDir>`** — mint that directory automatically by
  replaying **real events** (from the Event Logger DB) through your **current**
  policies and snapshotting the output as goldens.

Together: capture today's behavior over hundreds of real production events, then
prove a policy change doesn't alter any of them — automatically, on every commit.

---

## `test-all`: run a corpus

```bash
bin/sim test-all cases/regression
bin/sim test-all cases/regression --junit target/sim.xml --json target/sim.json
```

It discovers every case under the directory (a case is any folder with an
`input.xds`), runs each exactly as `bin/sim test` does (diff final output vs
`expected-output.xds`, and directory end-state vs `expected-directory.xds` when
present), and prints:

```
  PASS  add-user
  PASS  modify-email
  FAIL  terminate-employee   output: 3 nodes differ
  ERROR matching-change      IllegalArgumentException: missing chain.txt
  SKIP  draft-case
  ─────────────────────────────────
  18 cases: 16 passed, 1 failed, 1 error, 0 skipped, ...   (2.3s)
```

| Outcome | Meaning |
|---|---|
| **PASS** | ran; output matches the golden |
| **FAIL** | ran; output **differs** — behavior changed |
| **ERROR** | couldn't run (broken case, or a configured shim/LDAP host unreachable) |
| **SKIP** | no `expected-output.xds` yet — record one to start asserting |

`FAIL` and `ERROR` are kept distinct so CI can tell "a policy changed behavior"
from "a case is broken." **The command exits non-zero if anything FAILed or
ERRORed** — that exit code is the CI gate.

- `--junit <file>` writes JUnit XML — GitHub Actions, Jenkins, GitLab, and most CI
  systems render it as a test report with the diff on each failure.
- `--json <file>` writes a structured array for an agent or a custom dashboard.

## `harvest`: mint a corpus from real events

Hand-authoring goldens is slow. If the
**[DirXML Event Logger](https://github.com/jcombs-pointblue/DirXMLEventLogger)** is
recording your driver set, `harvest` builds the whole corpus from real traffic:

```bash
bin/sim harvest harvest-config/ cases/regression
```

`harvest-config/case.properties` holds the **event source + filters** (same keys as
`dbevents`) and a **config source** (the policies to run them through):

```properties
# where the real events come from (Event Logger DB) + which to take
db=jdbc:postgresql://192.168.103.7:5432/idmEvent
dbUser=postgres
dbPassword=…
eventsDriver=cn=CyberArk,cn=driverset1,o=system
eventType=modify
eventLimit=200          # newest 200 modify events for this driver

# the policies to replay them through (any config source)
export=../driver-config/CyberArk.xml
channel=subscriber
# …or read both config and query answers live:
# ldapConfig=cn=driverset1,o=system
# ldap=ldaps://host:636  ldapBindDn=…  ldapBindPassword=…  schema=ldap  driver=CyberArk
```

For each selected event it writes a self-contained case — the **real event** as
`input.xds`, a `case.properties` derived from your config source, and the
**current** engine output captured as `expected-output.xds`:

```
cases/regression/
  0001-modify-jdoe/   { case.properties, input.xds, expected-output.xds }
  0002-modify-asmith/ …
  HARVEST.md          # provenance: source, filters, config, counts, per-case notes
```

Then run them — first time, everything passes (the goldens *are* current
behavior):

```bash
bin/sim test-all cases/regression     # all PASS — this is your baseline
```

Now edit a policy and re-run: every event whose behavior changed turns up as a
`FAIL`, with the diff. That's "prove my change is safe" at the scale of real
history.

### Important: a harvested golden is a *change detector*, not a *correctness oracle*

It records what your policies do **today**. If a policy is buggy when you harvest,
the bug is baked in as "expected." Harvest tells you *what changed*, not *what's
right* — review the baseline before trusting it. (A future feature, DxCMD Phase 2,
will add a correctness oracle by diffing against the live engine.)

### Notes

- **One case per event — never coalesced.** Each logged transaction is its own
  case with its own golden, exactly as the `dbevents` source treats them.
- **Query data.** Policies that read the IDV during processing need answers: either
  point the config at `ldap=` (live) so harvest and replay answer queries from the
  vault, or provide a `directory.xds` seed in the config dir (copied into each
  case). Cases that issued queries with neither are flagged **query-light** in
  `HARVEST.md` — their goldens may be partial.
- **Re-baselining.** Harvesting into a non-empty directory needs `--refresh`, so a
  reviewed baseline is never silently overwritten. Use it when you *intend* to
  accept current behavior as the new baseline.
- **Subscriber channel** for shim-style output; publisher *policy* chains harvest
  too.

## `compare`: diff two policy versions directly

When you don't have goldens yet — or just want a quick "did this edit change
anything?" — `compare` runs the **same input through two policy sets** and shows
where they diverge, stage by stage:

```bash
bin/sim compare cases/my-case --against ../driver-config/CyberArk-v2.xml
```

It runs the case as-is (**A** = whatever config source the case already declares —
`export=`, `project=`, `ldifConfig=`, or `ldapConfig=`) and again with that source
swapped for `--against` (**B**, the same *kind* of source), then reports:

```
compare cases/my-case
  A: export=../CyberArk.xml
  B: export=/abs/CyberArk-v2.xml
  ────────────────────────────────────────
  matching          same
  create            same
  command           DIFFERS   first difference at offset 412: …
  ────────────────────────────────────────
  final output: DIFFERS (first diverges at 'command')
  6 stages: 5 identical, 1 differ
```

- **Exit code**: 0 if the final output is identical, 1 if it differs — usable as a
  gate on its own.
- **Per-stage view pinpoints the rule set that first changed the result** — and it
  surfaces the subtle case where *intermediate* stages diverge but the chains
  reconverge to an identical final output.
- Ideal for **two git revisions of the same export** (check one out as
  `--against`), or an edited copy vs the committed one — no goldens to record first.

`compare` complements goldens: use it for ad-hoc "what changed" exploration;
use `harvest` + `test-all` for the standing regression gate.

## Assertions: pin one behavior without a full golden

A full-document golden is exact but brittle — an incidental change anywhere fails
it. When you'd rather assert *one specific thing* ("it sets Email", "it did NOT
touch Surname", "it vetoed"), add an **`expected.assertions`** file to the case.
`test` and `test-all` evaluate it against the final output; it can stand alone or
sit alongside a golden.

One assertion per line — `<verb> <xpath> [=> <value>]` (the `=>` lets the XPath and
value contain spaces); `#` comments and blank lines are ignored:

```
# expected.assertions
not-vetoed                                              # at least one operation survived
exists   //modify-attr[@attr-name='Email']             # Email was modified
absent   //modify-attr[@attr-name='Surname']           # Surname was NOT touched
equals   //add-attr[@attr-name='Given Name']/value => Jane
count    //modify => 1                                  # exactly one modify op
matches  //add-attr[@attr-name='dob']/value => \d{8}    # date is yyyyMMdd
vetoed                                                  # (the opposite) nothing survived
```

| Verb | Passes when |
|---|---|
| `exists <xpath>` | at least one node matches |
| `absent <xpath>` | no node matches |
| `equals <xpath> => v` | first match's text equals `v` |
| `matches <xpath> => re` | first match's text matches regex `re` |
| `count <xpath> => n` | exactly `n` nodes match |
| `vetoed` / `not-vetoed` | no / at least one `add\|modify\|delete\|rename\|move` survived |

A case with only `expected.assertions` (no golden) is still a real test — it
PASSes/FAILs on the assertions, not SKIP. Assertions read robustly: they ignore
attribute ordering and unrelated parts of the document, so they survive policy
edits that a full golden would flag. Use a golden for "nothing at all changed,"
assertions for "this specific thing is true."

## Rule coverage: find dead or untested policy

Over a corpus, which rules actually fired? `coverage` runs every case under a
directory, reads the engine trace for each, and reports which DirXML Script rules
fired vs which are defined — surfacing rules that **never fire** (candidate dead
logic, or gaps in your test corpus):

```bash
bin/sim coverage cases/regression
```

```
rule coverage: 47/52 fired (90%) across 30 case(s)
  never fired (5):
    publisher-command:pub-ctp_Event Transforms
      - suppress legacy region codes
      - VIP override
    …
```

- "Fired" means the rule's **actions ran** (from `Applying rule '…'` in the trace);
  a rule whose condition was always false shows as never-fired — exactly what you
  want to find.
- Run it over a harvested corpus to ask "does real production traffic exercise this
  rule at all?" A rule that never fires across hundreds of real events is either
  dead or genuinely conditional — worth a look either way.
- `--json` emits per-rule `{stage, rule, fired}` plus totals for a dashboard.

> Matching is by rule name (the trace doesn't carry the owning policy), so a rule
> name reused across stages counts as covered if it fired in any of them.

## Machine-readable output (`--json`)

`run`, `step`, `test`, and `compare` accept `--json` (and `test-all` takes
`--json <file>`), emitting one structured object/array instead of human text — so
an **agent or script can parse the result** instead of scraping the console:

```bash
bin/sim test cases/my-case --json
# {"command":"test","case":"…","result":"PASS","output":{"checked":true,"equal":true,"diff":null},"directory":…}

bin/sim step cases/my-case --json | jq '.stages[] | select(.changed)'   # only the stages that changed
bin/sim compare cases/my-case --against v2.xml --json | jq '.finalSame'
```

`step --json` carries each stage's input/output XDS, queries, commands, trace, and
error; `run --json` the per-stage summary and final output (plus trace with
`--trace`). This is what lets an agent drive the edit → run → read → edit loop
without brittle text parsing.

## Putting it in CI/CD

The payoff: **policy changes get the same regression gate as application code.**
IDM policies are normally tested by hand in Designer, one event at a time, with no
safety net. With these two commands, a driver's policy repo (or the Designer
export checked into one) gets a real CI pipeline.

A typical flow:

1. **Seed the baseline once** — `harvest` a representative slice of production
   events (per driver, per channel), review `HARVEST.md`, and commit the corpus
   alongside the policies. (Treat the cases as operational data — see the
   sensitivity note below.)
2. **Gate every change** — CI runs `test-all` on each push/PR. A policy edit that
   alters any captured behavior fails the build with the exact diff.
3. **Refresh deliberately** — when a behavior change is *intended*, re-`harvest
   --refresh` (or `record` the specific cases) and commit the new goldens in the
   same PR, so the diff is reviewed like any other change.

### Example: GitHub Actions

```yaml
# .github/workflows/idm-policies.yml
name: IDM policy regression
on: [push, pull_request]
jobs:
  test-all:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: zulu, java-version: '21' }
      # The proprietary NetIQ jars aren't redistributable — restore them from a
      # secure cache/secret into lib/ (never commit them).
      - name: Restore IDM jars
        run: ./ci/fetch-idm-jars.sh        # your script -> lib/*.jar
      - name: Run the policy regression suite
        run: bin/sim test-all cases/regression --junit target/sim.xml
      - name: Publish report
        if: always()
        uses: mikepenz/action-junit-report@v4
        with: { report_paths: target/sim.xml }
```

The build is red the moment a policy change moves any real event's output; the
JUnit report shows which cases and how. The same `test-all … --junit` line drops
into Jenkins (`junit` step), GitLab (`artifacts: reports: junit`), or any runner.

> **CI fidelity.** `test-all` runs the *real* IDM engine, so the regression result
> is exactly what production policy execution would do — not an approximation.
> Cases that need a **live** connection (`ldap=` query answers, a real `shim=`)
> require that host to be reachable from the runner; for a hermetic CI, harvest
> with a `directory.xds` seed (or `ldif=`) so the cases are self-contained and need
> no live services.

### Sensitivity

Harvested cases contain **real event data** from your environment (DNs, attribute
values). Treat the corpus like any other sensitive artifact: commit it only to an
appropriately private repo, scrub or synthesize where needed, and keep credentials
(`dbPassword`, `ldapBindPassword`) out of committed `case.properties` — the harness
gitignores the local test cases that hold them by default.

---

*See also: the design rationale in
[regression-suite-design.md](regression-suite-design.md), and the case format in
the skill's
[`reference/xds-and-cases.md`](../.claude/skills/dirxml-policy-testing/reference/xds-and-cases.md).*
