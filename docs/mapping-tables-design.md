# Design note: mapping tables (the `Map` token) offline

Status: **implemented** (2026-06-19). Extends the simulator to resolve **mapping
tables** — the data behind DirXML Script's `Map` token (and the `dest`/`source`
column lookups used for code translation, region maps, etc.) — so policies that
use them load and run headlessly, instead of failing.

> **Shipped:** `NdsStreamProtocol` (a `vnd.nds.stream:` URL handler) + `MappingTableStore`
> (name-keyed) + base-URI plumbing in `PolicyStage` (only for Map-using policies) +
> `MappingTableSource` (case-local `mapping-tables/` dir, and extraction from a
> driver/driver-set **export**, a **Designer project**, an **LDIF** dump, and
> **live LDAP**). Wired in `Case.load`. Validated end-to-end: the `Map` token that
> fails today with VRDException -9192 now resolves (CA→West) from a case-local
> table; extraction pulls all 6 tables from the real `RFI-DriverSet.xml`, 5 from a
> real Designer workspace, and **5 live from the IG4 vault** (over LDAP). **Deferred:**
> live read from the vault *on demand* (we pre-load from the driver-set subtree
> instead); entitlement codemaps (separate). The approach below is what was built.

Grounded in a confirmed investigation (empirical + decompiled engine); see
"Background" for the exact failure and root cause.

## Background: the gap and its root cause

A policy with a `Map` token cannot be loaded today. It fails at **stage-build**:

```
VRDException Code(-9192): Couldn't access map definition '..\..\RegionMap':
  java.net.MalformedURLException: no protocol: ..\..\RegionMap
```

Tracing the engine (`com.novell.nds.dirxml.engine.rules.*`):

```
TokenMap.init/evaluate
  baseURL  = RuleUtil.getNodeURL(policyNode)              // ← null for our parsed policy.xml
  tableURL = RuleUtil.resolveRelativeDNToURL(baseURL, "..\..\RegionMap")
                                                          // ← builds a vnd.nds.stream: URL
  table    = MappingTable.getMappingTable(tableURL)       // cached per-URL
  table.load(): RuleUtil.getReferencedDocument(url)
              = new XmlDocument().readDocument(url)        // ← opens the URL, parses <mapping-table>
  table.getMap(source, dest)                              // builds the lookup Map
```

Two things break offline:

1. **No base URL on the policy node.** `getNodeURL` reads the DOM node's
   `NodeImpl.getBaseURI()`. We parse `policy.xml`/export blobs into a Novell DOM
   with no base URI, so it is `null`; `resolveRelativeDNToURL(null, "..\..\X")`
   degrades to `new URL("..\..\X")` → *"no protocol"*.
2. **The table URL is an NDS stream URL.** With a base URL present,
   `resolveRelativeDNToURL` resolves the (relative) table DN to an absolute DN and
   produces a **`vnd.nds.stream:`** URL via `XdsDN.getNDSStreamURL()`.
   `getReferencedDocument` then does `XmlDocument.readDocument(url)`, which needs a
   handler for that scheme — in production, `NDSURLConnection` reads the resource's
   stream attribute from eDirectory. There is no such connection offline.

Consequences today: a `Map`-token policy **hard-errors** when loaded from a
`chain.txt` case, and is **skipped-with-warning** when loaded from an
export/LDIF/project source (the per-policy build-failure path in
`LdifDriverSource.chain()` / the export loaders). Either way the token never
resolves. `ldap=` does **not** help — the engine's NDS-stream handler is not our
JNDI client.

A mapping table document is plain XML the engine already knows how to parse. As it
appears inside a driver-set export (confirmed against a real export,
`RFI-DriverSet.xml`), it is a **library resource**:

```xml
<driver-set-configuration dn="cn=RFI-DriverSet,ou=Services,o=RFI" name="RFI…">
 <children>
  <policy-library base-dn="cn=Library,cn=RFI-DriverSet,ou=Services,o=RFI">
   <resource content-type="application/vnd.novell.dirxml.mapping-table+xml ;charset=UTF-8"
             name="DeptCodeMap">
    <content contains="xml">
     <mapping-table>
       <col-def name="DeptCode" type="nocase"/>
       <col-def name="Domain-Placement" type="nocase"/>
       <row><col>840116</col><col>OU=stuttgart rm,DC=RFoods,DC=com</col></row>
       …
     </mapping-table>
    </content>
   </resource>
  </policy-library>
 </children>
</driver-set-configuration>
```

So the engine side works the moment `readDocument(tableURL)` returns the inner
`<mapping-table>`. The whole problem is **making that URL resolve to local data**.

A `Map` token references a table by a path that resolves to that resource — and the
reference is a `VarString`, so it is **not always literal**. Both forms occur in the
real export:

```xml
<token-map dest="Domain-Placement" src="LocCode" table="..\..\Library\LocCodeMap">…
<token-map dest="Domain-Placement" src="DeptCode" table="$locTableDN$">…   <!-- GCV-expanded -->
```

The literal form is a relative DN up to the library; the dynamic form is a GCV that
expands to a DN at runtime. Either way the **final component is the table's name**
(matching the resource's `name=`), which is what makes name-keyed resolution
(below) robust.

## Approach: serve `vnd.nds.stream:` from a local table store

Two cooperating pieces, both behind the existing seams — no engine patching:

1. **Give policy nodes a base URI** so relative table DNs resolve to absolute DNs.
   When we parse a policy for a stage, set the Novell DOM document/element base URI
   to a synthetic `vnd.nds.stream:` URL representing the policy's own DN (derived
   from `driverDN` + the policy name). Then `resolveRelativeDNToURL` produces a
   deterministic absolute `vnd.nds.stream:` URL for each referenced table.
2. **Register a `vnd.nds.stream:` URL handler** that resolves those URLs to local
   mapping-table XML. `XmlDocument.readDocument(url)` opens the URL through the
   JVM's handler resolution, so a handler we install returns the table document
   from an in-memory store keyed by the table's (normalized) DN.

Handler installation options, in order of preference:

- **`java.protocol.handler.pkgs`** — set the system property to a package
  containing `vnd/nds/stream/Handler` (a `URLStreamHandler`). Per-JVM, set once at
  startup (in `EngineContext` init or the `bin/sim` launcher), additive, and avoids
  the single-shot `URL.setURLStreamHandlerFactory`. **Risk:** the engine itself may
  register a handler for this scheme when its NDS stack initializes; we must ensure
  ours wins (it doesn't initialize the NDS stack offline, so the scheme is
  unclaimed — to verify).
- **`URL.setURLStreamHandlerFactory`** — one factory per JVM, first-wins. Usable
  but global and brittle if anything else sets one.

The handler is backed by a process-wide `MappingTableStore` (a `Map<String DN,
Document>`), populated per case before the run. A `URLConnection` whose
`getInputStream()` serializes the matching table document (or 404s, letting the
engine apply the token's `not-found`/`default` behavior, matching production).

### Resolve by table name, not full DN

The naive store key is the absolute table DN, which would force us to normalize a
`vnd.nds.stream:` URL back to a DN and match tree/case exactly — fragile, and the
GCV-expanded `table="$locTableDN$"` form means we can't even pre-compute every URL
statically.

The export removes the need: a table resource's `name=` equals the **last
component** of every reference to it (`..\..\Library\LocCodeMap` → `LocCodeMap`;
a GCV that expands to `…,cn=LocCodeMap,cn=Library,…` → `LocCodeMap`). Table names
are unique within a library. So the handler resolves a requested URL by its
**trailing name component** and looks the document up by name — no DN round-trip,
and it works for literal and dynamic references alike. (If two libraries ever held
same-named tables, fall back to a longest-suffix DN match; not seen in practice.)

## Where the table data comes from

Tables are authored in Designer as **mapping-table resources** (a DirXML-Resource
whose content is the `<mapping-table>` XML). The simulator should source them the
same flexible way it sources everything else:

- **Embedded in the driver export / project / LDIF.** Confirmed shape (above):
  `<resource content-type="application/vnd.novell.dirxml.mapping-table+xml…"
  name="…"><content contains="xml"><mapping-table>…`. Extraction walks every such
  `<resource>` — under a `<policy-library>` (the common case, as in
  `RFI-DriverSet.xml`) **or** under a driver — collecting `name → <mapping-table>`.
  This sits alongside the existing resource extractors
  (`DriverExport.ecmaScriptSources()` etc.). A **Designer project** keeps each table
  as a `.MappingTableResource_` object (meta `name=` + `_contents.xml`). An
  **LDIF dump or live LDAP** carries it as a `DirXML-Resource` object whose content
  is in **`DirXML-Data`** (not `XmlData` — that's policies; the live reader requests
  both). Live reads pre-load every table in the driver-set subtree at config-read
  time.
- **A case-local `mapping-tables/` directory.** Drop one `<mapping-table>` XML per
  table (filename = the table name/DN). Zero-dependency, always available, and the
  natural way for an agent to supply a table when the config source lacks it — it
  mirrors how `directory.xds`/`ecmascript/*.js` already work.
- **Live, later.** With a live connection the table could be read from the vault;
  out of scope for v1 (the offline store is the point).

Resolution order: case `mapping-tables/` overrides, then tables extracted from the
config source.

## Component plan

1. **`MappingTableSource`** — collects `<mapping-table>` documents for a case:
   from the config source (new `…Source.mappingTables()` extractors) and a
   case-local `mapping-tables/` dir. Pure parsing; unit-testable.
2. **`MappingTableStore` + `vnd.nds.stream` `URLStreamHandler`/`URLConnection`** —
   the process-wide registry and the handler that serves it. The handler is the
   only globally-installed piece; install once, guarded.
3. **Policy base-URI plumbing** — in `PolicyLoader`/`PolicyStage`, set the parsed
   policy node's base URI to the synthetic policy URL so relative table refs
   resolve. Confirm the Novell DOM honors `setBaseURI`/`setDocumentURI` such that
   `RuleUtil.getNodeURL` returns it.
4. **Pre-resolution registration** — for each table a stage references, compute the
   engine's URL and register the document, avoiding DN round-trip fragility.
5. **Case wiring** — `Case.load` builds the `MappingTableSource`, registers tables
   into the store, and (as today) reports a clear diagnostic when a referenced
   table is missing instead of the raw `VRDException`.
6. **Graceful degradation** — keep the current skip-with-warning, but upgrade the
   message: name the unresolved table and tell the user to add it under
   `mapping-tables/`.

### Case wiring sketch

```
cases/<name>/
  mapping-tables/
    RegionMap.xml        # <mapping-table>…</mapping-table>
  case.properties        # (optional) mappingTables=../shared-tables   to point elsewhere
```

No new required input for cases that don't use `Map`; entirely inert otherwise.

## Open questions / risks

- **Does the engine claim `vnd.nds.stream:` first?** Offline it should not
  initialize the NDS stream stack, leaving the scheme for us — must verify our
  handler is the one invoked. If the engine *has* statically registered one,
  fall back to `setURLStreamHandlerFactory` or a scheme-shim.
- **Novell DOM base-URI support.** `getNodeURL` relies on `NodeImpl.getBaseURI()`;
  confirm we can set it on a parsed policy (it may require `setDocumentURI` on the
  `XmlDocument`/`DocumentImpl`, or constructing the parse with a system id).
- **Caching.** `MappingTable` caches per-URL in a static map with an
  `UpdateWatcher`. Stable for a run; across cases in one JVM (`test-all`,
  `coverage`) ensure either deterministic URLs (same table name → same doc, fine)
  or a cache reset between cases if two cases define different tables of the same
  name. `MappingTable.cleanup()` exists for this.
- **Dynamic table refs.** `table="$gcv$"` resolves through GCV expansion before the
  URL is built, so the runtime handler (not static pre-registration) is what makes
  the dynamic form work — another reason to lead with the handler. GCVs are already
  in scope in the simulator, so the expansion itself already happens.

## Non-goals

- **Live table read** from the vault (later; the offline store is the goal).
- **Mapping-table *editing*/round-trip** — read-only resolution for lookups.
- **Entitlement codemaps** — a related but distinct resource mechanism (no
  `CodeMap` class found by that name); treat as a separate investigation. Basic
  entitlement tokens already work (they are operation-driven via
  `DirXML-EntitlementRef`).

## Build order

1. `mapping-tables/` case dir + `MappingTableSource` (case-local only) +
   `MappingTableStore` + the `vnd.nds.stream` handler + base-URI plumbing — enough
   to make a hand-supplied table resolve end-to-end. Validate with the same
   `RegionMap` repro that currently fails.
2. Extraction from export/project/LDIF — shape confirmed against `RFI-DriverSet.xml`
   (4 mapping tables under `<policy-library>`: DeptCodeMap, DeptCodeMapRland,
   LocCodeMap, LocCodeMapRland; referenced by `..\..\Library\<name>` and a
   GCV-expanded form). Validate end-to-end by running an RFI driver whose
   placement policy uses `LocCodeMap`/`DeptCodeMap`.
3. Diagnostics polish (name the missing table; list referenced-but-absent tables,
   akin to the missing-Java-class warning).
