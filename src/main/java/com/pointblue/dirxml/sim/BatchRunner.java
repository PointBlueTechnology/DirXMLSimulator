package com.pointblue.dirxml.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs a whole directory of cases as golden tests and reports the results — the
 * batch counterpart to {@code bin/sim test}. A case is "passed" exactly as
 * {@code test} defines it (final output, and directory end-state when an
 * {@code expected-directory.xds} is present); this class adds discovery,
 * per-case outcome classification, and machine-readable reporting (JUnit / JSON)
 * for CI.
 *
 * <p>The reporting methods ({@link #summary}, {@link #toJunit}, {@link #toJson})
 * are pure functions of a {@code List<CaseResult>} so they unit-test offline
 * against synthetic results, with no engine involved.
 */
public final class BatchRunner {

    private BatchRunner() {
    }

    /** A case outcome. ERROR (broken case) is kept distinct from FAIL (behavior changed). */
    public enum Outcome { PASS, FAIL, ERROR, SKIP }

    /** The result of running one case. */
    public static final class CaseResult {
        public final String name;       // path relative to the discovery root, '/'-separated
        public final Outcome outcome;
        public final long millis;
        public final String detail;     // diff text (FAIL), error message (ERROR), or ""

        public CaseResult(String name, Outcome outcome, long millis, String detail) {
            this.name = name;
            this.outcome = outcome;
            this.millis = millis;
            this.detail = detail == null ? "" : detail;
        }
    }

    /**
     * Find every runnable case under {@code root}: a directory containing an
     * {@code input.xds} (the operation a golden test runs). Source-only config
     * dirs (e.g. a {@code dbevents} fetch config with no input) are not cases.
     * Results are sorted by relative path for stable, reviewable output.
     */
    public static List<Path> discover(Path root) throws IOException {
        List<Path> cases = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                .filter(d -> Files.exists(d.resolve("input.xds")))
                .sorted(Comparator.comparing(Path::toString))
                .forEach(cases::add);
        }
        return cases;
    }

    /** Run one case and classify the outcome. Never throws — failures become ERROR. */
    public static CaseResult run(Path root, Path caseDir) {
        String name = relativeName(root, caseDir);
        long start = System.nanoTime();
        try {
            Case c = Case.load(caseDir);
            ChannelSimulator.Result r = c.run();

            boolean haveOut = Files.exists(c.expectedOutput);
            boolean haveDir = Files.exists(c.expectedDirectory);
            if (!haveOut && !haveDir) {
                return result(name, Outcome.SKIP, start,
                    "no expected-output.xds; run 'record' to capture a golden");
            }

            StringBuilder detail = new StringBuilder();
            boolean ok = true;
            if (haveOut) {
                XmlCompare.Diff d = XmlCompare.compare(readString(c.expectedOutput), r.finalXds);
                if (!d.equal) {
                    ok = false;
                    detail.append("output: ").append(d.message);
                }
            }
            if (haveDir) {
                XmlCompare.Diff d = XmlCompare.compare(
                    readString(c.expectedDirectory), c.directory.dumpState());
                if (!d.equal) {
                    ok = false;
                    if (detail.length() > 0) {
                        detail.append('\n');
                    }
                    detail.append("directory: ").append(d.message);
                }
            }
            return result(name, ok ? Outcome.PASS : Outcome.FAIL, start, detail.toString());
        } catch (Exception e) {
            return result(name, Outcome.ERROR, start, rootCauseMessage(e));
        }
    }

    /** Run every discovered case under {@code root}. */
    public static List<CaseResult> runAll(Path root) throws IOException {
        List<CaseResult> results = new ArrayList<>();
        for (Path caseDir : discover(root)) {
            results.add(run(root, caseDir));
        }
        return results;
    }

    // ---- reporting (pure) ----

    /** Human summary: one line per case, then a totals line. */
    public static String summary(List<CaseResult> results) {
        StringBuilder sb = new StringBuilder();
        int pass = 0, fail = 0, error = 0, skip = 0;
        long totalMs = 0;
        for (CaseResult r : results) {
            sb.append(String.format("  %-5s %s", r.outcome, r.name));
            if (r.outcome == Outcome.FAIL || r.outcome == Outcome.ERROR) {
                sb.append("   ").append(firstLine(r.detail));
            }
            sb.append('\n');
            totalMs += r.millis;
            switch (r.outcome) {
                case PASS -> pass++;
                case FAIL -> fail++;
                case ERROR -> error++;
                case SKIP -> skip++;
            }
        }
        sb.append("  ").append("─".repeat(33)).append('\n');
        sb.append(String.format("  %d cases: %d passed, %d failed, %d error, %d skipped   (%.1fs)",
            results.size(), pass, fail, error, skip, totalMs / 1000.0));
        return sb.toString();
    }

    /** True if any case failed or errored — the CI gate (non-zero exit). */
    public static boolean anyFailed(List<CaseResult> results) {
        return results.stream().anyMatch(r -> r.outcome == Outcome.FAIL || r.outcome == Outcome.ERROR);
    }

    /** JUnit XML, consumable by GitHub Actions / Jenkins / GitLab. */
    public static String toJunit(List<CaseResult> results, String suiteName) {
        long fail = results.stream().filter(r -> r.outcome == Outcome.FAIL).count();
        long error = results.stream().filter(r -> r.outcome == Outcome.ERROR).count();
        long skip = results.stream().filter(r -> r.outcome == Outcome.SKIP).count();
        long totalMs = results.stream().mapToLong(r -> r.millis).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append(String.format(
            "<testsuite name=\"%s\" tests=\"%d\" failures=\"%d\" errors=\"%d\" skipped=\"%d\" time=\"%.3f\">%n",
            xml(suiteName), results.size(), fail, error, skip, totalMs / 1000.0));
        for (CaseResult r : results) {
            sb.append(String.format("  <testcase name=\"%s\" classname=\"dirxml-sim\" time=\"%.3f\"",
                xml(r.name), r.millis / 1000.0));
            switch (r.outcome) {
                case PASS -> sb.append("/>\n");
                case SKIP -> sb.append(">\n    <skipped/>\n  </testcase>\n");
                case FAIL -> sb.append(">\n    <failure message=\"")
                    .append(xml(firstLine(r.detail))).append("\">")
                    .append(xml(r.detail)).append("</failure>\n  </testcase>\n");
                case ERROR -> sb.append(">\n    <error message=\"")
                    .append(xml(firstLine(r.detail))).append("\">")
                    .append(xml(r.detail)).append("</error>\n  </testcase>\n");
            }
        }
        sb.append("</testsuite>\n");
        return sb.toString();
    }

    /** A compact JSON array of results (hand-built; no JSON dependency). */
    public static String toJson(List<CaseResult> results) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < results.size(); i++) {
            CaseResult r = results.get(i);
            sb.append(String.format(
                "  {\"name\": \"%s\", \"outcome\": \"%s\", \"millis\": %d, \"detail\": \"%s\"}",
                json(r.name), r.outcome, r.millis, json(r.detail)));
            sb.append(i < results.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    // ---- helpers ----

    private static CaseResult result(String name, Outcome o, long startNanos, String detail) {
        return new CaseResult(name, o, (System.nanoTime() - startNanos) / 1_000_000, detail);
    }

    private static String relativeName(Path root, Path caseDir) {
        Path rel = root.toAbsolutePath().relativize(caseDir.toAbsolutePath());
        String s = rel.toString().replace('\\', '/');
        return s.isEmpty() ? caseDir.getFileName().toString() : s;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return c.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static String readString(Path p) throws IOException {
        return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        b.append(String.format("\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
                }
            }
        }
        return b.toString();
    }
}
