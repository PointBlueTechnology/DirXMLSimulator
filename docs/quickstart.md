# Quickstart

From zero to stepping through your own driver's policies. Commands are
copy-pasteable; `$` lines are shell.

## 1. One-time setup

**JDK 21** is required (the engine jars are Java 21 bytecode). The `bin/sim`
launcher finds it automatically, or set `SIM_JAVA_HOME`.

**Stage the jars.** Copy the nine NetIQ jars into `lib/` (see the
[README](../README.md#requirements) for the list and where they come from on a
server). Then build and self-check:

```bash
$ mvn compile
$ bin/sim doctor
DirXML Policy Simulator — doctor
  java.version: 21.0.8  OK
  engine jars: OK
  engine smoke run: OK
  sample case cases/copy-surname: PASS
DOCTOR: OK
```

`DOCTOR: OK` means you're ready.

**Using it as a skill.** This project ships a Claude Code skill. Working *in* this
repo, it's active automatically — just ask the agent to test or debug a policy. To
use it from any other project, install it globally:

```bash
ln -s "$(pwd)/.claude/skills/dirxml-policy-testing" ~/.claude/skills/dirxml-policy-testing
```

(The skill drives `bin/sim`, so keep this built repo reachable.) See the
[README](../README.md#install-it-as-a-claude-code-skill) for details. The rest of
this guide is what the agent does for you — and what you can run by hand.

## 2. Prove it on the bundled sample (no export needed)

A complete sample case ships with the project:

```bash
$ bin/sim step cases/copy-surname
```

You'll see the single stage's input and output, the `<query>` the policy issued,
the directory's answer, and the rule trace — the value `Surname=Doe` flowing from
the fake directory into a `CopiedSurname` attribute. That's the whole loop in
miniature.

## 3. Run your own driver from a trace + export

### a. Get the artifacts

- **Driver config** — any one of:
  - an **LDIF/LDAP export of the live vault** (easiest — one dump carries the
    policy chain, GCVs, filter, and shim params for the *whole driver set*, and
    can seed the directory too; see [3c](#c-point-the-case-at-the-driver-config)),
  - a **driver export** (`.xml` from Designer → *Export to Configuration File*),
  - a **Designer project** on disk (your `designer_workspace` project + driver
    name) — also carries the schema, so inputs get validated.
- **Directory data + an input event** — a DSTrace / driver trace log from your
  environment (turn trace up, reproduce the event, save the log) **or** an LDIF
  dump of the relevant objects. With a live connection you can also pull a
  **stopped driver's event cache** (its queued subscriber events) directly with
  `bin/sim dxcache` — see step (e).

### b. Bootstrap a case from the trace

```bash
$ bin/sim extract /path/to/driver.trace cases/my-test
parsed 21 XDS documents from driver.trace
  event: 1
  query-result: 3
  ...
wrote input.xds  (channel=Subscriber)
wrote case.properties stub (channel=subscriber)
wrote directory.xds  (3 query results merged)
```

This creates `cases/my-test/` with:
- `input.xds` — the real event from the trace,
- `directory.xds` — the directory data the policies looked up,
- `case.properties` — a stub with the channel inferred,
- `trace-samples/` — every document in the trace, labeled.

### c. Point the case at the driver config

Edit `cases/my-test/case.properties`. Use an **LDIF vault export** + driver name:

```properties
ldifConfig=/path/to/IDM_subtree.ldif
driver=CyberArk
channel=publisher        # or subscriber (the extract step inferred one)
driverDN=\TREE\system\driverset\MyDriver
```

**or** a **driver export**:

```properties
export=/path/to/driver.xml
channel=publisher
driverDN=\TREE\system\driverset\MyDriver
```

**or** a **Designer project** + driver name:

```properties
project=/path/to/designer_workspace/MyProject
driver=CyberArk-PROD
channel=publisher
driverDN=\TREE\system\driverset\MyDriver
```

Any of them assembles your real channel chain (in IDM policy-set order) and loads
the driver's GCVs and ECMAScript resources. With `project=` it also loads the
**schema** and validates `input.xds`/`directory.xds` against it.

> **Producing the LDIF** — a plain `ldapsearch *` omits the DirXML policy/config
> attributes, so request them explicitly:
> ```bash
> ldapsearch -o ldif-wrap=no -b "cn=<DriverSet>,o=system" -s sub "(objectclass=*)" \
>   '*' XmlData DirXML-Policies DirXML-ShimConfigInfo DirXML-ConfigValues \
>   DirXML-JavaModule DirXML-DriverFilter DirXML-ShimAuthServer DirXML-ShimAuthID
> ```
> The same file (or a `'*'`-only dump) can seed the fake directory with real
> objects — add `ldif=that-file.ldif` to the case.

### d. Step through it

```bash
$ bin/sim step cases/my-test
```

For each stage you get the document before and after, any queries/commands it
issued, and its trace. Add `--rules` to expand a policy rule by rule:

```bash
$ bin/sim step cases/my-test --rules
```

Find the stage (or rule) where a value first appears, gets vetoed, or comes out
wrong — and read that stage's trace to see why.

### e. (Alternative input) pull a stopped driver's event cache

No trace? If the driver is **stopped** and you have a live connection, read its
queued subscriber events directly into the case:

```properties
# case.properties — connection + the driver whose cache to read
ldap=ldaps://host:636
ldapBindDn=cn=admin,ou=sa,o=system
ldapBindPassword=...
cacheDriver=cn=MyDriver,cn=driverset1,o=system
```

```bash
$ bin/sim dxcache cases/my-test
wrote cases/my-test/cache.xds  (22 cached events, …)
wrote input.xds (the cached events as one <input> batch)
```

It writes the real pending events as `input.xds`. A **running** driver is detected
and reported (stop it first). Needs the optional `lib/ldap.jar` (Novell LDAP SDK).

## 4. Test a change

Lock in the current behavior as a golden, edit the policy, and verify:

```bash
$ bin/sim record cases/my-test          # save expected-output.xds
# ... edit the policy file the case points at ...
$ bin/sim test cases/my-test            # exit 0 = unchanged, 1 = changed (with a diff)
```

Use `test` to prove a fix does what you intend and nothing else regresses.

## Command reference

```
bin/sim run    <caseDir> [--trace]   run the chain; final output (+ full trace)
bin/sim step   <caseDir> [--rules]   per-stage (or per-rule) input/output/queries/trace
bin/sim dxcache <caseDir>            read a stopped driver's event cache (live) into the case
bin/sim test   <caseDir>             diff vs goldens; exit 0 pass, 1 mismatch
bin/sim record <caseDir>             write expected-output.xds / expected-directory.xds
bin/sim extract <trace> <outDir>     mine a DSTrace log into a case
bin/sim doctor                       setup self-check
```

## Letting an agent drive

Everything above is what an agent does on your behalf — point it at an export and
a trace and ask in plain English ("why isn't email syncing — step the subscriber
channel and find the rule that drops it"). See [intro.md](intro.md) for the
pitch and example asks, and the skill's `SKILL.md` for how the agent uses these
commands.
