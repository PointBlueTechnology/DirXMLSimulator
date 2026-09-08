# Plan: fully agent-driven IDM development (Designer-optional)

Status: **plan** (2026-09-08). The next horizon beyond the simulator: let an agent do
the **whole** IDM development loop — read, design, edit, validate, test, diff,
deploy, operate — without Designer in the loop, while still being able to hand a
Designer-compatible project to a human team when they want one.

## Framing: what "eliminate Designer" realistically means

Designer does five jobs. Two we've already replaced or exceeded; three are the work:

| Designer job | Status |
|---|---|
| **Read/model** a driver set (policies, GCVs, filters, schema map, resources, mapping tables, packages) | ✅ done — `DesignerProject` / `DriverExport` / `LdifDriverSource` / live LDAP, plus the `dirxml-designer-workspace` skill |
| **Test** policies (Policy Simulator) | ✅ exceeded — real-engine simulator, regression corpus, compare, coverage |
| **Edit/author** policies and config, keeping the project's cross-references intact | ❌ the core of this plan |
| **Deploy / compare** against the live vault | ❌ read is done; write + diff + safeguards are the work |
| **Operate** drivers (start/stop/restart, migrate, cache, trace, passwords) | ◐ cache read + state done; the rest is wiring ops we already have |

The realistic target is **Designer-*optional***, not Designer-forbidden: the agent
can do everything end-to-end, and the artifacts stay interoperable (import into
Designer from the vault, or — later — a faithful Designer-format writer) for teams
that keep using it. Driver/policy development comes first; **provisioning forms
(the form builder for PRDs) are in scope** as their own track (below); full
workflow-activity design is a later follow-on.

## The four hard problems (the ones you named)

### 1. Communication method — solved by protocol, not by Designer's code

Designer/iManager talk to the vault over **LDAP** (objects and attributes — the
`DirXML-*` classes, `XmlData`, `DirXML-Policies` linkage, `DirXML-ConfigValues`,
`DirXML-DriverFilter`, resources in `DirXML-Data`) plus **DirXML LDAP extended
operations** for engine actions. We already use both:

- LDAP read of the whole driver set (`JndiLdapSearch.readDriverConfig`) and schema.
- Extended ops via `dirxml_misc.jar` + `ldap.jar` (`DxCacheReader`).

`dirxml_misc.jar` carries the **full engine-control surface**: `StartDriver`,
`StopDriver`, `RestartDriver`, `GetDriverState`, `Set/GetDriverSet`,
`InitDriverObject`, `MigrateApp`, `DriverResync`, `SubmitEvent`/`SubmitCommand`,
`Set/Get/ListNamedPassword`, `GetDriverGCV`, `SetDriverStartOption`,
`DeleteCacheEntries`, jobs, activation, key management. So **deploy = LDAP writes
of the config objects + `RestartDriver` (or `InitDriverObject`) to pick them up**,
and operations = the ops above. No Designer code, no licensing entanglement beyond
the IDM jars we already depend on. (Designer's own deploy uses the same objects;
reusing its classes would add coupling and legal risk for no capability gain.)

*Spike to confirm:* LDAP-write a policy's `XmlData`, add a linkage to
`DirXML-Policies`, set a GCV in `DirXML-ConfigValues`, restart the driver, and
verify the engine runs the new policy — against the test vault. Expected to work;
the open detail is which objects require a restart vs are re-read live (mapping
tables have an `UpdateWatcher`; policies are loaded at driver start).

### 2. Safeguards — the simulator makes them *strong*, not just procedural

An agent writing to a production vault needs gates that a human clicking Deploy
doesn't have. Non-negotiable set:

- **Validate before anything**: DTD validation (`dirxmlscript4.10.dtd`,
  `dirxmlfilter.dtd`, `Driver.dtd`, entitlements, jobs — all on disk), XSLT
  compiles, ECMAScript parses, every policy-set linkage resolves, GCV references
  defined, mapping-table references present, schema-map/filter names exist in the
  schema. Most of these the simulator already detects at stage-build; make them a
  first-class `validate` step.
- **Prove before deploy**: run the regression corpus (`test-all` over a `harvest`ed
  baseline) and `compare` old-vs-new — the agent can *demonstrate* a change alters
  exactly the intended cases. This is the safeguard Designer can't offer.
- **Diff and dry-run against the vault**: structured, per-object diff of the model
  vs the live tree; a deploy *plan* you approve before any write.
- **Snapshot + rollback**: export the affected subtree (LDIF) before writing;
  `rollback` restores it. Every deploy is reversible.
- **Environment gating**: STG and PRD are distinct targets; PRD deploy requires an
  explicit confirmation and a green STG run; MCP tools carry destructive
  annotations so the client prompts.
- **Package awareness (overrides are the supported method)**: editing packaged
  content in place is the *normal* customization path — IDM tracks it with a
  **modified/customized flag** on the object (backed by the package baseline:
  `_initial_state.xml` in a project, `DirXML-pkgInitialState`/`DirXML-pkgChecksum`
  in the vault) so package upgrades know what to preserve. The tool must make an
  override **easy** and **set that flag correctly** every time — never a silent
  edit that an upgrade would later clobber. It should also report, on upgrade,
  which customized objects a new package version touches. (Exact flag attribute to
  confirm in the Phase 0 Designer-diff spike.)
- **Least privilege + audit**: a dedicated LDAP identity per environment; an
  append-only log of every write (who/what/when/diff), plus post-deploy re-read
  proving diff = empty.
- **Ground truth**: DxCMD Phase 2 (`SubmitEvent` to the live engine) to confirm the
  deployed policy behaves as the simulator predicted for a canary event.

### 3. Formatting — canonical serialization so diffs and Designer both stay happy

Every write goes through one canonical XML serializer: stable attribute order,
indentation, UTF-8 with declaration, consistent entity escaping, DirXML Script
element order per the DTD. Benefits: clean git diffs, byte-stable round-trips, and
output Designer parses without complaint. `XmlCompare.canonical` is the seed; the
writer is its counterpart.

### 4. References inside the project — an object model, not text edits

Designer's on-disk format is a graph: CObject metadata (`<ID>.<Type>_`) with
`relations` referencing other objects by `#ID.<Type>_`, `_contents.xml` payloads,
package association GUIDs, `_initial_state.xml`, checksums. Adding a policy means
updating the channel's relations, minting an ID Designer's scheme accepts, and
keeping package state coherent. Editing that as text is how you corrupt a project.

The answer is a **typed in-memory model** (driver set → drivers → channels → policy
sets → policies; filters, GCVs, resources, mapping tables, schema map, packages)
with reference-aware operations (`addPolicy(channel, set, order, policy)` updates
the linkage; `renamePolicy` fixes every reference; `deletePolicy` refuses if
referenced). The model loads from **every source we already read** and serializes
to (a) our own on-disk format and (b) the vault. A Designer-format writer is a
third serializer, added once the model is proven — the riskiest piece, so it comes
last, not first.

## Architecture

```
            ┌────────────────────────── sources (all exist) ──────────────────────────┐
            │ Designer project · driver export · LDIF dump · live LDAP (ldapConfig=) │
            └───────────────────────────────┬─────────────────────────────────────────┘
                                            ▼
                               ┌─────────────────────────┐
                               │   IDM model (typed)     │  reference-aware edits
                               │  driverset/driver/…     │  canonical serializer
                               └────┬──────────┬─────────┘
                    ┌───────────────┘          └────────────────┐
                    ▼                                           ▼
        ┌────────────────────┐                         ┌────────────────────┐
        │  IDM-as-code repo  │  git-versioned files    │   Vault deployer   │  LDAP writes +
        │  (source of truth) │  one file per object    │   diff/dry-run/    │  DirXML ext. ops
        └─────────┬──────────┘                         │   snapshot/rollback│  (restart etc.)
                  ▼                                    └─────────┬──────────┘
        ┌────────────────────┐                                   ▼
        │ validate + simulate│  DTDs · linkage · GCVs ·   ┌────────────────┐
        │ (simulator, tests) │  regression corpus         │  live vault(s) │  STG → PRD
        └────────────────────┘                            └────────────────┘

            surfaced to the agent as:  CLI (`bin/idm …`)  +  MCP server (typed tools)
```

- **IDM-as-code** is the source of truth: one readable file per object (policy XML,
  filter, GCVs, mapping tables, schema map, ECMAScript), a manifest for structure
  (channels, policy-set order, packages). Git gives history, review, and branches
  for free; the agent edits files it can read and diff.
- **Designer** becomes an import/export target: import a project into as-code
  (done — the reader), export as-code back to a Designer project (later phase), or
  simply have Designer *import from the vault* after a deploy (works today, no
  writer needed).
- **Two surfaces**: the Java core + CLI (testable, transparent, like `bin/sim`), and
  an **MCP server** wrapping it — typed arguments, schema-validated, destructive
  tools annotated so the client confirms `deploy`/`rollback`/`driver.stop`. Tools
  along the lines of `model.load`, `model.query`, `policy.edit`, `policy.add`,
  `gcv.set`, `validate`, `simulate`, `vault.diff`, `vault.snapshot`,
  `vault.deploy(dryRun)`, `vault.rollback`, `driver.status/start/stop/restart`,
  `driver.submitEvent`.

## Phases

**Phase 0 — Spikes (de-risk the two unknowns, days)**
1. *LDAP write path*: write `XmlData` on a test policy, add a `DirXML-Policies`
   linkage, set a GCV, `RestartDriver`; confirm the engine runs it. Learn which
   changes need a restart.
2. *Designer write fidelity + the modified flag*: make small changes in Designer
   (add a rule; add a policy; **edit a packaged policy**) and diff the project files
   to learn exactly what it touches (IDs, relations, GUIDs, initial_state,
   checksums) and **which attribute marks a packaged object as modified** — the
   flag our override path must set. Repeat once against the vault
   (`DirXML-pkg*` attributes) so the deployer sets it identically. Decides how hard
   the Designer-format writer is — deliberately **not** on the critical path.
3. *Extended-op inventory*: exercise `Start/Stop/RestartDriver`, `GetDriverState`,
   `SubmitEvent` (DxCMD Phase 2 blueprint) against the test vault.

**Phase 1 — The model + IDM-as-code (foundation)**
- Typed model populated from all four sources; canonical serializer; as-code
  import/export; a manifest. Round-trip tests: source → model → as-code → model
  must be byte-stable.

**Phase 2 — Validation (offline safeguards)**
- `validate`: DTD, XSLT/ECMAScript compile, linkage integrity, GCV/mapping-table
  references, schema-map/filter vs schema, package-discipline check. Wire the
  simulator's existing diagnostics into it; `--json` output.

**Phase 3 — Edit operations + MCP server**
- Reference-aware operations on the model, each validated; CLI + MCP tools;
  dry-run everywhere. Simulation as a gate (`simulate` = `test-all` + `compare`).

**Phase 4 — Vault deploy with safeguards**
- Structured `vault.diff`; deploy plan; LDIF snapshot + `rollback`; LDAP writes +
  `RestartDriver`; environment gating; audit log; post-deploy verification
  (re-read → diff empty; optional canary `SubmitEvent` vs simulator prediction).

**Phase 5 — Operate**
- Driver lifecycle, cache view/clear, migrate/resync, named passwords, trace
  level, jobs — via the existing extended ops. (Much of this is `DxCacheReader`
  generalized.)

**Phase 6 — Designer round-trip + workflows**
- Designer-format writer (from the Phase 0 findings) for teams that need it.
- Agent workflows/skills: "implement requirement X" → edit → validate → simulate →
  diff → deploy STG → verify → promote PRD, with package-aware overrides and docs
  generation from the model.

**Track P — Provisioning forms (the form builder)**
Runs alongside Phases 3–4 once the model exists; it is a distinct object model
(`Model/Provisioning/` in a project; `srvprv*` objects under the User Application
driver's AppConfig in the vault) so it gets its own reader/writer.
- **P1 Read/model**: provisioning request definitions (PRDs) and their **request /
  approval forms** — form XML (fields, widgets, data items, validation, ECMAScript
  event handlers, localized labels) — loaded from a project and from the vault
  (the `dirxml-designer-workspace` skill already maps the project side).
- **P2 Edit**: typed form operations (add/remove/reorder fields, set widget type
  and data binding, attach events/validation, localization) with a schema check
  against the form DTD/XSD and the PRD's data items; canonical serialization.
- **P3 Validate/preview**: static checks (every field bound to a data item, events
  parse, required/visibility rules consistent) and a rendered **preview** so the
  agent and a human can see the form before deploying.
- **P4 Deploy**: write the PRD/form objects to the vault (LDAP) and trigger the User
  Application's refresh, with the same diff/snapshot/rollback/gating as drivers.
- Later: workflow activities/flow design and roles/resources modeling.

## What it takes

- **Reuse**: the reader stack, simulator, regression tooling, live-LDAP client,
  extended-op plumbing, canonical compare — roughly the read and test halves are
  done, which is the larger share of the risk.
- **New**: the typed model + serializer (the heart), validation as a product,
  reference-aware edit ops, the vault deployer with snapshot/rollback/diff, the MCP
  surface, and — last — the Designer writer.
- **Sequencing rule**: nothing writes to a vault until validate + simulate + diff +
  snapshot exist; nothing writes Designer format until the model is proven via the
  as-code + vault path. This keeps every phase shippable and safe on its own.

## Decisions (made 2026-09-08)

1. **Source of truth: IDM-as-code.** Git-native, agent-native; Designer is an
   import/export target, with the Designer-format writer in Phase 6.
2. **A new repo/product** that depends on the simulator as a library; the
   simulator stays the test engine it is.
3. **Scope:** driver/policy development first, **plus provisioning forms (the form
   builder) as Track P**; full workflow-activity design is a later follow-on.
4. **Package overrides are the supported customization method** — make them easy
   and always set the modified/customized flag correctly; never refuse by default.

## Non-goals (for now)

- Reusing Designer's Java code or UI.
- Workflow-activity/flow design and roles/resources modeling (after Track P).
- Native-shim / Remote Loader *installation* (RL config attributes are LDAP and in
  scope; the OS-level install isn't).
