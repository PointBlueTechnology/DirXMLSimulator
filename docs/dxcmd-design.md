# Design note: DxCMD as an input source

Status: **Phase 1 implemented** (2026-06-18). `dxcmd` (DxCommand, in `dirxml_misc`)
talks to a running IDM engine over **LDAP extended operations**. Two of its
capabilities are useful as harness inputs:

1. **Read a driver's event cache** — the queued **subscriber-channel** transactions
   of a stopped/idle driver: real sample input events, without a trace. *(built)*
2. **Submit a document to the live engine** — run an XDS doc through the real
   running driver and capture its response: ground truth to compare the headless
   simulator against. *(Phase 2, not built)*

## How it works (verified against `dirxml_misc`)

Every operation is an LDAP **extended operation**. The request classes in
`com.novell.nds.dirxml.ldap` extend `com.novell.ldap.LDAPExtendedOperation` *and*
implement `javax.naming.ldap.ExtendedRequest`, so they work over either the Novell
JLDAP SDK (`LDAPConnection.extendedOperation`, dxcmd's native path) or JNDI. BER
encoding is handled by `DirXMLExtensions` (already in `dirxml_misc`).

Cache read mirrors `DxCommand.readCache` + `getChunkedResult`:

```
ViewCacheEntriesRequest(driverDN, timeout=1, position, count, cacheType)
  → ViewCacheEntriesResponse: getDataHandle(), getDataSize(), getPositionToken()
  → if handle==0 || size==0  ⇒ cache is EMPTY (or no more entries)
  → else pull the data in ≤64512-byte chunks:
       GetChunkedResultRequest(handle, chunkSize, 0) → GetChunkedResultResponse.getData()
     until drained, then CloseChunkedResultRequest(handle)
```

The assembled bytes are a single well-formed XDS document — one `<nds><input>…`
holding every cached event as a sibling operation, carrying the engine's real
event metadata (`cached-time`, `event-id`, `qualified-src-dn`, `src-entry-id`,
`timestamp`). Directly usable as `input.xds`.

## Additional dependency

**`ldap.jar` — the Novell/OpenText JLDAP SDK (`com.novell.ldap.*`)** — a **10th**
jar in `lib/`, **not** among the original nine. Required because the request
classes extend `LDAPExtendedOperation`. It is **optional**: only the DxCMD features
need it; everything else runs without it. Any 4.10.x `ldap.jar` works (it ships
with IDM and Designer). Gitignored like the other `lib/*.jar`.

Everything else — the request/response classes, the chunked-result protocol,
`DirXMLExtensions` — is already in the `dirxml_misc` jar we stage.

## Phase 1 — `DxCacheReader` (built & validated live)

- `DxCacheReader` connects with the Novell `LDAPConnection` (trust-all TLS by
  default, like the rest of the harness), registers the typed responses, and runs
  the cache-read protocol above. Returns the events as XDS (or empty).
- CLI: **`bin/sim dxcache <caseDir>`** reads the connection + `cacheDriver=<DN>`
  from `case.properties` (`ldap=`/`ldapBindDn`/`ldapBindPassword`, optional
  `cacheCount`/`cacheToken`) and writes `cache.xds` (raw) and `input.xds` (if
  absent). Reports an empty cache plainly.

Validated against a live server: read **22 queued events** from a stopped
`cn=Active Directory Driver,cn=driverset1,o=system` in ~0.25 s — real `<modify>`
operations on User objects — and bootstrapped a runnable case from them.

## Phase 2 — submit to the live engine (not built)

`SubmitEventRequest`/`SubmitCommandRequest` (+ responses) send an XDS document to a
**running** driver and return the engine's real output. Use as a **fidelity check**:
run the case headlessly, submit the same input to the real engine, and diff. High
value, but with real caveats:

- the driver must be **running**, and the submit genuinely **executes** through the
  engine — real side effects on the connected app. Must be pointed at a test driver,
  with confirm-before-submit guardrails.
- best surfaced as a distinct, explicit mode (e.g. `bin/sim verify-vs-engine`), never
  automatic.

## Constraints / notes

- Needs a live LDAP connection to the IDM **server hosting the driver** (the engine
  runs there); the bind identity needs rights on the driver object.
- Cache read requires a **stopped** driver — a running one is actively draining its
  cache and the engine rejects the read with an opaque `LDAP_OTHER` ("Other"). The
  reader checks `GetDriverState` first (0=stopped, 1=starting, 2=running, 3=stopping,
  verified live) and, if the driver isn't stopped, returns `readable=false` with the
  state so the CLI says "driver is running — stop it to read its cache" instead of
  surfacing the cryptic error.
- Cached events carry engine metadata attributes the headless run tolerates (the
  interpreter ignores unknown attributes); they reflect real production traffic.
