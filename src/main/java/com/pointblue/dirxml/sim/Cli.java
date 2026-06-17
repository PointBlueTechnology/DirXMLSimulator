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
        java.util.List<String> unsupported = c.sim.unsupportedFeatures();
        if (!unsupported.isEmpty()) {
            System.out.println("WARNING: this policy uses IDM subsystems the harness does not provide — "
                + "results involving them are not authoritative:");
            for (String u : unsupported) {
                System.out.println("  - " + u);
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
        System.err.println("  doctor                       setup self-check");
    }
}
