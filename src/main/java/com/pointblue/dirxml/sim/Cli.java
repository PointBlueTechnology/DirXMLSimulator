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
                    System.exit(doStep(caseDir));
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

    private static int doRun(Path caseDir, boolean wantTrace) {
        Case c = Case.load(caseDir);
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

    private static int doStep(Path caseDir) {
        Case c = Case.load(caseDir);
        ChannelSimulator.Result r = c.run();
        for (StageSnapshot s : r.stages) {
            System.out.println("============================================================");
            System.out.println("STAGE: " + s.stageName + (s.changed() ? "  [changed]" : "  [no-op]"));
            System.out.println("------------------------------------------------------------");
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
        System.err.println("usage: <run|step|test|record> <caseDir> [--trace]");
    }
}
