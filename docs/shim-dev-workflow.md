# Developing a driver shim alongside the simulator (IntelliJ)

A practical guide for the inner loop where you are **writing a driver shim** in
its own IntelliJ project (with the Claude Code plugin) and want to exercise it
through the simulator's real-shim command sink (`shim=true`) on every build —
without merging the two projects.

The design goal: **the simulator and the shim project stay in separate
directories**, while the **test artifacts (input events and reference/golden
data) live in the shim project's own repo**. The harness supports this directly;
no special build integration is needed.

> **IDE-agnostic.** This guide is written with IntelliJ in mind (it's the common
> case for a Java shim), but nothing here is IntelliJ-specific. Any editor or IDE
> with a Claude Code integration — VS Code, a JetBrains IDE, or the `claude` CLI
> run from the shim project — works the same way. The only IDE-dependent detail is
> the build-output path you point `shimJar=` at: `target/classes` for Maven,
> `build/classes/java/main` for Gradle, `out/production/<module>` for IntelliJ's
> own compiler. Pick the one your build produces.

## Why it just works

Two existing mechanics make the separation clean:

1. **`bin/sim run <caseDir>` accepts any path.** A case directory does not have to
   live inside the simulator repo — point the CLI at a folder in your shim repo.
2. **`shimJar=` is resolved relative to the case directory** (`caseDir.resolve(...)`).
   So a case that lives in the shim repo can reference the shim's own build output
   with a relative path that climbs back into the shim tree — and the simulator
   binary never moves.

The shim's classloader is parented on the simulator's own loader, so the
`DriverShim` / `SubscriberShim` / `CommonDriverShim` types resolve from the engine
jars the simulator already carries. You only list **what the shim adds** in
`shimJar=`.

## Recommended layout

```
~/IdeaProjects/
  DirXMLSimulator/            # the harness — built once (bin/sim doctor → OK); not opened day to day
    bin/sim
    lib/*.jar                 #   the 9 NetIQ jars (incl. CommonDriverShim.jar)
  MyRestShim/                 # ← open THIS in IntelliJ; the plugin runs here
    src/main/java/...
    target/classes            #   Maven; or build/classes/java/main (Gradle); or out/production (IDEA)
    driver-config/MyRestShim.xml   # a Designer export of the driver, kept in-repo
    sim-tests/                # ← test artifacts live HERE, in your repo
      add-user/
        case.properties
        input.xds             #   the operation to run
        directory.xds         #   seed data the shim's back-channel queries read
        expected-output.xds   #   golden (reference data) — written by `record`
      modify-email/ ...
    AGENTS.md                 # tells the plugin where the simulator lives
```

## The case (in the shim repo)

`sim-tests/add-user/case.properties`:

```properties
# driver policies — an export kept in the repo (or ldifConfig=/ldapConfig= against a live tree)
export=../../driver-config/MyRestShim.xml
channel=subscriber

# drive the REAL shim with the chain's final command
shim=true
shimClass=com.example.rest.RestDriverShim     # optional — defaults to the export's java-module
shimJar=../../target/classes                  # ← relative to THIS case dir = your live build output
shimAuthPassword.named=app-password
```

`shimJar=` is comma-separated and the classloader is parented on the simulator's
own, so list only what the shim adds beyond `CommonDriverShim` and the engine:

- **Pure-Java connector, only depends on `CommonDriverShim`** →
  `shimJar=../../target/classes` is enough.
- **Has third-party deps** (HTTP/JSON libraries) → build a shaded/uber jar
  (`shimJar=../../target/MyRestShim-all.jar`) **or** list them:
  `shimJar=../../target/classes,../../target/deps/httpclient.jar,...`

## The inner loop in IntelliJ

1. Edit shim code in IntelliJ.
2. Build it — IDEA compile, or `mvn -o package` / `gradle jar` — which refreshes
   `target/classes` (or the jar).
3. Ask the plugin: *"run the add-user sim case against my shim."* It invokes
   `~/IdeaProjects/DirXMLSimulator/bin/sim run sim-tests/add-user`.
4. The fresh classes are picked up automatically because `shimJar=` points at the
   live build output — **no copy or reinstall step.**

Use `bin/sim test sim-tests/add-user` to diff against `expected-output.xds`
(non-zero exit on mismatch), and `bin/sim record sim-tests/add-user` to capture
the current shim response as the golden after a deliberate change.

## Pointing the plugin at the simulator

The Claude Code plugin running in the shim project needs to know the harness
exists, how to drive it, and where it is:

- **Install the skill globally** so the agent knows the case format and the
  `bin/sim` loop in any session:
  ```bash
  ln -s ~/IdeaProjects/DirXMLSimulator/.claude/skills/dirxml-policy-testing \
        ~/.claude/skills/dirxml-policy-testing
  ```
- **Add a pointer to the shim repo's `AGENTS.md`** (the plugin reads it):
  > IDM shim testing: drive the simulator at `~/IdeaProjects/DirXMLSimulator/bin/sim`.
  > Cases live in `sim-tests/`. To exercise this shim, set `shim=true` with
  > `shimJar=` pointed at `target/classes`. See the `dirxml-policy-testing` skill.

To keep the absolute path in one place, a tiny `sim-tests/run.sh`
(`exec ~/IdeaProjects/DirXMLSimulator/bin/sim "$@"`) or a `SIM_HOME` env var works
well.

## Two caveats worth knowing

- **Compile against the same `CommonDriverShim.jar`** the simulator carries in
  `lib/`. The shim's interface types come from the parent loader, so a version
  skew surfaces as a `LinkageError`. For a 4.10.1 simulator, build the shim against
  4.10.1.
- **Classes-dir vs jar.** `shimJar=../../target/classes` works because
  `Path.toUri()` adds the trailing slash *for a directory that exists* — so you
  must have built at least once. For CI or a hand-off, prefer a built jar; for the
  fast inner loop, the classes dir is the convenience.

## Keeping artifacts out of the wrong repo

- The shim jar/classes are **build output of the shim repo** — never copied into
  the simulator, never committed there.
- The cases (`input.xds`, `directory.xds`, `expected-*.xds`) are **operational
  data of the shim repo** — they live with the shim and version with it. Treat
  exports and any seeded directory data as sensitive (see the note in
  [intro.md](intro.md)); `.gitignore` anything carrying real instance data or
  secrets.

See the design rationale in [shim-testing-design.md](shim-testing-design.md) and
the full case-key reference in the skill's
[`reference/xds-and-cases.md`](../.claude/skills/dirxml-policy-testing/reference/xds-and-cases.md).
