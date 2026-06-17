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
    public final List<String> schemaWarnings;  // input/directory vs schema (empty if no schema)

    private Case(Path dir, EngineContext ctx, FakeDirectory directory, ChannelSimulator sim,
                 Document input, Path expectedOutput, Path expectedDirectory, List<String> schemaWarnings) {
        this.dir = dir;
        this.ctx = ctx;
        this.directory = directory;
        this.sim = sim;
        this.input = input;
        this.expectedOutput = expectedOutput;
        this.expectedDirectory = expectedDirectory;
        this.schemaWarnings = schemaWarnings;
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

            // Config source: a driver export, or a Designer project + driver name.
            String exportRef = p.getProperty("export");
            DriverExport export = null;
            GCDefinitions gcv = new GCDefinitions();
            if (exportRef != null && !exportRef.isBlank()) {
                export = DriverExport.load(caseDir.resolve(exportRef.trim()));
                gcv = export.gcvDefinitions();
            }
            String projectRef = p.getProperty("project");
            String projectDriver = p.getProperty("driver");
            DesignerProject project = null;
            if (export == null && projectRef != null && !projectRef.isBlank()) {
                project = DesignerProject.load(caseDir.resolve(projectRef.trim()));
                if (projectDriver == null || projectDriver.isBlank()) {
                    throw new IllegalArgumentException(
                        "project= requires driver=<name>; drivers in project: " + project.driverNames());
                }
                gcv = project.gcvDefinitions(projectDriver);
            }
            // A case-local gcv.xml overlays/overrides the export GCVs.
            Path gcvFile = caseDir.resolve("gcv.xml");
            if (Files.exists(gcvFile)) {
                try {
                    // construct(Node) wants the parent of <configuration-values>.
                    gcv.merge(GCDefinitions.construct(
                        (org.w3c.dom.Node) Xds.parseFile(gcvFile).getDocumentElement()));
                } catch (Throwable t) {
                    System.err.println("warning: could not parse gcv.xml: " + t);
                }
            }

            EngineContext ctx = EngineContext.create(driverDN, dnFormat, fromNDS, gcv);
            ctx.setTraceLevel(traceLevel);

            // Faking of external actions (REST/email/RBPM/…). On by default.
            FakeActions.Config fake = new FakeActions.Config();
            fake.enabled = Boolean.parseBoolean(p.getProperty("fakeActions", "true"));
            if (p.getProperty("restResponse") != null) {
                fake.defaultRestResponse = p.getProperty("restResponse");
            }
            Path restFile = caseDir.resolve("rest-response.json");
            if (fake.defaultRestResponse == null && Files.exists(restFile)) {
                fake.defaultRestResponse = new String(Files.readAllBytes(restFile), "UTF-8");
            }
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith("restResponse.")) {
                    fake.restResponsesByUrl.put(key.substring("restResponse.".length()), p.getProperty(key));
                }
            }
            ctx.setFakeConfig(fake);

            // ECMAScript (es:) functions: from the export's resources and/or a
            // case-local ecmascript/*.js directory.
            List<String> ecma = new ArrayList<>();
            if (export != null) {
                ecma.addAll(export.ecmaScriptSources());
            }
            if (project != null) {
                ecma.addAll(project.ecmaScriptSources());
            }
            Path ecmaDir = caseDir.resolve("ecmascript");
            if (Files.isDirectory(ecmaDir)) {
                try (var paths = Files.list(ecmaDir)) {
                    for (Path js : paths.filter(f -> f.toString().endsWith(".js")).sorted().toList()) {
                        ecma.add(new String(Files.readAllBytes(js), "UTF-8"));
                    }
                }
            }
            ctx.enableEcmaScript(ecma);

            FakeDirectory directory = new FakeDirectory();
            Path dirFile = caseDir.resolve("directory.xds");
            if (Files.exists(dirFile)) {
                directory.loadStateFile(dirFile);
            }
            // Named passwords are secret values (kept out of the export); supply them
            // per case as `namedPassword.<name>=<value>`.
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith("namedPassword.")) {
                    directory.setNamedPassword(key.substring("namedPassword.".length()), p.getProperty(key));
                }
            }

            ChannelSimulator sim = new ChannelSimulator(ctx, directory);
            boolean publisher = "publisher".equals(p.getProperty("channel", "subscriber").trim().toLowerCase());
            boolean wantFilter = Boolean.parseBoolean(p.getProperty("filter", "false"));
            if (export != null) {
                if (wantFilter && export.filter() != null) {
                    sim.add(PolicyStage.filter("filter", export.filter(), publisher));
                }
                sim.addAll(publisher ? export.publisherChain(ctx) : export.subscriberChain(ctx));
            } else if (project != null) {
                if (wantFilter && project.filter(projectDriver) != null) {
                    sim.add(PolicyStage.filter("filter", project.filter(projectDriver), publisher));
                }
                sim.addAll(publisher
                    ? project.publisherChain(projectDriver, ctx)
                    : project.subscriberChain(projectDriver, ctx));
            } else {
                for (Stage s : readChain(caseDir)) {
                    sim.add(PolicyStage.fromFile(s.name, caseDir.resolve(s.policyPath), ctx));
                }
            }

            Document input = Xds.parseFile(caseDir.resolve("input.xds"));

            // Schema (from the project, or an explicit schema=<file|dir>) validates inputs.
            SchemaModel schema = SchemaModel.empty();
            String schemaRef = p.getProperty("schema");
            if (schemaRef != null && !schemaRef.isBlank()) {
                Path sp = caseDir.resolve(schemaRef.trim());
                schema = Files.isDirectory(sp) ? DesignerProject.load(sp).schema()
                    : SchemaModel.parseFile(sp);
            } else if (project != null) {
                schema = project.schema();
            }
            List<String> schemaWarnings = new ArrayList<>(schema.validate(input));
            if (Files.exists(dirFile)) {
                schemaWarnings.addAll(schema.validate(Xds.parseFile(dirFile)));
            }

            Path expOut = caseDir.resolve("expected-output.xds");
            Path expDir = caseDir.resolve("expected-directory.xds");
            return new Case(caseDir, ctx, directory, sim, input, expOut, expDir, schemaWarnings);
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
