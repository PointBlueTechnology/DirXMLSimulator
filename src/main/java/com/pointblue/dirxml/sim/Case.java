package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.gcv.GCDefinitions;

import org.w3c.dom.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A test case on disk. Layout:
 *
 * <pre>
 *   cases/&lt;name&gt;/
 *     case.properties        # optional: driverDN, dnFormat, fromNDS, traceLevel
 *     chain.txt              # ordered stages: "stageName = relative/policy.xml" per line
 *     input.xds              # the operation to run
 *     directory.xds          # optional: initial fake-directory state
 *     gcv.xml                # optional: GCV definitions
 *     expected-output.xds    # optional golden: final document
 *     expected-directory.xds # optional golden: fake-directory end state
 * </pre>
 */
public final class Case {

    public final Path dir;
    public final EngineContext ctx;
    public final FakeDirectory directory;
    public final ChannelSimulator sim;
    public final Document input;
    public final Path expectedOutput;     // may not exist
    public final Path expectedDirectory;  // may not exist

    private Case(Path dir, EngineContext ctx, FakeDirectory directory, ChannelSimulator sim,
                 Document input, Path expectedOutput, Path expectedDirectory) {
        this.dir = dir;
        this.ctx = ctx;
        this.directory = directory;
        this.sim = sim;
        this.input = input;
        this.expectedOutput = expectedOutput;
        this.expectedDirectory = expectedDirectory;
    }

    public static Case load(Path caseDir) {
        try {
            Properties p = new Properties();
            Path propFile = caseDir.resolve("case.properties");
            if (Files.exists(propFile)) {
                try (var in = Files.newInputStream(propFile)) {
                    p.load(in);
                }
            }
            String driverDN = p.getProperty("driverDN", "\\SIM\\system\\DriverSet\\Driver");
            String dnFormat = p.getProperty("dnFormat", "slash");
            boolean fromNDS = Boolean.parseBoolean(p.getProperty("fromNDS", "true"));
            int traceLevel = Integer.parseInt(p.getProperty("traceLevel", "4"));

            GCDefinitions gcv = new GCDefinitions();
            Path gcvFile = caseDir.resolve("gcv.xml");
            if (Files.exists(gcvFile)) {
                try {
                    gcv = GCDefinitions.construct(Xds.parseFile(gcvFile));
                } catch (Throwable t) {
                    System.err.println("warning: could not parse gcv.xml, using empty GCVs: " + t);
                }
            }

            EngineContext ctx = EngineContext.create(driverDN, dnFormat, fromNDS, gcv);
            ctx.setTraceLevel(traceLevel);

            FakeDirectory directory = new FakeDirectory();
            Path dirFile = caseDir.resolve("directory.xds");
            if (Files.exists(dirFile)) {
                directory.loadStateFile(dirFile);
            }

            ChannelSimulator sim = new ChannelSimulator(ctx, directory);
            for (Stage s : readChain(caseDir)) {
                sim.add(PolicyStage.fromFile(s.name, caseDir.resolve(s.policyPath), ctx));
            }

            Document input = Xds.parseFile(caseDir.resolve("input.xds"));

            Path expOut = caseDir.resolve("expected-output.xds");
            Path expDir = caseDir.resolve("expected-directory.xds");
            return new Case(caseDir, ctx, directory, sim, input, expOut, expDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load case " + caseDir + ": " + e, e);
        }
    }

    public ChannelSimulator.Result run() {
        return sim.run(input);
    }

    // ---- chain.txt parsing ---------------------------------------------------

    private static final class Stage {
        final String name;
        final String policyPath;
        Stage(String name, String policyPath) {
            this.name = name;
            this.policyPath = policyPath;
        }
    }

    private static List<Stage> readChain(Path caseDir) throws Exception {
        Path chain = caseDir.resolve("chain.txt");
        if (!Files.exists(chain)) {
            throw new IllegalArgumentException("missing chain.txt in " + caseDir);
        }
        List<Stage> out = new ArrayList<>();
        int n = 0;
        for (String raw : Files.readAllLines(chain)) {
            n++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                // bare path -> derive stage name from filename
                out.add(new Stage("stage" + n, line));
            } else {
                out.add(new Stage(line.substring(0, eq).trim(), line.substring(eq + 1).trim()));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("chain.txt has no stages in " + caseDir);
        }
        return out;
    }
}
