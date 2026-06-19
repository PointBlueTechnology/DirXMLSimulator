package com.pointblue.dirxml.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Mints a regression corpus from real events: for each selected event, writes a
 * self-contained case (the real event as {@code input.xds}) and runs it through
 * the <em>current</em> policies, snapshotting the produced output as the golden
 * ({@code expected-output.xds}). The result is a directory {@link BatchRunner}
 * can run — a regression baseline built from production traffic in one command.
 *
 * <p>A harvested golden encodes <em>current</em> behavior, not <em>correct</em>
 * behavior: it is a change detector, not a correctness oracle. {@code HARVEST.md}
 * records that, plus the source, filters, and config-source identity.
 *
 * <p>Source for v1 is the Event Logger DB. Each row is its own case — never
 * coalesced (same rule the {@code dbevents} source enforces).
 */
public final class Harvester {

    private Harvester() {
    }

    /** Event-source / filter keys consumed by harvest itself — not propagated to cases. */
    private static final Set<String> SOURCE_KEYS = Set.of(
        "db", "dbUser", "dbPassword", "dbTable",
        "eventType", "eventClass", "eventsForDn", "eventsDnLike", "eventsDriver",
        "eventsSince", "eventsUntil", "eventsWhere", "eventLimit", "eventOrder",
        "cacheDriver");

    /** Config-source keys whose value is a path, rewritten to absolute so cases run anywhere. */
    private static final Set<String> PATH_KEYS = Set.of(
        "export", "project", "ldifConfig", "schema", "ldif");

    /** Case-local support files copied into each generated case when present. */
    private static final String[] COPY_FILES = {"directory.xds", "gcv.xml", "rest-response.json"};

    public static final class Result {
        public final int events;
        public final int cases;
        public final int flagged;   // cases whose policies queried with no seed/LDAP (golden may be partial)
        public final Path outDir;

        Result(int events, int cases, int flagged, Path outDir) {
            this.events = events;
            this.cases = cases;
            this.flagged = flagged;
            this.outDir = outDir;
        }
    }

    /**
     * Harvest from {@code configCaseDir/case.properties} (DB connection + filters +
     * a config source) into {@code outDir}. Refuses to write into a non-empty
     * {@code outDir} unless {@code refresh} is set, so a reviewed baseline is never
     * silently overwritten.
     */
    public static Result harvest(Path configCaseDir, Path outDir, boolean refresh) throws Exception {
        Properties cfg = loadProps(configCaseDir.resolve("case.properties"));
        String url = trimOrNull(cfg.getProperty("db"));
        if (url == null) {
            throw new IllegalArgumentException(
                "harvest needs db=jdbc:postgresql://host:port/db in " + configCaseDir + "/case.properties");
        }
        if (Files.isDirectory(outDir) && hasEntries(outDir) && !refresh) {
            throw new IllegalArgumentException(
                outDir + " already has content; pass --refresh to re-baseline it");
        }

        List<DbEventReader.Event> events = new DbEventReader(dbConfig(cfg, url)).query(dbQuery(cfg));
        Files.createDirectories(outDir);

        String caseProps = propagatedProps(configCaseDir, cfg);
        boolean haveSeed = Files.exists(configCaseDir.resolve("directory.xds"))
            || trimOrNull(cfg.getProperty("ldap")) != null;

        List<String> log = new ArrayList<>();
        int i = 0, flagged = 0;
        for (DbEventReader.Event e : events) {
            i++;
            String caseName = String.format("%04d-%s-%s",
                i, safe(e.eventType), safe(rdn(e.srcDn)));
            Path caseDir = outDir.resolve(caseName);
            Files.createDirectories(caseDir);

            Files.writeString(caseDir.resolve("case.properties"), caseProps);
            Files.writeString(caseDir.resolve("input.xds"), e.xds);
            for (String f : COPY_FILES) {
                Path src = configCaseDir.resolve(f);
                if (Files.exists(src)) {
                    Files.copy(src, caseDir.resolve(f));
                }
            }

            // Run the current chain and capture the produced output as the golden.
            Case c = Case.load(caseDir);
            ChannelSimulator.Result r = c.run();
            Files.writeString(caseDir.resolve("expected-output.xds"), pretty(r.finalXds));

            int queries = r.stages.stream().mapToInt(s -> s.queries.size()).sum();
            boolean queryLight = queries > 0 && !haveSeed;
            if (queryLight) {
                flagged++;
            }
            log.add(String.format("| %s | %s | %s | %s | %d | %s |",
                caseName, str(e.eventType), str(e.srcDn), str(e.cachedTime), queries,
                queryLight ? "**query-light**" : "ok"));
        }

        writeManifest(outDir, configCaseDir, cfg, url, events.size(), flagged, log);
        return new Result(events.size(), i, flagged, outDir);
    }

    // ---- case.properties propagation ----

    /**
     * The {@code case.properties} written into every harvested case: the config
     * source + channel + connection keys from the harvest config, minus the
     * event-source/filter keys, with path-valued config keys rewritten to absolute
     * so the cases run from anywhere (under {@code test-all} or CI).
     */
    static String propagatedProps(Path configCaseDir, Properties cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("# generated by `sim harvest` — golden is CURRENT behavior, a change detector\n");
        cfg.stringPropertyNames().stream().sorted().forEach(k -> {
            if (SOURCE_KEYS.contains(k)) {
                return;
            }
            String v = cfg.getProperty(k);
            if (PATH_KEYS.contains(k) && v != null && !v.isBlank()) {
                Path resolved = configCaseDir.resolve(v.trim());
                // Only rewrite an actual on-disk path; leave sentinels (schema=ldap)
                // and live values (a DN) verbatim.
                if (Files.exists(resolved)) {
                    v = resolved.toAbsolutePath().normalize().toString();
                }
            }
            // Re-escape backslashes so a slash-form DN round-trips through .properties
            // (Properties.load already decoded the source's `\\`).
            sb.append(k).append('=').append(v == null ? "" : v.replace("\\", "\\\\")).append('\n');
        });
        return sb.toString();
    }

    // ---- DB plumbing (mirrors Cli.doDbEvents) ----

    private static DbEventReader.Config dbConfig(Properties p, String url) {
        DbEventReader.Config c = new DbEventReader.Config();
        c.url = url;
        c.user = p.getProperty("dbUser");
        c.password = p.getProperty("dbPassword");
        String table = trimOrNull(p.getProperty("dbTable"));
        if (table != null) {
            c.table = table;
        }
        return c;
    }

    private static DbEventReader.Query dbQuery(Properties p) {
        DbEventReader.Query q = new DbEventReader.Query();
        q.srcDn = p.getProperty("eventsForDn");
        q.srcDnLike = p.getProperty("eventsDnLike");
        q.driver = p.getProperty("eventsDriver");
        q.eventType = p.getProperty("eventType");
        q.className = p.getProperty("eventClass");
        q.since = p.getProperty("eventsSince");
        q.until = p.getProperty("eventsUntil");
        q.rawWhere = p.getProperty("eventsWhere");
        q.limit = Integer.parseInt(p.getProperty("eventLimit", "200"));
        q.newestFirst = !"asc".equalsIgnoreCase(p.getProperty("eventOrder", "desc"));
        return q;
    }

    // ---- manifest ----

    private static void writeManifest(Path outDir, Path configCaseDir, Properties cfg, String url,
                                      int events, int flagged, List<String> rows) throws IOException {
        String source = describeConfigSource(configCaseDir, cfg);
        StringBuilder sb = new StringBuilder();
        sb.append("# Harvested regression corpus\n\n");
        sb.append("Goldens here are **captured current behavior** — a change detector, ")
          .append("not a correctness oracle. If a policy was buggy when harvested, the bug ")
          .append("is baked in as \"expected.\"\n\n");
        sb.append("- **Source**: Event Logger DB `").append(redactUrl(url)).append("`\n");
        sb.append("- **Config source**: ").append(source).append('\n');
        sb.append("- **Channel**: ").append(str(cfg.getProperty("channel"))).append('\n');
        sb.append("- **Filters**: ").append(describeFilters(cfg)).append('\n');
        sb.append("- **Events harvested**: ").append(events)
          .append("  (").append(flagged).append(" query-light)\n\n");
        sb.append("Re-run the corpus with `bin/sim test-all <thisDir>`. A `query-light` case ")
          .append("issued IDV queries with no seed data or `ldap=` configured, so its golden ")
          .append("may be partial — add `directory.xds`/`ldap=` and re-harvest with `--refresh`.\n\n");
        sb.append("| case | type | src-dn | cached | queries | note |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        Files.writeString(outDir.resolve("HARVEST.md"), sb.toString());
    }

    private static String describeConfigSource(Path dir, Properties cfg) {
        for (String k : new String[]{"export", "project", "ldifConfig"}) {
            String v = trimOrNull(cfg.getProperty(k));
            if (v != null) {
                return k + "=" + dir.resolve(v).toAbsolutePath().normalize()
                    + (cfg.getProperty("driver") != null ? " (driver=" + cfg.getProperty("driver") + ")" : "");
            }
        }
        String ldapConfig = trimOrNull(cfg.getProperty("ldapConfig"));
        if (ldapConfig != null) {
            return "ldapConfig=" + ldapConfig + " (live)";
        }
        return "(none — chain.txt?)";
    }

    private static String describeFilters(Properties p) {
        List<String> f = new ArrayList<>();
        for (String k : new String[]{"eventType", "eventClass", "eventsDriver", "eventsForDn",
                "eventsDnLike", "eventsSince", "eventsUntil", "eventsWhere"}) {
            String v = trimOrNull(p.getProperty(k));
            if (v != null) {
                f.add(k + "=" + v);
            }
        }
        f.add("eventLimit=" + p.getProperty("eventLimit", "200"));
        f.add("eventOrder=" + p.getProperty("eventOrder", "desc"));
        return String.join(", ", f);
    }

    // ---- small helpers ----

    private static Properties loadProps(Path file) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        return p;
    }

    private static boolean hasEntries(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.findAny().isPresent();
        }
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    /** Hide any password embedded in a jdbc URL for the manifest. */
    private static String redactUrl(String url) {
        return url.replaceAll("(?i)(password=)[^&]*", "$1***");
    }

    private static String safe(String s) {
        if (s == null || s.isBlank()) {
            return "x";
        }
        String out = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return out.length() > 32 ? out.substring(out.length() - 32) : out;
    }

    private static String rdn(String dn) {
        if (dn == null || dn.isBlank()) {
            return "x";
        }
        String[] parts = dn.split("[\\\\/,]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            if (!p.isEmpty()) {
                int eq = p.indexOf('=');
                return eq >= 0 ? p.substring(eq + 1) : p;
            }
        }
        return "x";
    }

    private static String pretty(String xml) {
        return xml.replaceAll(">\\s*<", ">\n<");
    }
}
