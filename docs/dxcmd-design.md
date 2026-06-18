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

## Phase 2 — submit to the live engine (DEFERRED — build-ready blueprint)

**Status: deferred by request (2026-06-18). Not built. This section is the complete
blueprint so it can be picked up later without re-investigating.**

Send an XDS document to a **running** driver and get the engine's real output — a
**fidelity check**: run the case headlessly, submit the same input to the real
engine, and diff the two.

### Protocol (verified from `DxCommand.submitXDS`)

Same chunked-result mechanism as the cache read, just a different request. Mirror
`DxCommand.submitXDS(driverDN, fileData, version)`:

```
version 0 (command) → SubmitCommandRequest(driverDN, 1, fileData)   // subscriber-channel command
version 1 (event)   → SubmitEventRequest(driverDN, 1, fileData)     // publisher-channel event
  → response is a ChunkedResultResponseBase: getDataHandle(), getDataSize()
  → if handle==0 || size==0 ⇒ no result document
  → else pull with GetChunkedResultRequest / CloseChunkedResultRequest (reuse
    DxCacheReader.getChunkedResult) → the engine's response XDS
```

`fileData` is the XDS document bytes (UTF-8). Register the typed responses first
(`SubmitCommandResponse.register()` / `SubmitEventResponse.register()`,
`GetChunkedResultResponse.register()`), exactly like the cache reader. OIDs:
SubmitCommand=30, SubmitEvent (see `DirXMLExtensions`). All classes are in
`dirxml_misc`; the only dependency is the same `lib/ldap.jar`.

### Implementation sketch

- A `DxEngineSubmit` class alongside `DxCacheReader` (reuse `connect()`,
  `getChunkedResult()`, the trust-all manager, and the `GetDriverState` check).
- Direction → version: subscriber command = 0, publisher event = 1 (match the
  case's `channel=`).

### Required guardrails (the reason it's gated)

- The driver must be **running** (state 2) — the inverse of the cache read; check
  `GetDriverState` and refuse with a clear message otherwise.
- Submit **genuinely executes** through the engine — real side effects on the
  connected application. So:
  - **explicit, never automatic** — a distinct verb (e.g. `bin/sim submit <caseDir>`
    or a `verify-vs-engine` mode), never part of `run`/`test`;
  - **confirm before submit** (interactive confirm or an explicit
    `iUnderstandThisHitsTheRealApp=true` case key);
  - intended for a **test driver**, not production;
  - document loudly that it is not a dry run.
- Surface the engine's response next to the headless output for a diff (the payoff:
  proves the simulator matches — or finds where it diverges from — the real engine).

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
