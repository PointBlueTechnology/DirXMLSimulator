package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.XdsQueryProcessor;
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
            // Third config source: an LDIF/LDAP export of the live Identity Vault —
            // an LDIF file (ldifConfig=) or read live from LDAP (ldapConfig=<DriverSetDN>).
            String ldifConfigRef = p.getProperty("ldifConfig");
            String ldapConfigDn = p.getProperty("ldapConfig");
            LdifDriverSource ldifConfig = null;
            if (export == null && project == null && ldifConfigRef != null && !ldifConfigRef.isBlank()) {
                ldifConfig = LdifDriverSource.load(caseDir.resolve(ldifConfigRef.trim()));
            } else if (export == null && project == null && ldapConfigDn != null && !ldapConfigDn.isBlank()) {
                JndiLdapSearch.Config lc = ldapConfig(p, null);  // literal ldapBindPassword
                if (lc == null) {
                    throw new IllegalArgumentException("ldapConfig= requires ldap=<url>");
                }
                ldifConfig = new JndiLdapSearch(lc, SchemaModel.empty()).readDriverConfig(ldapConfigDn.trim());
            }
            if (ldifConfig != null) {
                if (projectDriver == null || projectDriver.isBlank()) {
                    throw new IllegalArgumentException(
                        "ldifConfig=/ldapConfig= requires driver=<name>; drivers: " + ldifConfig.driverNames());
                }
                gcv = ldifConfig.gcvDefinitions(projectDriver);
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
            if (ldifConfig != null) {
                ecma.addAll(ldifConfig.ecmaScriptSources());
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
            } else if (ldifConfig != null) {
                if (wantFilter && ldifConfig.filter(projectDriver) != null) {
                    sim.add(PolicyStage.filter("filter", ldifConfig.filter(projectDriver), publisher));
                }
                sim.addAll(publisher
                    ? ldifConfig.publisherChain(projectDriver, ctx)
                    : ldifConfig.subscriberChain(projectDriver, ctx));
            } else {
                for (Stage s : readChain(caseDir)) {
                    sim.add(PolicyStage.fromFile(s.name, caseDir.resolve(s.policyPath), ctx));
                }
            }

            Document input = Xds.parseFile(caseDir.resolve("input.xds"));

            // Schema validates inputs. Sources: an explicit schema=<file|dir>, the
            // Designer project, or — with a live connection — read directly from
            // LDAP (schema=ldap, or auto when ldap= is set and nothing else supplies one).
            SchemaModel schema = SchemaModel.empty();
            String schemaRef = p.getProperty("schema");
            boolean wantLdapSchema = "ldap".equalsIgnoreCase(schemaRef == null ? "" : schemaRef.trim());
            if (schemaRef != null && !schemaRef.isBlank() && !wantLdapSchema) {
                Path sp = caseDir.resolve(schemaRef.trim());
                schema = Files.isDirectory(sp) ? DesignerProject.load(sp).schema()
                    : SchemaModel.parseFile(sp);
            } else if (project != null) {
                schema = project.schema();
            }
            JndiLdapSearch.Config ldapCfg = ldapConfig(p, directory);
            if (schema.isEmpty() && (wantLdapSchema || ldapCfg != null)) {
                if (ldapCfg == null) {
                    throw new IllegalArgumentException("schema=ldap requires ldap=<url>");
                }
                try {
                    schema = new JndiLdapSearch(ldapCfg, SchemaModel.empty()).readSchema();
                    System.out.println("note: schema read from LDAP " + ldapCfg.url);
                } catch (Exception e) {
                    System.err.println("warning: could not read schema from LDAP: " + e.getMessage());
                }
            }
            List<String> schemaWarnings = new ArrayList<>(schema.validate(input));
            if (Files.exists(dirFile)) {
                schemaWarnings.addAll(schema.validate(Xds.parseFile(dirFile)));
            }

            // Optional: seed the fake directory from an LDIF dump (ldapsearch/ICE
            // export), mapped to native XDS via the schema + value normalizer.
            String ldifRef = p.getProperty("ldif");
            if (ldifRef != null && !ldifRef.isBlank()) {
                new LdifReader(schema, new LdapValueNormalizer(p.getProperty("ldapDnTree")), driverDN)
                    .seed(directory, caseDir.resolve(ldifRef.trim()));
            }

            // Optional, opt-in: a live-LDAP query source and/or a real shim command
            // sink. Absent keys ⇒ the chain runs against FakeDirectory, as before.
            wireShimAndLdap(p, caseDir, export, project, ldifConfig, projectDriver, directory, schema, sim);

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

    /**
     * Wire the optional live-LDAP query source and/or real-shim command sink from
     * {@code case.properties}. Both are independent and opt-in; with neither set
     * this is a no-op and the chain runs against the {@link FakeDirectory}.
     *
     * <pre>
     *   ldap=ldaps://host:636        # answer queries from live eDir (else directory.xds)
     *   ldapBindDn=cn=admin,o=system
     *   ldapBindPassword.named=ldap-bind   # or ldapBindPassword=<literal>
     *   ldapSearchBase=o=data
     *   ldapTrustAll=true            # ldaps with an internal CA
     *   ldapAssocPrefix=...          # prefix on the DirXML-Associations filter
     *   ldapDnTree=ACME-TREE         # tree name for slash-form DN values
     *
     *   shim=true                    # drive the real connector as command sink
     *   shimClass=...                # defaults to the export/project java-module
     *   shimJar=lib/RestShim.jar     # extra jar(s), comma-separated; gitignored
     *   shimInit=shim-init.xml       # explicit init-params, else synthesized from the source
     *   shimAuthPassword.named=app   # or shimAuthPassword=<literal>
     * </pre>
     */
    private static void wireShimAndLdap(Properties p, Path caseDir, DriverExport export,
            DesignerProject project, LdifDriverSource ldifConfig, String projectDriver,
            FakeDirectory directory, SchemaModel schema, ChannelSimulator sim) {
        // --- live-LDAP query source (optional) ---
        LdapQueryProcessor ldapQp = null;
        JndiLdapSearch.Config lc = ldapConfig(p, directory);
        if (lc != null) {
            ldapQp = new LdapQueryProcessor(
                new JndiLdapSearch(lc, schema), schema,
                new LdapValueNormalizer(p.getProperty("ldapDnTree")),
                p.getProperty("ldapSearchBase", ""), p.getProperty("ldapAssocPrefix", ""));
            sim.withQuerySource(ldapQp);
        }

        // --- real shim as command sink (optional) ---
        if (!Boolean.parseBoolean(p.getProperty("shim", "false"))) {
            return;
        }
        ShimConfig cfg = export != null ? export.shimConfig()
            : project != null ? project.shimConfig(projectDriver)
            : ldifConfig != null ? ldifConfig.shimConfig(projectDriver) : null;
        String shimClass = p.getProperty("shimClass", cfg != null ? cfg.shimClass : null);
        if (shimClass == null || shimClass.isBlank()) {
            throw new IllegalArgumentException("shim=true needs a shim class: set shimClass=, "
                + "or use an export/project that names one (java-module / DirXML-JavaModule)");
        }
        String password = resolveSecret(p, directory, "shimAuthPassword");

        Document initDoc;
        String shimInit = p.getProperty("shimInit");
        if (shimInit != null && !shimInit.isBlank()) {
            initDoc = Xds.parseFile(caseDir.resolve(shimInit.trim()));
        } else {
            InitDocBuilder b = new InitDocBuilder();
            if (cfg != null) {
                b.shimConfig(cfg, password);
            } else {
                b.authentication(p.getProperty("shimAuthServer"), p.getProperty("shimAuthId"), password);
            }
            initDoc = b.build();
        }

        List<Path> jars = new ArrayList<>();
        String shimJar = p.getProperty("shimJar");
        if (shimJar != null) {
            for (String j : shimJar.split(",")) {
                if (!j.isBlank()) {
                    jars.add(caseDir.resolve(j.trim()));
                }
            }
        }
        XdsQueryProcessor backChannel = ldapQp != null ? ldapQp : directory;
        sim.withCommandSink(ShimAdapter.create(
            shimClass, ShimAdapter.classLoaderFor(jars), initDoc, backChannel));
    }

    /** Build the LDAP connection config from `ldap*` keys, or null if `ldap=` is absent. */
    private static JndiLdapSearch.Config ldapConfig(Properties p, FakeDirectory directory) {
        String url = p.getProperty("ldap");
        if (url == null || url.isBlank()) {
            return null;
        }
        JndiLdapSearch.Config lc = new JndiLdapSearch.Config();
        lc.url = url.trim();
        lc.bindDn = p.getProperty("ldapBindDn");
        lc.bindPassword = resolveSecret(p, directory, "ldapBindPassword");
        // Default to NOT validating TLS certs: the harness only ever points at test
        // directories, which routinely use self-signed / internal-CA certs. Set
        // ldapTrustAll=false to re-enable validation.
        lc.trustAllCerts = Boolean.parseBoolean(p.getProperty("ldapTrustAll", "true"));
        return lc;
    }

    /** A secret from {@code <key>=<literal>} or {@code <key>.named=<namedPassword>}. */
    private static String resolveSecret(Properties p, FakeDirectory dir, String key) {
        String literal = p.getProperty(key);
        if (literal != null) {
            return literal;
        }
        String named = p.getProperty(key + ".named");
        return (named != null && dir != null) ? dir.getNamedPassword(named) : null;
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
