package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Trace parsing + coverage aggregation. No engine needed. */
public class CoverageReporterTest {

    private static final String TRACE =
        "  Applying to add #1.\n"
        + "    Evaluating selection criteria for rule 'stamp A'.\n"
        + "    Rule selected.\n"
        + "    Applying rule 'stamp A'.\n"
        + "    Evaluating selection criteria for rule 'veto if no Surname'.\n"
        + "    Rule rejected.\n";

    @Test
    public void firedRulesExtractsOnlyAppliedRules() {
        Set<String> fired = CoverageReporter.firedRules(TRACE);
        assertTrue(fired.contains("stamp A"));
        assertFalse("evaluated-but-rejected did not fire", fired.contains("veto if no Surname"));
    }

    @Test
    public void firedRulesHandlesNullAndEmpty() {
        assertTrue(CoverageReporter.firedRules(null).isEmpty());
        assertTrue(CoverageReporter.firedRules("no rules here").isEmpty());
    }

    private static CoverageReporter.Coverage withDefinedAndObserved() {
        CoverageReporter.Coverage cov = new CoverageReporter.Coverage();
        // Two named rules defined; only one fires in the observed trace.
        cov.defineStage("event", List.of("stamp A", "veto if no Surname"));
        cov.observe(TRACE);
        return cov;
    }

    @Test
    public void aggregatesDefinedVsFiredAcrossObservations() {
        CoverageReporter.Coverage cov = withDefinedAndObserved();

        List<CoverageReporter.Rule> rules = cov.rules();
        assertEquals(2, rules.size());
        assertTrue(rules.stream().anyMatch(r -> r.name.equals("stamp A") && r.fired));
        List<CoverageReporter.Rule> uncovered = cov.uncovered();
        assertEquals(1, uncovered.size());
        assertEquals("veto if no Surname", uncovered.get(0).name);
        assertEquals(1, cov.cases);
    }

    @Test
    public void summaryReportsCountsAndNeverFired() {
        String s = CoverageReporter.summary(withDefinedAndObserved());
        assertTrue(s.contains("1/2 fired"));
        assertTrue(s.contains("never fired"));
        assertTrue(s.contains("veto if no Surname"));
    }

    @Test
    public void jsonHasCountsAndPerRuleFiredFlags() {
        String j = CoverageReporter.toJson(withDefinedAndObserved(), Path.of("cases"));
        assertTrue(j.contains("\"defined\":2"));
        assertTrue(j.contains("\"fired\":1"));
        assertTrue(j.contains("\"rule\":\"stamp A\",\"fired\":true"));
    }
}
