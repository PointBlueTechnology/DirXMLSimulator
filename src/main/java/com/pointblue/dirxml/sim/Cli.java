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
 *   record &lt;caseDir&gt;            run and write expected-output.xds / expected-directory.xds
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
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        String cmd = args[0];
        Path caseDir = Paths.get(args[1]);
        boolean wantTrace = hasFlag(args, "--trace");

        try {
            switch (cmd) {
                case "run":
                    System.exit(doRun(caseDir, wantTrace));
                    break;
                case "step":
                    System.exit(doStep(caseDir, hasFlag(args, "--rules")));
                    break;
                case "test":
                    System.exit(doTest(caseDir));
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

    private static int doRun(Path caseDir, boolean wantTrace) {
        Case c = Case.load(caseDir);
        warnDiagnostics(c);
        ChannelSimulator.Result r = c.run();
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

    private static int doStep(Path caseDir, boolean perRule) {
        Case c = Case.load(caseDir);
        warnDiagnostics(c);
        ChannelSimulator.Result r = c.sim.run(c.input, perRule);
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

    private static int doTest(Path caseDir) {
        Case c = Case.load(caseDir);
        warnDiagnostics(c);
        ChannelSimulator.Result r = c.run();
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
            int rc = doTest(sample);
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
        System.err.println("  run    <caseDir> [--trace]   run chain, print final output (+ trace)");
        System.err.println("  step   <caseDir> [--rules]   per-stage (or per-rule) input/output/queries/trace");
        System.err.println("  test   <caseDir>             diff vs expected-*.xds; exit !=0 on mismatch");
        System.err.println("  record <caseDir>             write goldens");
        System.err.println("  extract <traceFile> <outDir> mine a DSTrace log into a case");
        System.err.println("  dxcache <caseDir>            read a driver's event cache (live) into the case");
        System.err.println("  dbevents <caseDir>           list/pick logged events from the Event Logger DB");
        System.err.println("  doctor                       setup self-check");
    }
}
