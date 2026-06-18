# Design note: testing with real driver shims

Status: **design** (2026-06-18). Extends the simulator from policy-only testing
(against the in-memory `FakeDirectory`) to driving a **real `DriverShim`** so the
XDS your policies produce is validated against the actual connector — and,
optionally, so destination/IDV queries are answered from a **live eDirectory over
LDAP** instead of author-supplied state.

Grounded in a working precedent: the `offline/` package in the **ClaimsCenter**
driver project already drives `CCDriverShim` headlessly and converts XDS queries
to LDAP. This note generalizes that into the simulator and fixes the one part
ClaimsCenter does naively — value representation.

## Background: the two seams

The engine talks to a connector through two DOM-`Document` seams the simulator
already implements in `FakeDirectory`:

- `engine.XdsQueryProcessor.query(Document)` — answers the queries a *policy*
  issues (source/IDV reads, `do-find-matching-object`, attribute reads).
- `engine.XdsCommandProcessor.execute(Document)` — absorbs the commands a policy
  emits.

A driver **shim** exposes the parallel *driver-side* contract over `XmlDocument`
(a thin Novell-DOM wrapper; convert with `new XmlDocument(doc)` / `xmlDoc.getDocument()`):

```java
interface DriverShim {
    XmlDocument init(XmlDocument initParams);
    SubscriptionShim getSubscriptionShim();
    PublicationShim  getPublicationShim();
    XmlDocument getSchema(XmlDocument);
}
interface SubscriptionShim extends XmlCommandProcessor {   // app-bound commands
    XmlDocument init(XmlDocument initParams);
    XmlDocument execute(XmlDocument command, XmlQueryProcessor reply);
}
interface XmlQueryProcessor { XmlDocument query(XmlDocument q); }  // shim's back-channel to the IDV
```

`SubscriptionShim.execute` is given a back-channel `XmlQueryProcessor` the shim
uses to query the Identity Vault *during* command processing (resolve
associations, read extra attributes). That back-channel is where ClaimsCenter
plugs its `LDAPQueryProcessor`.

## What ClaimsCenter proves (and reuses)

| ClaimsCenter `offline/` | Role | Simulator counterpart |
|---|---|---|
| `TestJig.run()` | shim lifecycle: `new CCDriverShim()` → `init` → `getSubscriptionShim().init` → `execute(xds, qp)` | **`ShimAdapter`** (new) |
| `config/initParams-*.xml` (+ `.template`) | the `<init-params>` doc | **`InitDocBuilder`** (new) — synthesize from export/project + named passwords |
| `TestCommandProcessor` / `TestQueryProcessor` | back-channel query answerer passed into `execute` | bridge over **`FakeDirectory`** *or* **`LdapQueryProcessor`** (new) |
| `LDAPQueryProcessor` | XDS `<query>` → LDAP search → `<instance>` | **`LdapQueryProcessor`** (new), schema-driven |

The init-doc shape is standard and is the target for `InitDocBuilder`:

```xml
<nds><input>
  <init-params src-dn="\TREE\system\driverset\Driver">
    <authentication-info><server>…</server><user>…</user><password>…</password></authentication-info>
    <driver-options>… plus inline <configuration-values><definitions>…(GCVs) …</driver-options>
    <publisher-options>…</publisher-options>
    <subscriber-options>…</subscriber-options>
    <subscriber-state/>
  </init-params>
</input></nds>
```

Everything in it is already available to the simulator: connection params and
GCV definitions from the driver export / Designer project, secrets from the
existing named-password supply.

## Integration into the simulator

Two modes, both keep the existing policy stepping intact:

1. **Shim as command sink (primary).** Run the channel as today; take the final
   post–Output-Transform command and call `subscriptionShim.execute(cmd, qp)`.
   Capture the shim's *real* response (status, association, request it built) as
   a terminal `StageSnapshot`. This answers "is the XDS my policies produced
   actually consumable by the connector?" — which `FakeDirectory` cannot, because
   it accepts anything.
2. **Shim as destination query source (optional).** Route destination queries
   (matching against the app) to `subscriptionShim.execute(query, qp)` instead of
   `FakeDirectory`. Higher-fidelity matching; needs the shim's query path to work
   offline.

The back-channel `XmlQueryProcessor` handed to the shim is sourced one of two ways:

- **`FakeDirectory` bridge** — author-supplied `directory.xds`, already in
  native XDS form. No value normalization needed.
- **`LdapQueryProcessor`** — live eDir over LDAP. **This is where value
  representation must be normalized** (next section).

`FakeDirectory` keeps implementing the engine seams; the new code lives behind an
adapter so `ChannelSimulator` is unchanged except for an optional terminal
shim-execute step.

## The hard part: LDAP vs native value representation

ClaimsCenter funnels every LDAP value through `value.toString()` with a single
hand-coded exception (`DirXML-EntitlementRef`). JNDI/LDAP and the native engine
query seam represent several eDir **syntaxes** differently, so a naive copy
produces values the policies/shim never see in production. The fix is to
normalize **keyed on the attribute's syntax**, which the simulator already knows:
`SchemaModel.Attr` carries `syntax` (the export's `syn=`) and `ldap` (the
DirXML↔LDAP name mapping — so ClaimsCenter's hand-built `attributeMap`/`classMap`
become schema lookups).

Syntaxes observed in a real schema export, and how to normalize LDAP → native XDS:

| `syn=` | LDAP/JNDI form | Native XDS form | Action |
|---|---|---|---|
| `ci-string` `ce-string` `pr-string` `nu-string` `class-name` `tel-number` `fax-number` `email-address` | `String` | `<value type="string">` | pass-through |
| `integer` `counter` `interval` | numeric `String` | numeric text | pass-through |
| `boolean` | `"TRUE"`/`"FALSE"` | `"true"`/`"false"` | lower-case |
| `octet-string` `octet-list` `net-address` | `byte[]` (`toString()` ⇒ `[B@…` garbage) | `<value type="octet">` base64 | base64-encode bytes |
| `time` | generalized time `YYYYMMDDHHMMSSZ` | integer seconds (`type="time"`) | parse → epoch seconds |
| `timestamp` | vendor form | integer secs + `timestamp="secs#event"` metadata | parse → secs (+ synthesize event id) |
| `dist-name` | LDAP DN `cn=x,ou=y,o=z` | slash form `\TREE\…` (driver dn-format) | reformat DN (default slash; configurable) |
| `path` | `vol#ns#path` string | structured `<value type="structured">` (nameSpace/volume/path components) | decompose to components |
| `typed-name` | `dn#level#interval` | structured (typedName/level/interval) | decompose to components |
| `object-acl` `replica-pointer` `back-link` `hold` `po-address` | structured/operational | structured | rarely queried; flag, pass-through |
| `stream` | `byte[]` (large) | not meaningfully returned in query | skip + flag |
| `unknown` | `String` | — | pass-through |

Also missing from a JNDI response and synthesized by the native seam: instance
metadata (`qualified-src-dn`, `src-entry-id`, association `state`). These are
best-effort (set association `state="associated"` when an association is present;
omit entry-id) and noted as a fidelity gap rather than faked precisely.

`DirXML-EntitlementRef` (already special-cased in ClaimsCenter as `DN#state#XML`
→ structured value with `nameSpace`/`volume`/`path.xml` components) is a specific
instance of the `path`/structured handling and folds into the same table.

## Core principle: both pieces are optional

The shim and the LDAP query processor are **independent, opt-in add-ons**. The
simulator must run exactly as it does today when neither is configured, and each
must work without the other. Concretely:

- **No shim configured** → the channel runs against `FakeDirectory` and reports
  final output, as today. (Default. Nothing changes for existing cases.)
- **No LDAP configured** → query answers come from the author-supplied
  `directory.xds` via `FakeDirectory`. The normalizer/`LdapQueryProcessor` are
  never instantiated, so live-LDAP is never a prerequisite for anything.
- **Shim, no LDAP** → run the chain, feed the final command to the real shim, and
  hand the shim a back-channel `XmlQueryProcessor` **backed by `FakeDirectory`**
  (author state, already native-form). This is the common, infra-free path:
  validate the connector consumes the payload without needing a live tree.
- **LDAP, no shim** → answer policy queries from live eDir (with normalization)
  while the command sink stays `FakeDirectory`. Useful to test policies against
  real instance data without a connector.
- **Both** → highest fidelity; live queries + real shim.

The decision is per-feature and made at case load: a missing `shim*` /`ldap*`
key simply leaves that collaborator null, and the simulator selects the
`FakeDirectory`-backed path. No feature flag gates the others on. If a shim jar
or LDAP host is configured but unreachable, fail that case with a clear
diagnostic rather than silently degrading — but an *absent* config is a normal,
fully-supported mode, not an error.

## Component plan

New classes under `com.pointblue.dirxml.sim` (and `…sim.shim`):

1. **`LdapValueNormalizer`** — pure function `(syntax, Object ldapValue) → XDS value`
   per the table above. No external deps; fully unit-testable offline. **This is
   the novel, reusable core** and the first thing to build.
2. **`InitDocBuilder`** + **`ShimConfig`** — the init-doc parameters are already
   **defined in the source**, so they are extracted, not hand-authored:
   - **Driver export** (`<driver-configuration>`): `<java-module value>` (shim
     class), `@dn`, `<authentication-info>`, and the top-level
     `<driver-options>`/`<subscriber-options>`/`<publisher-options>` blocks
     (each `<configuration-values><definitions>` of resolved values). An export
     can also carry empty placeholder blocks inside `<shim-config-info-xml>`, so
     the extractor prefers a block that actually holds `<definition>`s.
     → `DriverExport.shimConfig()`.
   - **Designer project** (`.Driver_` CObject): `DirXML-JavaModule` (shim class),
     `DirXML-ShimAuthServer`/`DirXML-ShimAuthID`, and the option blocks parsed
     from the `DirXML-ShimConfigInfo` `<driver-config>`.
     → `DesignerProject.shimConfig(driver)`.
   `InitDocBuilder.shimConfig(cfg, password)` embeds each option block verbatim
   (the `<configuration-values>` the shim reads) **and** flattens each definition
   to a flat `<name>value</name>` child — emitting both forms, as the engine does,
   since shims read one or the other. The password is the only piece not in the
   source; it comes from the named-password channel. Pure transform; testable
   offline.
3. **`LdapQueryProcessor`** — `XmlQueryProcessor` that maps an XDS `<query>` to an
   LDAP search via `SchemaModel` (names) and rebuilds the response via
   `LdapValueNormalizer` (values). Needs a live eDir to exercise end-to-end;
   the name-mapping and response-building are unit-testable with a stubbed search.
4. **`ShimAdapter`** — loads a `DriverShim` by class name, runs the
   `init`/`getSubscriptionShim().init` lifecycle, and exposes
   `execute(command)` (command sink) and an optional destination-query path.
   Needs the proprietary shim jar + (for query) a live/sandbox target to validate.

### Wiring (case + CLI)

A case opts into shim testing via `case.properties` (all optional; absent ⇒
today's behavior):

```properties
# drive a real shim as the command sink
shim=true
shimClass=com.pointblue.idm.claimscenter.CCDriverShim
shimJar=/path/to/ShimDriver.jar          # added to a child classloader; gitignored
shimInit=shim-init.xml                    # explicit init-params, or let InitDocBuilder synthesize
# back-channel / destination queries from live LDAP instead of directory.xds
ldap=ldap://host:636
ldapBindDn=cn=admin,ou=sa,o=system
ldapBindPassword.named=ldap-bind          # via existing named-password supply
ldapSearchBase=o=data
```

The `shim*` and `ldap*` key groups are independent: supply either, both, or
neither. With no `shim*` keys the chain ends at `FakeDirectory` (today's
behavior); with no `ldap*` keys queries resolve against `directory.xds`. `Case`
holds each collaborator as a nullable field, and `ChannelSimulator` picks the
`FakeDirectory`-backed path whenever the field is null — existing cases load and
run byte-for-byte unchanged.

Secrets stay in the named-password channel; shim jars stay in `lib/`/gitignored
like the engine jars. No proprietary artifacts enter source control.

## Build order

1. `LdapValueNormalizer` + unit tests (offline, no shim/LDAP needed). ← start here
2. `InitDocBuilder` + unit tests (offline).
3. `LdapQueryProcessor` (name-mapping/response-build unit-tested with a stub
   search; live-LDAP path behind config).
4. `ShimAdapter` + CLI/case wiring; validate against a real shim jar
   (Delimited Text / Loopback first, then ClaimsCenter against a sandbox).

Steps 1–2 are fully testable here and now. Steps 3–4 need the proprietary shim
jar and a live/sandbox target to validate end-to-end, so they land behind config
and are exercised against a real environment.

## Non-goals / fidelity gaps

- **Publisher channel.** `PublicationShim.start()` is a long-running poll loop;
  it doesn't fit synchronous stepping. Shim testing means Subscriber-out.
- **Native shims** (AD-local, eDir) aren't loadable in-process on macOS — Remote
  Loader bridge is a separate, later effort.
- **DN format** for `dist-name` depends on the driver's configured dn-format; the
  normalizer defaults to slash form and exposes a setting.
- **Instance metadata** (`src-entry-id`, exact `timestamp` event ids) from a live
  LDAP read is best-effort, not byte-identical to the native seam.
