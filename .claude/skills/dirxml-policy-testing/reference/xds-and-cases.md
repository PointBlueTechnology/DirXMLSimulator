# XDS, cases, and driver exports — reference

## XDS document shape

The engine processes operations under `<nds><input>…</input></nds>`. Each child
of `<input>` is one operation the policy chain transforms.

```xml
<nds dtdversion="4.0">
  <input>
    <add class-name="User" src-dn="\ACME\users\jdoe">
      <add-attr attr-name="Given Name"><value>John</value></add-attr>
      <add-attr attr-name="Surname"><value>Doe</value></add-attr>
    </add>
  </input>
</nds>
```

Operation elements you'll author for `input.xds`:

- **add** — `<add class-name dest-dn|src-dn>` with `<add-attr attr-name><value>…</value></add-attr>` children.
- **modify** — `<modify class-name src-dn dest-dn>` with `<association>…</association>`
  and `<modify-attr attr-name>` containing `<add-value>`, `<remove-value>`, or
  `<remove-all-values/>`, each wrapping `<value>…</value>`.
- **delete / rename / move** — `<delete>`, `<rename>` (with `<new-name>`),
  `<move>` (with `<parent>`), carrying `src-dn`/`dest-dn`/`<association>`.

`src-dn` is the source (e.g. Identity Vault) DN; `dest-dn` the destination (app)
DN. For an associated object include `<association>token</association>` so
destination queries can find it.

## Direction: `fromNDS`

- `fromNDS=true` (default) — **Subscriber** direction, Identity Vault → application.
  Source = eDir, destination = the app. `token-dest-attr` queries the app.
- `fromNDS=false` — **Publisher** direction, application → Identity Vault. Source =
  app, destination = eDir.

Match this to the channel you're testing or queries resolve against the wrong side.

## Fake-directory state (`directory.xds`)

The fake directory answers the queries a policy issues. Seed it with `<instance>`
elements (same container shape as input):

```xml
<nds dtdversion="4.0">
  <input>
    <instance class-name="User" src-dn="\ACME\users\jdoe">
      <association>jdoe-assoc</association>
      <attr attr-name="Surname"><value>Doe</value></attr>
      <attr attr-name="Given Name"><value>John</value></attr>
    </instance>
  </input>
</nds>
```

The harness evaluates a policy's `<query>`/`<query-ex>` against these instances:
matches on `class-name`, `scope`, `<search-attr>` criteria (case-insensitive
value equality), and projects `<read-attr>` (no read-attr ⇒ all attributes).
Direct reads by `dest-dn`/`src-dn` or `<association>` are supported. Commands the
policy emits (`add`/`modify`/`delete`) mutate the directory, so matching→merge and
create→read sequences behave realistically.

## Channel (policy-set) order

When you build a chain from a driver export, stages run in standard IDM order:

- **Subscriber** (eDir → app): Event → Matching → Create → Placement → Command →
  Schema Mapping → Output Transform.
- **Publisher** (app → eDir): Input Transform → Schema Mapping → Event → Matching →
  Create → Placement → Command.

In an explicit `chain.txt`, list stages yourself in the order you want them run.

## Reading `step` output

For each stage you get:
- **INPUT / OUTPUT** — the XDS before and after. Diff them to see exactly what the
  stage changed. An empty `<input/>` output means the operation was vetoed/stripped.
- **QUERIES / COMMANDS** — the XDS the stage sent to the directory (with the
  `<query>` criteria and `<read-attr>`s). If a value is wrong, check whether the
  query asked for it and whether `directory.xds` holds it.
- **TRACE** — the engine's rule-by-rule log: which rules' conditions matched
  ("Rule selected"), each action, token/arg-value resolution, and "Policy
  returned". This is where you see *why* a value came out the way it did.

## Driver exports

A Designer "Export Driver Configuration" file (`<driver-configuration>`) holds the
whole driver: policies as `<rule name="…">` wrappers (each with a `<policy>`,
`<style-sheet>`, or `<attr-name-map>`), the `<policy-linkage>` that orders them,
the filter, and GCVs. Point a case at one:

```
# case.properties
export=../../UKG.xml
channel=publisher
```

The harness assembles that channel's real chain and loads the driver's GCVs
(`token-global-variable` resolves from the export). A case-local `gcv.xml` can
override individual GCVs. Real client exports are proprietary — keep them out of
git (the repo gitignores `*.xml` outside `cases/`).

You can also point a single stage at one real policy file — a Designer project
stores each policy as a clean `<policy>` in `Model/.../<id>_contents.xml`, which
loads directly.

## GCV definitions (`gcv.xml`)

If you supply GCVs by hand, each `<definition>` **must** have a `display-name`
attribute or the engine rejects the whole block:

```xml
<nds><configuration-values><definitions>
  <definition name="gcv.MyFlag" display-name="My Flag" type="boolean"><value>true</value></definition>
</definitions></configuration-values></nds>
```
