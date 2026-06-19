# Design note: DriverSet/Library-scope GCVs & shared policies

Status: **implemented** (2026-06-19). Backlog #8. Makes the simulator **tell you when
a DriverSet-scope GCV or a shared/Library policy is referenced but not present**, lets
you **supply the missing value directly**, and teaches the agent to ask for it —
rather than running on a silently-incomplete chain.

> **Shipped:** `GcvReferences` (scans `token-global-variable`/`token-global-config-value`)
> → `run`/`step`/`test` warn referenced-but-undefined GCVs (excluding engine
> `dirxml.auto.*`); the chain builders now accumulate unresolved policy linkages
> (`DriverExport`/`LdifDriverSource.unresolvedPolicies()`) → warned up front;
> `gcv.<name>=<value>` in `case.properties` overlays GCVs (after the source + `gcv.xml`).
> Validated: a `token-global-variable` GCV warns when undefined and resolves when set;
> `~name~` is correctly *not* treated as a policy GCV reference. **Deferred:** the
> case-local `policies/` splice (use a fuller driver-set source meanwhile). The plan
> below is what was built.

## What's already handled (investigation)

The premise of #8 turned out to be mostly true: these *are* resolved whenever the
config source carries them. Confirmed in code:

| Concern | Designer project | LDIF / live LDAP | Driver **export** |
|---|---|---|---|
| **DriverSet-scope GCVs** | ✅ merges DriverSet then driver (`gcvDefinitions(driver)`) | ✅ merges `parentOf(driver)` then driver | ⚠️ merges every `<configuration-values>` block **present in the file** — so only if exported |
| **Library / shared policies** (linked in a set, living in `cn=Library`) | ✅ resolved via the project's global object index | ✅ resolved from the driver-set subtree | ⚠️ only if the export's *include referenced (external) policies* option was on |
| **GCV value override** | `gcv.xml` overlay (any source) | `gcv.xml` overlay | `gcv.xml` overlay |

So a **full** driver-set source (project, LDIF, or live) already carries both. The
weak spot is a **single-driver export**: whether a referenced Library/external
policy is included is a **choice made at export time** — Designer's *Export to
Configuration File* offers an option to include referenced (external/Library)
policies. If that option was off, the linked policy simply isn't in the file (and
the DriverSet GCV block may be absent too). So the same driver can export *with* or
*without* its shared policies; the fix when one is missing is to re-export with that
option enabled, or supply the policy/value directly (§2).

Two real gaps, independent of source:

1. **No detection.** Nothing scans for a GCV that a policy *references* but that has
   no definition in scope, so the run silently uses an empty value (a misleading
   "missing value" result). There's no signal for the agent to act on.
2. **Silent policy skips.** When a policy-set linkage points to a policy the source
   doesn't contain (a Library policy not in the export), it's skipped with a
   `System.err` warning and the chain runs **without that policy** — easy to miss.

`<include>` (the DirXML Script element that pulls another policy's rules inline) has
no engine processor wired in the harness and is rare (RFI uses none); treated as a
non-goal below.

## Deliverables

### 1. Detect & report (the signal for "ask if missing")

- **`GcvReferences`** — a static scan (sibling to `JavaExtensions` /
  `UnsupportedFeatures`) that collects every GCV a policy references —
  `<token-global-config-value name="X"/>`, `~X~` substitutions in argument strings,
  and structured-GCV refs — and returns those whose name has **no definition** in
  the case's merged `GCDefinitions`. Reported up front by `run`/`step`/`test`
  (`WARNING: GCV(s) referenced but not defined: …`) and in `--json`, exactly like
  the existing missing-Java-class / named-password warnings.
- **Missing linked-policy diagnostic** — the chain builders already know each
  linkage's policy DN and whether its content resolved; collect the **unresolved**
  ones and surface them up front (`WARNING: policy 'cn=…' linked in <set> not found
  — a Library/shared policy missing from this export?`) instead of only a stderr
  skip. Add to the warning channel + `--json` so `test-all`/agents see it.

These two warnings are the trigger: when they fire, the agent asks the user for the
missing GCV value or the Library policy (see §3).

### 2. Specify values directly

- **GCVs — already there, made first-class.** `gcv.xml` overlays/overrides any
  source's GCVs. Add a one-off shortcut so the agent doesn't have to author a file:
  ```properties
  # case.properties
  gcv.idv.dit.data.users = data\\users      # gcv.<name>=<value>, overlaid like gcv.xml
  gcv.MyFlag = true
  ```
  `Case.load` collects `gcv.*` keys into a `GCDefinitions` overlay merged after the
  source GCVs and `gcv.xml` (so it wins). Scalar values only; `gcv.xml` remains for
  structured GCVs.
- **Library / shared policies — supply what's missing.** Two low-effort options
  (pick per case):
  - point at a **fuller source** for resolution (the recommended fix — a driver-set
    export/LDIF/project/live carries the Library); or
  - a case-local **`policies/`** dir whose files (named by the missing policy's DN
    or link name) are spliced in where a linkage is unresolved. Mirrors how
    `mapping-tables/` and `ecmascript/` already patch a missing resource.

### 3. Teach the agent to ask

Add missing-inputs rows (skill `SKILL.md` + reference) and a proactive cue:

- **A DriverSet/Library-scope GCV** — *sign:* `GCV(s) referenced but not defined`.
  *Ask for:* the value (set `gcv.<name>=` or `gcv.xml`), or a **full driver-set
  source** (project/LDIF/live, or re-export with the *include referenced policies*
  option on) which carries the DriverSet GCV block.
- **A shared / Library policy** — *sign:* `policy '…' linked but not found`.
  *Ask for:* a **re-export with the *include referenced (external) policies* option
  enabled** (the option was off if a linked Library policy is missing), or a full
  driver-set export/LDIF/project/live, or the specific policy XML for a `policies/`
  splice.
- **Proactive guidance:** prefer a driver-set source over a single-driver export
  precisely because it carries DriverSet GCVs + Library policies; if only a
  single-driver export is available, expect to supply these directly.

## Component plan

1. **`GcvReferences`** (scan + diff vs `GCDefinitions`) + wire into the
   `warnDiagnostics`/`--json` paths. The novel, offline-testable core.
2. **Missing linked-policy collection** — have `DriverExport` / `DesignerProject` /
   `LdifDriverSource` expose the unresolved-linkage DNs they already detect; report
   them centrally.
3. **`gcv.<name>=` overlay** in `Case.load` (+ a `GcvOverlay` helper) and docs.
4. **`policies/` splice** (optional, second) — resolve an unfound linkage from a
   case-local file before skipping.
5. **Skill/docs** — missing-inputs rows, the proactive cue, `gcv.<name>=`, and the
   "full driver-set source carries scope" guidance.

Build order: **1 + 2 first** (detection is the whole point — it's what makes the
agent ask), then **3** (direct GCV values), then **4** (policy splice) if needed.

## Non-goals

- **`<include>` (DirXML Script inline include)** — no engine processor wired; rare.
  If it ever matters it likely resolves like mapping tables (a `vnd.nds.stream:`
  ref) and could reuse that handler; deferred until a real driver needs it.
- **Structured-GCV authoring** via the `gcv.<name>=` shortcut — scalars only;
  `gcv.xml` stays for structured definitions.
- **Engine Control Values** beyond what GCV resolution already covers.
