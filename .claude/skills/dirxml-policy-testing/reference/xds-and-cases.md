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

## Seeding the fake directory from LDIF (optional)

Instead of hand-writing `directory.xds`, point a case at an **LDIF** dump of real
objects (`ldapsearch`/ICE export):

```properties
ldif=sample-users.ldif
```

Entries are mapped to native XDS the same way live LDAP is: attribute/class names
DirXML-ward via the schema (so use `project=`/`schema=` for best results), values
normalized by syntax (base64 `::` octet stays base64, generalized time → seconds,
DN values → slash form). LDIF line folding and `::` base64 are handled; operational
attributes (`entryDN`, `structuralObjectClass`, timestamps) are dropped. A
`dirxml-associations` value matching the case's `driverDN` becomes the instance's
`<association>`. Combine with `directory.xds` (both load) if you like.

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

## Driver config: an export or a Designer project

### Driver export

A Designer "Export Driver Configuration" file (`<driver-configuration>`) holds the
whole driver: policies as `<rule name="…">` wrappers (each with a `<policy>`,
`<style-sheet>`, or `<attr-name-map>`), the `<policy-linkage>` that orders them,
the filter, and GCVs. Point a case at one:

```
# case.properties
export=../../UKG.xml
channel=publisher
```

The harness assembles that channel's real chain and loads the driver's GCVs and
ECMAScript resources. A case-local `gcv.xml` can override individual GCVs. Real
client exports are proprietary — keep them out of git (the repo gitignores
`*.xml` outside `cases/`).

### Designer project on disk

Instead of exporting, point at the Designer **project** and name the driver:

```
# case.properties
project=/path/to/designer_workspace/MyProject
driver=CyberArk-PROD
channel=publisher
```

The harness reads the project's object model — `*.Driver_` (by name),
`.Subscriber_`/`.Publisher_` `relations` (ordered policy sets), each policy's
`<ID>_contents.xml` — and assembles the same chain. It also loads the project's
**GCVs** (`*_DirXML-ConfigValues.xml`), **ECMAScript resources**, and the
**eDirectory schema** (`*_schema.xml`) — the last of which an export omits and
which drives input validation (see below). The on-disk format is mapped by the
companion `dirxml-designer-workspace` skill.

(You can also point a single chain stage at one real policy file directly —
Designer stores each policy as a clean `<policy>` in `Model/.../<id>_contents.xml`.)

### LDIF/LDAP export of the live Identity Vault

A third source: an LDIF dump (or live-LDAP export) of the eDir DriverSet subtree.

```
# case.properties
ldifConfig=IDM_subtree.ldif
driver=CyberArk
channel=publisher
```

The harness reads the `DirXML-Driver` object's `DirXML-Policies` linkage
(`<policyDN>#<order>#<setId>`, the same set ids as an export), each referenced
`DirXML-Rule`/`DirXML-StyleSheet`'s `XmlData` policy content, and the
`DirXML-JavaModule`/`DirXML-ShimConfigInfo`/`DirXML-ConfigValues`/
`DirXML-DriverFilter` attributes (shim params, GCVs, filter). A policy whose
content the engine rejects (an unresolved map-table/resource reference, etc.) is
skipped with a warning rather than failing the chain.

**The export must include the DirXML data attributes** — a plain `ldapsearch *`
omits them. Request them explicitly, e.g.:

```
ldapsearch -o ldif-wrap=no -b "cn=<DriverSet>,o=system" -s sub "(objectclass=*)" \
  '*' XmlData DirXML-Policies DirXML-ShimConfigInfo DirXML-ConfigValues \
  DirXML-JavaModule DirXML-DriverFilter DirXML-ShimAuthServer DirXML-ShimAuthID
```

The same LDIF (or a separate dump of object entries) can seed sample data via
`ldif=` — see "Seeding the fake directory from LDIF" above.

## Schema validation

When a schema is available — automatically with `project=`, explicitly via
`schema=<*_schema.xml or project dir>`, or **read live from LDAP** (`schema=ldap`,
or automatically whenever `ldap=` is set and no other schema is supplied) — the
harness validates `input.xds` and `directory.xds` against it and warns on: an
unknown class, an attribute not in the schema (a typo), an attribute not valid for
its class, or multiple values on a single-valued attribute. This catches mistakes
when you hand-author inputs.

Reading from LDAP parses the eDir subschema (`cn=schema`): it recovers the true
NDS/DirXML name from each definition's `X-NDS_NAME` extension (falling back to the
LDAP name) and maps the syntax OID to the eDir `syn=` the value normalizer uses —
so it's a full equivalent of a Designer `*_schema.xml`, no project needed. A
schema-read failure is non-fatal (warns; validation just doesn't run).

## Driving a real driver shim (optional)

Normally the chain ends at its final command and the fake directory accepts
anything. To validate that the command your policies built is actually consumable
by the connector, drive the **real shim** as a terminal command sink. Both this
and the live-LDAP option below are opt-in — absent keys mean today's behavior.

```properties
# case.properties — run the connector after the chain
shim=true
shimClass=com.acme.MyDriverShim     # optional; defaults to the export/project java-module
shimJar=lib/MyShim.jar              # optional extra jar(s), comma-separated; keep out of git
shimInit=shim-init.xml              # optional explicit <init-params>; else synthesized from the source
shimAuthPassword.named=app-secret   # or shimAuthPassword=<literal>
```

The init-params doc is built from where the driver already defines it — the
export's `<java-module>`/option blocks or the project's `DirXML-JavaModule`/
`DirXML-ShimConfigInfo` — so you usually only add `shim=true` (plus the secret).
A new **`shim`** snapshot is appended: its INPUT is the chain's final command, its
OUTPUT is the shim's real status/association response. Subscriber direction only.

The shim runs in-process, so pure-Java connectors work (REST/SCIM/SOAP/JDBC/
Delimited Text/Loopback); native shims (AD-local, eDir) do not.

## Answering queries from live eDirectory (optional)

Instead of seeding `directory.xds`, answer the chain's (and the shim's) queries
from a live eDir over LDAP. Values are normalized to native XDS form by each
attribute's schema syntax, so a schema must be available (use `project=` or
`schema=`).

```properties
ldap=ldaps://host:636
ldapBindDn=cn=admin,ou=sa,o=system
ldapBindPassword.named=ldap-bind     # or ldapBindPassword=<literal>
ldapSearchBase=o=data
# TLS cert validation is OFF by default (test directories use self-signed/internal
# CAs); set ldapTrustAll=false to require a valid cert.
ldapDnTree=ACME-TREE                 # tree name for slash-form DN values
```

Use `ldap=` with or without `shim=`. With a shim, the shim's back-channel queries
go to LDAP too; without one, only the policy chain's queries do.

## GCV definitions (`gcv.xml`)

If you supply GCVs by hand, each `<definition>` **must** have a `display-name`
attribute or the engine rejects the whole block:

```xml
<nds><configuration-values><definitions>
  <definition name="gcv.MyFlag" display-name="My Flag" type="boolean"><value>true</value></definition>
</definitions></configuration-values></nds>
```
