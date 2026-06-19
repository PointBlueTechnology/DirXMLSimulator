package com.pointblue.dirxml.sim;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregates rule coverage across a corpus of cases: which DirXML Script rules
 * <em>fired</em> (their actions ran) over a set of runs, and — more usefully —
 * which rules are <em>defined but never fired</em>, surfacing dead or untested
 * policy logic in a large driver.
 *
 * <p>"Fired" is read from the engine trace ({@code Applying rule '<name>'.}); the
 * universe of rules is enumerated from the chain's policy stages. Matching is by
 * rule name (the trace doesn't carry the owning policy), so a name shared across
 * stages is considered covered if it fired anywhere — noted as a limitation.
 *
 * <p>{@link #firedRules} and the report builders are pure and unit-test offline.
 */
public final class CoverageReporter {

    private CoverageReporter() {
    }

    private static final Pattern APPLYING = Pattern.compile("Applying rule '(.*?)'\\.");

    /** Rule names whose actions were applied, parsed from an engine trace. */
    public static Set<String> firedRules(String trace) {
        Set<String> fired = new LinkedHashSet<>();
        if (trace != null) {
            Matcher m = APPLYING.matcher(trace);
            while (m.find()) {
                fired.add(m.group(1));
            }
        }
        return fired;
    }

    /** A defined rule and where it lives. */
    public static final class Rule {
        public final String stage;
        public final String name;
        public boolean fired;

        Rule(String stage, String name) {
            this.stage = stage;
            this.name = name;
        }
    }

    /** The accumulated coverage across all cases run so far. */
    public static final class Coverage {
        // keyed by "stage rule" to keep same-named rules in different stages distinct
        private final Map<String, Rule> rules = new LinkedHashMap<>();
        private final Set<String> firedNames = new LinkedHashSet<>();
        public int cases;

        /** Register the rules a case defines (from its chain stages). */
        public void define(List<PolicyStage> stages) {
            for (PolicyStage s : stages) {
                defineStage(s.name(), s.ruleNames());
            }
        }

        /** Register one stage's rules (primitive; testable without a PolicyStage). */
        public void defineStage(String stage, List<String> ruleNames) {
            for (String r : ruleNames) {
                rules.computeIfAbsent(stage + " " + r, k -> new Rule(stage, r));
            }
        }

        /** Record the rules that fired in a case's trace. */
        public void observe(String trace) {
            cases++;
            firedNames.addAll(firedRules(trace));
        }

        /** Resolve fired flags (by name) and return the rules in definition order. */
        public List<Rule> rules() {
            List<Rule> out = new ArrayList<>(rules.values());
            for (Rule r : out) {
                r.fired = firedNames.contains(r.name);
            }
            return out;
        }

        public List<Rule> uncovered() {
            return rules().stream().filter(r -> !r.fired).toList();
        }
    }

    /** Human summary: counts, percentage, and the never-fired rules grouped by stage. */
    public static String summary(Coverage cov) {
        List<Rule> all = cov.rules();
        long fired = all.stream().filter(r -> r.fired).count();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("rule coverage: %d/%d fired (%.0f%%) across %d case(s)%n",
            fired, all.size(), all.isEmpty() ? 100.0 : 100.0 * fired / all.size(), cov.cases));
        List<Rule> uncovered = cov.uncovered();
        if (uncovered.isEmpty()) {
            sb.append("  all defined rules fired");
            return sb.toString();
        }
        sb.append("  never fired (").append(uncovered.size()).append("):\n");
        String stage = null;
        for (Rule r : uncovered) {
            if (!r.stage.equals(stage)) {
                stage = r.stage;
                sb.append("    ").append(stage).append('\n');
            }
            sb.append("      - ").append(r.name).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Structured coverage for {@code --json}. */
    public static String toJson(Coverage cov, Path dir) {
        List<Rule> all = cov.rules();
        long fired = all.stream().filter(r -> r.fired).count();
        List<String> rules = new ArrayList<>();
        for (Rule r : all) {
            rules.add(Json.obj("stage", Json.q(r.stage), "rule", Json.q(r.name),
                "fired", r.fired ? "true" : "false"));
        }
        return Json.obj(
            "command", Json.q("coverage"),
            "dir", Json.q(dir.toString()),
            "cases", Integer.toString(cov.cases),
            "defined", Integer.toString(all.size()),
            "fired", Long.toString(fired),
            "uncovered", Integer.toString(all.size() - (int) fired),
            "rules", Json.arr(rules));
    }
}
