package com.pointblue.dirxml.sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command-line entry point an agent drives:
 *
 * <pre>
 *   run    &lt;caseDir&gt; [--trace]   run the chain, print final output (+ trace)
 *   step   &lt;caseDir&gt;            print each stage: input/output/changed/queries/trace
 *   test   &lt;caseDir&gt;            run and diff vs expected-*.xds; exit !=0 on mismatch
 *   test-all &lt;dir&gt;             run every case under dir; summary + CI reports; exit !=0 on any fail
 *   compare &lt;caseDir&gt; --against &lt;cfg&gt;  same input through two policy sets; per-stage divergence
 *
 *   run/step/test/compare accept --json for structured output.
 *   record &lt;caseDir&gt;            run and write expected-output.xds / expected-directory.xds
 *   harvest &lt;cfgDir&gt; &lt;outDir&gt;   mint a regression corpus from real Event Logger DB events
 * </pre>
 */
public final class Cli {

    public static void main(String[] args) {
        if (args.length >= 1 && args[0].equals("doctor")) {
            System.exit(doDoctor());
        }
        if (args.length >= 1 && args[0].equals("extract")) {
            if (args.length < 3) {
                System.err.println("usage: extract <traceFile> <outDir>");
                System.exit(2);
            }
            try {
                System.out.println(TraceExtract.extractToDir(Paths.get(args[1]), Paths.get(args[2])));
                System.exit(0);
            } catch (Exception e) {
                System.err.println("ERROR: " + e.getMessage());
                System.exit(3);
            }
        }
        if (args.length >= 1 && args[0].equals("dxcache")) {
            if (args.length < 2) {
                System.err.println("usage: dxcache <caseDir>   "
                    + "(reads ldap=/ldapBindDn/ldapBindPassword/cacheDriver from case.properties)");
                System.exit(2);
            }
            try {
                System.exit(doDxCache(Paths.get(args[1])));
            } catch (Exception e) {
                System.err.println("ERROR: " + e.getMessage());
                System.exit(3);
            }
        }
        if (args.length >= 1 && args[0].equals("dbevents")) {
            if (args.length < 2) {
                System.err.println("usage: dbevents <caseDir>   "
                    + "(reads db=/dbUser/dbPassword + event filters from case.properties)");
                System.exit(2);
            }
            try {
                System.exit(doDbEvents(Paths.get(args[1])));
            } catch (Exception e) {
                System.err.println("ERROR: " + e.getMessage());
                System.exit(3);
            }
        }
        if (args.length >= 1 && args[0].equals("harvest")) {
            if (args.length < 3) {
                System.err.println("usage: harvest <configCaseDir> <outDir> [--refresh]   "
                    + "(reads db=/filters + a config source from <configCaseDir>/case.properties)");
                System.exit(2);
            }
            try {
                System.exit(doHarvest(Paths.get(args[1]), Paths.get(args[2]), hasFlag(args, "--refresh")));
            } catch (Exception e) {
                System.err.println("ERROR: " + e.getMessage());
                System.exit(3);
            }
        }
        if (args.length >= 1 && args[0].equals("test-all")) {
            if (args.length < 2) {
                System.err.println("usage: test-all <dir> [--junit <file>] [--json <file>]");
                System.exit(2);
            }
            try {
                System.exit(doTestAll(Paths.get(args[1]),
                    flagValue(args, "--junit"), flagValue(args, "--json")));
            } catch (Exception e) {
                System.err.println("ERROR: " + e.getMessage());
                System.exit(3);
            }
        }
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        String cmd = args[0];
        Path caseDir = Paths.get(args[1]);
        boolean wantTrace = hasFlag(args, "--trace");
        boolean wantJson = hasFlag(args, "--json");

        try {
            switch (cmd) {
                case "run":
                    System.exit(doRun(caseDir, wantTrace, wantJson));
                    break;
                case "step":
                    System.exit(doStep(caseDir, hasFlag(args, "--rules"), wantJson));
                    break;
                case "test":
                    System.exit(doTest(caseDir, wantJson));
                    break;
                case "compare":
                    System.exit(doCompare(caseDir, flagValue(args, "--against"), wantJson));
                    break;
                case "record":
                    System.exit(doRecord(caseDir));
                    break;
                default:
                    usage();
                    System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(3);
        }
    }

    /** Up-front diagnostics: missing Java extension classes and unsupported subsystems. */
    private static void warnDiagnostics(Case c) {
        java.util.List<String> missing = c.sim.missingJavaClasses();
        if (!missing.isEmpty()) {
            System.out.println("WARNING: Java extension classes not on the classpath (calls to them "
                + "will fail with 'function not found'); add the jar(s) to lib/:");
            for (String cn : missing) {
                System.out.println("  - " + cn);
            }
            System.out.println();
        }
        java.util.List<String> external = c.sim.externalActions();
        if (!external.isEmpty()) {
            if (c.ctx.fakeConfig().enabled) {
                System.out.println("note: external actions are faked (recorded as 'FAKED: …' in the "
                    + "trace, no live call; do-invoke-rest-endpoint uses canned responses if supplied): "
                    + String.join(", ", external));
            } else {
                System.out.println("WARNING: faking is off — these actions make live external calls and "
                    + "will fail or hang (set fakeActions=true): " + String.join(", ", external));
            }
            System.out.println();
        }
        if (!c.schemaWarnings.isEmpty()) {
            System.out.println("WARNING: schema validation (input/directory vs the project schema):");
            for (String w : c.schemaWarnings) {
                System.out.println("  - " + w);
            }
            System.out.println();
        }
        // Named passwords are supplied per case; warn only for referenced names with no value.
        java.util.Set<String> configured = c.directory.namedPasswordNames();
        java.util.List<String> unset = new java.util.ArrayList<>();
        for (String n : c.sim.referencedNamedPasswords()) {
            if (!configured.contains(n)) {
                unset.add(n);
            }
        }
        if (!unset.isEmpty()) {
            System.out.println("WARNING: named password(s) referenced but not supplied (resolve to empty); "
                + "set in case.properties as namedPassword.<name>=<value>:");
            for (String n : unset) {
                System.out.println("  - " + n);
            }
            System.out.println();
        }
    }

    private static int doRun(Path caseDir, boolean wantTrace, boolean wantJson) {
        Case c = Case.load(caseDir);
        ChannelSimulator.Result r = c.run();
        if (wantJson) {
            java.util.List<String> stages = new java.util.ArrayList<>();
            for (StageSnapshot s : r.stages) {
                stages.add(Json.obj("name", Json.q(s.stageName),
                    "changed", bool(s.changed()),
                    "queries", Integer.toString(s.queries.size()),
                    "commands", Integer.toString(s.commands.size())));
            }
            System.out.println(Json.obj(
                "command", Json.q("run"),
                "case", Json.q(caseDir.toString()),
                "stages", Json.arr(stages),
                "finalOutput", Json.q(r.finalXds),
                "trace", wantTrace ? Json.q(r.fullTrace) : "null"));
            return 0;
        }
        warnDiagnostics(c);
        System.out.println("# stages: " + r.stages.size());
        for (StageSnapshot s : r.stages) {
            System.out.println("  - " + s.stageName + (s.changed() ? " [changed]" : " [no-op]")
                + (s.queries.isEmpty() ? "" : " queries=" + s.queries.size())
                + (s.commands.isEmpty() ? "" : " commands=" + s.commands.size()));
        }
        System.out.println("\n=== final output ===");
        System.out.println(pretty(r.finalXds));
        if (wantTrace) {
            System.out.println("\n=== trace ===");
            System.out.println(r.fullTrace);
        }
        return 0;
    }

    private static int doStep(Path caseDir, boolean perRule, boolean wantJson) {
        Case c = Case.load(caseDir);
        ChannelSimulator.Result r = c.sim.run(c.input, perRule);
        if (wantJson) {
            java.util.List<String> stages = new java.util.ArrayList<>();
            for (StageSnapshot s : r.stages) {
                stages.add(Json.obj(
                    "name", Json.q(s.stageName),
                    "changed", bool(s.changed()),
                    "error", Json.q(s.error),
                    "input", Json.q(s.inputXds),
                    "output", Json.q(s.outputXds),
                    "queries", Json.strArr(s.queries),
                    "commands", Json.strArr(s.commands),
                    "trace", Json.q(s.trace)));
            }
            System.out.println(Json.obj(
                "command", Json.q("step"),
                "case", Json.q(caseDir.toString()),
                "stages", Json.arr(stages)));
            return 0;
        }
        warnDiagnostics(c);
        for (StageSnapshot s : r.stages) {
            System.out.println("============================================================");
            System.out.println("STAGE: " + s.stageName
                + (s.error != null ? "  [ERROR]" : s.changed() ? "  [changed]" : "  [no-op]"));
            System.out.println("------------------------------------------------------------");
            if (s.error != null) {
                System.out.println("ERROR: " + s.error);
            }
            System.out.println("INPUT:\n" + pretty(s.inputXds));
            System.out.println("OUTPUT:\n" + pretty(s.outputXds));
            if (!s.queries.isEmpty()) {
                System.out.println("QUERIES (" + s.queries.size() + "):");
                for (String q : s.queries) {
                    System.out.println("  " + pretty(q).replace("\n", "\n  "));
                }
            }
            if (!s.commands.isEmpty()) {
                System.out.println("COMMANDS (" + s.commands.size() + "):");
                for (String cmd : s.commands) {
                    System.out.println("  " + pretty(cmd).replace("\n", "\n  "));
                }
            }
            if (!s.trace.isBlank()) {
                System.out.println("TRACE:\n" + s.trace.strip());
            }
        }
        return 0;
    }

    private static int doTest(Path caseDir, boolean wantJson) {
        Case c = Case.load(caseDir);
        ChannelSimulator.Result r = c.run();
        if (wantJson) {
            return doTestJson(caseDir, c, r);
        }
        warnDiagnostics(c);
        boolean ok = true;

        if (Files.exists(c.expectedOutput)) {
            XmlCompare.Diff d = XmlCompare.compare(read(c.expectedOutput), r.finalXds);
            System.out.println("output: " + (d.equal ? "PASS" : "FAIL"));
            if (!d.equal) {
                ok = false;
                System.out.println(d.message);
            }
        } else {
            System.out.println("output: SKIP (no expected-output.xds; use 'record')");
        }

        if (Files.exists(c.expectedDirectory)) {
            XmlCompare.Diff d = XmlCompare.compare(read(c.expectedDirectory), c.directory.dumpState());
            System.out.println("directory: " + (d.equal ? "PASS" : "FAIL"));
            if (!d.equal) {
                ok = false;
                System.out.println(d.message);
            }
        }

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        return ok ? 0 : 1;
    }

    /** Structured form of {@code test}: same checks, emitted as one JSON object. */
    private static int doTestJson(Path caseDir, Case c, ChannelSimulator.Result r) {
        boolean ok = true;
        String outBlock = Json.obj("checked", "false");
        if (Files.exists(c.expectedOutput)) {
            XmlCompare.Diff d = XmlCompare.compare(read(c.expectedOutput), r.finalXds);
            ok = d.equal;
            outBlock = Json.obj("checked", "true", "equal", bool(d.equal),
                "diff", d.equal ? "null" : Json.q(d.message));
        }
        String dirBlock = "null";
        if (Files.exists(c.expectedDirectory)) {
            XmlCompare.Diff d = XmlCompare.compare(read(c.expectedDirectory), c.directory.dumpState());
            ok = ok && d.equal;
            dirBlock = Json.obj("checked", "true", "equal", bool(d.equal),
                "diff", d.equal ? "null" : Json.q(d.message));
        }
        System.out.println(Json.obj(
            "command", Json.q("test"),
            "case", Json.q(caseDir.toString()),
            "result", Json.q(ok ? "PASS" : "FAIL"),
            "output", outBlock,
            "directory", dirBlock));
        return ok ? 0 : 1;
    }

    /**
     * Run every case under {@code dir} as a golden test (the batch counterpart to
     * {@code test}); print a per-case summary, optionally write JUnit / JSON
     * reports, and exit non-zero if any case FAILed or ERRORed.
     */
    private static int doTestAll(Path dir, String junitFile, String jsonFile) throws Exception {
        if (!Files.isDirectory(dir)) {
            System.err.println("ERROR: not a directory: " + dir);
            return 2;
        }
        var results = BatchRunner.runAll(dir);
        if (results.isEmpty()) {
            System.out.println("no cases found under " + dir + " (a case is a dir with input.xds)");
            return 0;
        }
        System.out.println(BatchRunner.summary(results));
        if (junitFile != null) {
            write(Paths.get(junitFile), BatchRunner.toJunit(results, "dirxml-sim:" + dir));
            System.out.println("wrote " + junitFile);
        }
        if (jsonFile != null) {
            write(Paths.get(jsonFile), BatchRunner.toJson(results));
            System.out.println("wrote " + jsonFile);
        }
        return BatchRunner.anyFailed(results) ? 1 : 0;
    }

    /**
     * Replay real Event Logger DB events through the current policies and snapshot
     * each produced output as a golden — minting a regression corpus under
     * {@code outDir} that {@code test-all} can run. Source + filters + config come
     * from {@code <configCaseDir>/case.properties}.
     */
    private static int doHarvest(Path configCaseDir, Path outDir, boolean refresh) throws Exception {
        Harvester.Result r = Harvester.harvest(configCaseDir, outDir, refresh);
        if (r.events == 0) {
            System.out.println("no matching events — nothing harvested");
            return 0;
        }
        System.out.printf("harvested %d event(s) -> %d case(s) under %s%s%n",
            r.events, r.cases, r.outDir,
            r.flagged > 0 ? "   (" + r.flagged + " query-light — see HARVEST.md)" : "");
        System.out.println("run them:  bin/sim test-all " + r.outDir);
        return 0;
    }

    /** Config-source keys; a case uses exactly one (the one {@code compare} swaps). */
    private static final String[] CONFIG_SOURCE_KEYS = {"export", "project", "ldifConfig", "ldapConfig"};

    /**
     * Run the same case through two policy sets and report where they diverge: the
     * case as-is (A) vs the same input/data with its config source replaced by
     * {@code --against} (B). Exit 1 if the final output differs.
     */
    private static int doCompare(Path caseDir, String against, boolean wantJson) throws Exception {
        if (against == null || against.isBlank()) {
            System.err.println("usage: compare <caseDir> --against <export|project|ldif|driverSetDN> [--json]");
            return 2;
        }
        java.util.Properties a = new java.util.Properties();
        Path pf = caseDir.resolve("case.properties");
        if (Files.exists(pf)) {
            try (var in = Files.newInputStream(pf)) {
                a.load(in);
            }
        }
        String srcKey = null;
        for (String k : CONFIG_SOURCE_KEYS) {
            if (a.getProperty(k) != null && !a.getProperty(k).isBlank()) {
                srcKey = k;
                break;
            }
        }
        if (srcKey == null) {
            System.err.println("ERROR: compare needs a driver config source (export=/project=/"
                + "ldifConfig=/ldapConfig=) in " + pf + "; chain.txt cases aren't comparable");
            return 2;
        }
        // ldapConfig is a DN (verbatim); the others are a path → absolutize from CWD.
        String bValue = "ldapConfig".equals(srcKey)
            ? against.trim() : Paths.get(against.trim()).toAbsolutePath().normalize().toString();

        Path tmp = Files.createTempDirectory("sim-compare-");
        try {
            for (String f : new String[]{"input.xds", "directory.xds", "gcv.xml", "rest-response.json"}) {
                Path src = caseDir.resolve(f);
                if (Files.exists(src)) {
                    Files.copy(src, tmp.resolve(f));
                }
            }
            Files.writeString(tmp.resolve("case.properties"), CaseProps.render(
                caseDir, a, java.util.Set.of(), java.util.Map.of(srcKey, bValue),
                "generated by `sim compare` — config swapped to " + against));

            ChannelSimulator.Result ra = Case.load(caseDir).run();
            ChannelSimulator.Result rb = Case.load(tmp).run();
            Comparer.Comparison cmp = Comparer.diff(ra, rb);

            if (wantJson) {
                printCompareJson(caseDir, srcKey, a.getProperty(srcKey), against, cmp);
            } else {
                printCompareHuman(caseDir, srcKey, a.getProperty(srcKey), against, cmp);
            }
            return cmp.finalSame ? 0 : 1;
        } finally {
            deleteRecursive(tmp);
        }
    }

    private static void printCompareHuman(Path caseDir, String srcKey, String aVal, String bVal,
                                          Comparer.Comparison cmp) {
        System.out.println("compare " + caseDir);
        System.out.println("  A: " + srcKey + "=" + aVal);
        System.out.println("  B: " + srcKey + "=" + bVal);
        System.out.println("  " + "─".repeat(40));
        int diff = 0;
        for (Comparer.StageDiff s : cmp.stages) {
            boolean changed = !s.same;
            if (changed) {
                diff++;
            }
            System.out.printf("  %-22s %s%s%n", s.name, changed ? "DIFFERS" : "same",
                changed && !s.detail.isBlank() ? "   " + s.detail.split("\n", 2)[0] : "");
        }
        System.out.println("  " + "─".repeat(40));
        System.out.println("  final output: " + (cmp.finalSame ? "IDENTICAL"
            : "DIFFERS (first diverges at '" + cmp.firstDivergesAt + "')"));
        System.out.printf("  %d stages: %d identical, %d differ%n",
            cmp.stages.size(), cmp.stages.size() - diff, diff);
    }

    private static void printCompareJson(Path caseDir, String srcKey, String aVal, String bVal,
                                         Comparer.Comparison cmp) {
        java.util.List<String> stages = new java.util.ArrayList<>();
        for (Comparer.StageDiff s : cmp.stages) {
            stages.add(Json.obj("name", Json.q(s.name), "presentIn", Json.q(s.presentIn),
                "same", bool(s.same), "diff", s.detail.isBlank() ? "null" : Json.q(s.detail)));
        }
        System.out.println(Json.obj(
            "command", Json.q("compare"),
            "case", Json.q(caseDir.toString()),
            "configKey", Json.q(srcKey),
            "a", Json.q(aVal),
            "b", Json.q(bVal),
            "finalSame", bool(cmp.finalSame),
            "firstDivergesAt", Json.q(cmp.firstDivergesAt),
            "finalDiff", cmp.finalDetail.isBlank() ? "null" : Json.q(cmp.finalDetail),
            "stages", Json.arr(stages)));
    }

    private static void deleteRecursive(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (java.io.IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        }
    }

    private static int doRecord(Path caseDir) {
        Case c = Case.load(caseDir);
        ChannelSimulator.Result r = c.run();
        write(c.expectedOutput, pretty(r.finalXds));
        System.out.println("wrote " + c.expectedOutput);
        if (c.directory.size() > 0) {
            write(c.expectedDirectory, pretty(c.directory.dumpState()));
            System.out.println("wrote " + c.expectedDirectory);
        }
        return 0;
    }

    /**
     * Read a stopped driver's event cache (queued subscriber events) from a live
     * server via DxCMD's LDAP extended ops, and write them into the case as
     * {@code cache.xds} (raw) and {@code input.xds} (if absent). Connection comes
     * from the case's {@code ldap=}/{@code ldapBindDn}/{@code ldapBindPassword}
     * keys plus {@code cacheDriver=<driverDN>}.
     */
    private static int doDxCache(Path caseDir) throws Exception {
        java.util.Properties p = new java.util.Properties();
        Path pf = caseDir.resolve("case.properties");
        if (Files.exists(pf)) {
            try (var in = Files.newInputStream(pf)) {
                p.load(in);
            }
        }
        String url = p.getProperty("ldap");
        String driver = p.getProperty("cacheDriver", p.getProperty("driverDN"));
        if (url == null || url.isBlank() || driver == null || driver.isBlank()) {
            System.err.println("dxcache needs ldap=<url> and cacheDriver=<driverDN> in case.properties");
            return 2;
        }
        DxCacheReader.Config c = new DxCacheReader.Config();
        String lower = url.trim().toLowerCase();
        c.ssl = lower.startsWith("ldaps");
        String hostPort = url.trim().replaceFirst("(?i)^ldaps?://", "").replaceAll("/.*$", "");
        int colon = hostPort.indexOf(':');
        if (colon >= 0) {
            c.host = hostPort.substring(0, colon);
            c.port = Integer.parseInt(hostPort.substring(colon + 1));
        } else {
            c.host = hostPort;
            c.port = c.ssl ? 636 : 389;
        }
        c.bindDn = p.getProperty("ldapBindDn");
        c.password = p.getProperty("ldapBindPassword");
        c.trustAllCerts = Boolean.parseBoolean(p.getProperty("ldapTrustAll", "true"));

        int count = Integer.parseInt(p.getProperty("cacheCount", "100"));
        int token = Integer.parseInt(p.getProperty("cacheToken", "0"));
        DxCacheReader.Result r = new DxCacheReader(c).readCache(driver.trim(), token, count);

        if (!r.readable) {
            System.out.println("driver is " + r.stateName() + " (state " + r.driverState
                + ") — stop it to read its event cache: " + driver);
            return 0;
        }
        if (r.empty) {
            System.out.println("driver cache is EMPTY for " + driver);
            return 0;
        }
        Path cacheFile = caseDir.resolve("cache.xds");
        Files.writeString(cacheFile, r.xds);
        int ops = countOps(r.xds);
        System.out.println("wrote " + cacheFile + "  (" + ops + " cached events, "
            + r.xds.length() + " bytes; nextToken=" + r.nextToken + ")");
        Path input = caseDir.resolve("input.xds");
        if (!Files.exists(input)) {
            Files.writeString(input, r.xds);
            System.out.println("wrote input.xds (the cached events as one <input> batch)");
        } else {
            System.out.println("input.xds already exists; left unchanged (use cache.xds)");
        }
        return 0;
    }

    private static int countOps(String xds) {
        return (xds.split("<modify", -1).length - 1)
            + (xds.split("<add ", -1).length - 1)
            + (xds.split("<delete", -1).length - 1)
            + (xds.split("<rename", -1).length - 1)
            + (xds.split("<move", -1).length - 1);
    }

    /**
     * Query the DirXML Event Logger DB and write each matched event as its own
     * pickable sample under {@code <caseDir>/events/} (a distinct transaction each —
     * never coalesced), with a listing so you can choose which to run as
     * {@code input.xds}. Connection + filters come from {@code case.properties}.
     */
    private static int doDbEvents(Path caseDir) throws Exception {
        java.util.Properties p = new java.util.Properties();
        Path pf = caseDir.resolve("case.properties");
        if (Files.exists(pf)) {
            try (var in = Files.newInputStream(pf)) {
                p.load(in);
            }
        }
        String url = p.getProperty("db");
        if (url == null || url.isBlank()) {
            System.err.println("dbevents needs db=jdbc:postgresql://host:port/db in case.properties");
            return 2;
        }
        DbEventReader.Config c = new DbEventReader.Config();
        c.url = url.trim();
        c.user = p.getProperty("dbUser");
        c.password = p.getProperty("dbPassword");
        if (p.getProperty("dbTable") != null && !p.getProperty("dbTable").isBlank()) {
            c.table = p.getProperty("dbTable").trim();
        }

        DbEventReader.Query q = new DbEventReader.Query();
        q.srcDn = p.getProperty("eventsForDn");
        q.srcDnLike = p.getProperty("eventsDnLike");
        q.driver = p.getProperty("eventsDriver");
        q.eventType = p.getProperty("eventType");
        q.className = p.getProperty("eventClass");
        q.since = p.getProperty("eventsSince");
        q.until = p.getProperty("eventsUntil");
        q.rawWhere = p.getProperty("eventsWhere");
        q.limit = Integer.parseInt(p.getProperty("eventLimit", "50"));
        q.newestFirst = !"asc".equalsIgnoreCase(p.getProperty("eventOrder", "desc"));

        java.util.List<DbEventReader.Event> events = new DbEventReader(c).query(q);
        if (events.isEmpty()) {
            System.out.println("no matching events in " + c.table);
            return 0;
        }
        Path dir = caseDir.resolve("events");
        Files.createDirectories(dir);
        System.out.println(events.size() + " event(s) — each a distinct transaction; "
            + "pick one as input.xds:");
        int i = 0;
        for (DbEventReader.Event e : events) {
            i++;
            String name = String.format("%03d-%s-%s.xds", i, safeName(e.eventType), safeName(rdnOf(e.srcDn)));
            Files.write(dir.resolve(name), e.xds.getBytes("UTF-8"));
            System.out.printf("  [%3d] %-7s %-14s %-42s %s%n      -> events/%s%n",
                i, str(e.eventType), str(e.className), str(e.srcDn), str(e.cachedTime), name);
        }
        System.out.println("then e.g.:  cp " + caseDir.resolve("events").resolve("001-…")
            + "  " + caseDir.resolve("input.xds"));
        return 0;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String safeName(String s) {
        if (s == null || s.isBlank()) {
            return "x";
        }
        String out = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return out.length() > 40 ? out.substring(out.length() - 40) : out;
    }

    /** Last RDN value of a DN (slash- or comma-delimited) for a filename. */
    private static String rdnOf(String dn) {
        if (dn == null || dn.isBlank()) {
            return "x";
        }
        String last = dn.replace('/', '\\');
        last = last.substring(Math.max(last.lastIndexOf('\\'), last.lastIndexOf(',')) + 1);
        int eq = last.indexOf('=');
        return eq >= 0 ? last.substring(eq + 1) : last;
    }

    /** Self-check: JDK, engine jars, and a smoke run of the bundled sample case. */
    private static int doDoctor() {
        boolean ok = true;
        System.out.println("DirXML Policy Simulator — doctor");

        String ver = System.getProperty("java.version", "?");
        boolean is21 = ver.startsWith("21");
        System.out.println("  java.version: " + ver + (is21 ? "  OK" : "  WARN (need JDK 21)"));
        ok &= is21;

        String[] engineClasses = {
            "com.novell.nds.dirxml.engine.rules.DirXMLScriptProcessor",
            "com.novell.nds.dirxml.engine.gcv.GCDefinitions",
            "com.novell.nds.dirxml.driver.XmlDocument",
            "com.novell.xml.dom.DocumentImpl",
            "novell.jclient.JCContext",
        };
        boolean jars = true;
        for (String c : engineClasses) {
            try {
                // initialize=false: JCContext's static init loads a native lib we
                // never actually touch (it's only ever a null parameter type).
                Class.forName(c, false, Cli.class.getClassLoader());
            } catch (Throwable t) {
                jars = false;
                System.out.println("  MISSING class " + c + " — check lib/ jars");
            }
        }
        System.out.println("  engine jars: " + (jars ? "OK" : "INCOMPLETE"));
        ok &= jars;

        if (jars) {
            try {
                EngineContext ctx = EngineContext.create("\\x\\y\\z");
                new ChannelSimulator(ctx, new FakeDirectory())
                    .add(PolicyStage.fromElement("smoke",
                        PolicyLoader.load("<policy><rule><description>x</description>"
                            + "<conditions/><actions/></rule></policy>"), ctx))
                    .run("<nds dtdversion='4.0'><input><add class-name='User'/></input></nds>");
                System.out.println("  engine smoke run: OK");
            } catch (Throwable t) {
                ok = false;
                System.out.println("  engine smoke run: FAIL — " + t);
            }
        }

        Path sample = Paths.get("cases/copy-surname");
        if (Files.isDirectory(sample)) {
            int rc = doTest(sample, false);
            System.out.println("  sample case cases/copy-surname: " + (rc == 0 ? "PASS" : "FAIL"));
            ok &= rc == 0;
        }

        System.out.println(ok ? "DOCTOR: OK" : "DOCTOR: PROBLEMS FOUND");
        return ok ? 0 : 1;
    }

    // ---- helpers -------------------------------------------------------------

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (a.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    /** JSON boolean literal. */
    private static String bool(boolean b) {
        return b ? "true" : "false";
    }

    /** Value of a {@code --flag <value>} option, or null if absent. */
    private static String flagValue(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return null;
    }

    /** Light pretty-print: newline between adjacent tags. Good enough for reading. */
    private static String pretty(String xml) {
        return xml.replaceAll(">\\s*<", ">\n<");
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void write(Path p, String content) {
        try {
            Files.write(p, content.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void usage() {
        System.err.println("usage:");
        System.err.println("  run    <caseDir> [--trace] [--json]   run chain, print final output (+ trace)");
        System.err.println("  step   <caseDir> [--rules] [--json]   per-stage (or per-rule) input/output/queries/trace");
        System.err.println("  test   <caseDir> [--json]    diff vs expected-*.xds; exit !=0 on mismatch");
        System.err.println("  test-all <dir> [--junit f] [--json f]  run every case; CI summary + exit code");
        System.err.println("  compare <caseDir> --against <cfg> [--json]  two policy sets, same input; divergence");
        System.err.println("  record <caseDir>             write goldens");
        System.err.println("  extract <traceFile> <outDir> mine a DSTrace log into a case");
        System.err.println("  dxcache <caseDir>            read a driver's event cache (live) into the case");
        System.err.println("  dbevents <caseDir>           list/pick logged events from the Event Logger DB");
        System.err.println("  harvest <configDir> <outDir> [--refresh]  mint a regression corpus from real events");
        System.err.println("  doctor                       setup self-check");
    }
}
