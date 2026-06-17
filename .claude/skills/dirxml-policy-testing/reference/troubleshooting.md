# Troubleshooting

## Setup

- **`bin/sim doctor` says JDK 21 missing.** The 4.10.1 engine jars are Java 21
  bytecode. Install a JDK 21 or `export SIM_JAVA_HOME=/path/to/jdk21`. The
  launcher otherwise tries `java_home -v 21` and common locations.
- **engine jars INCOMPLETE.** Stage all 8 proprietary NetIQ jars in `lib/`
  (gitignored): `dirxml`, `dirxml_misc`, `nxsl`, `xp`, `CommonDriverShim`,
  `jclient`, `dhutil`, `XDS`. Copy from an IDM install or an "IDM Driver
  Dependencies" set. `lib/README.md` lists them.
- **`JCContext` native error.** Harmless — `JCContext` is only ever a null
  parameter type; never initialize it. `doctor` checks classes without
  initializing for this reason. If your own code triggers it, you referenced
  JCContext in a way that loads it.

## Results

- **Output is `<input/>` (empty).** The operation was vetoed or stripped. Run
  `step` and find the stage where the operation disappears; its trace shows the
  `do-veto` / `do-strip` / failed condition. This is often *correct* behavior for
  a minimal input (e.g. a Create policy vetoing for missing required attributes,
  or a Scoping/Event policy filtering it out). Add the required attrs/GCVs.
- **A `token-global-variable` resolves to empty.** The GCV isn't in scope. If
  using an export, confirm the name exists in it; otherwise supply a `gcv.xml`.
  Hand-written GCV `<definition>`s need a `display-name` or the engine silently
  rejects the block. `dirxml.auto.driverdn` is auto-seeded from `driverDN` in
  `case.properties` (set that to a realistic slash DN if a policy queries the
  driver object); other `dirxml.auto.*` engine GCVs aren't populated — add them to
  `gcv.xml` if a policy needs them.
- **`es:` function fails / `Script processor not available`.** The policy calls an
  ECMAScript extension function (e.g. `es:getSurname`). Provide the JavaScript that
  defines it: set `export=` (the harness loads the export's ECMAScript resources
  automatically) and/or drop the `.js` source in the case's `ecmascript/`
  directory. Needs `lib/js.jar` (repackaged Rhino) — `doctor` covers the jars. If a
  function is still undefined, its source isn't among the loaded scripts. (Some
  NetIQ built-in `es:` helpers live in product ECMAScript libraries, not the
  driver — supply those `.js` files in `ecmascript/` if a policy uses them.)

  Scripting is **Rhino ECMAScript**, which is what IDM drivers use in practice. The
  engine's other JSR-223 languages (Nashorn/Python/Ruby/Groovy) aren't wired and
  aren't expected to be needed.
- **`function 'foo:bar' not found` — Java extension class missing.** A policy can
  bind a namespace to a Java class and call its static methods:
  `xmlns:m="http://www.novell.com/nxsl/java/java.lang.Math"` then `m:max(3,7)`.
  These work whenever the class is on the classpath. If it isn't, the engine
  reports a vague "function not found" — but `run`/`step`/`test` print an explicit
  `WARNING: Java extension classes not on the classpath: …` up front listing the
  missing class(es). Add the jar that contains it to `lib/`.
- **A stage shows `[ERROR]`.** The policy threw (bad XPath, unavailable script
  function, missing Java extension class, unresolvable target). The run stops at
  that stage but shows all prior snapshots and the error message; the offending
  action is named in the message.
- **Named passwords.** A named password is a secret *value* (like a GCV) kept out
  of the export. Supply it per case: `namedPassword.<name>=<value>` in
  `case.properties`; `token-named-password` then resolves to it. A referenced name
  you don't supply resolves to empty and prints `WARNING: named password(s)
  referenced but not supplied`.
- **External actions (REST / email / RBPM / workflow) are faked.** Actions that
  would connect out — `do-invoke-rest-endpoint`, `do-send-email[-from-template]`,
  `do-add-role`/`do-create-resource`/… (RBPM SOAP), `do-start-workflow`,
  `do-generate-xdas-event`, the SSO-credential actions — are rewritten to safe
  stand-ins (faking is on by default). They make no live call (so the run can't
  hang), are logged as `FAKED: <action> …` in the trace, and the policy continues.
  For `do-invoke-rest-endpoint`, supply the response a downstream rule needs via
  `restResponse=<body>` (or `restResponse.<urlSubstring>=<body>`, or a
  `rest-response.json` file) — it's injected into the `success.do-invoke-rest-endpoint`
  local variable. `fakeActions=false` turns faking off (the actions then attempt the
  real call and fail or hang).

  **Entitlements are NOT external** — `token-added-entitlement`,
  `if-entitlement`, `do-implement-entitlement`, etc. are *op-driven*: they read the
  `DirXML-EntitlementRef` values on the operation. They work fine as long as your
  **input op carries the entitlement change** (trace-mined inputs do). If an
  entitlement token comes back empty, the input is missing the
  `DirXML-EntitlementRef` add/remove-value — not a harness limitation.
- **A queried value is missing/wrong.** In `step`, read the stage's QUERIES: did
  the policy ask for that attribute, with what `<search-attr>`/scope? Then check
  `directory.xds` actually contains a matching `<instance>` with that attr.
  Matching is case-insensitive value equality; `read-attr` projects which attrs
  come back (none specified ⇒ all).
- **Queries hit the wrong side.** Check `fromNDS`. `true` = Subscriber (dest =
  app), `false` = Publisher (dest = eDir). `token-dest-attr` / `token-src-attr`
  follow this.
- **`regExEscChars` / null NPE in VarString or do-reformat.** The harness already
  supplies the engine's regex escape class via the 10-arg context; if you build a
  context yourself, use `EngineContext.create(...)` rather than constructing
  `RuleStaticContext` directly.

## Authoring / DOM

- **DirXML DOM is Level 2.** When writing harness code, never use
  `Node.getTextContent()` (returns null on the Novell DOM) — use `Xds.text(node)`.
  Parse/serialize via `Xds` (it routes through `XmlDocument` so DOM edits
  re-serialize and the DOM is the `com.novell.xml.dom` impl the engine needs).
- **Golden compares.** `XmlCompare` canonicalizes whitespace but is
  attribute-order sensitive; if a `test` fails only on attribute order,
  re-`record` the golden. Re-record whenever you intend the output to change.
- **A stage shows `[no-op]`.** The policy ran but didn't change the document
  (conditions didn't match, or actions were inert). The trace still shows the
  rule evaluation — check which conditions were false.
