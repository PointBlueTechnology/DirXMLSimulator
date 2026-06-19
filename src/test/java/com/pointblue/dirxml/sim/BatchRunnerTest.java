package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Outcome classification reporting (summary / JUnit / JSON). No engine needed. */
public class BatchRunnerTest {

    private static BatchRunner.CaseResult r(String name, BatchRunner.Outcome o, String detail) {
        return new BatchRunner.CaseResult(name, o, 12, detail);
    }

    private final List<BatchRunner.CaseResult> sample = List.of(
        r("add-user", BatchRunner.Outcome.PASS, ""),
        r("terminate", BatchRunner.Outcome.FAIL, "output: 3 nodes differ\nat /nds/output"),
        r("broken", BatchRunner.Outcome.ERROR, "IllegalArgumentException: bad chain"),
        r("draft", BatchRunner.Outcome.SKIP, "no expected-output.xds"));

    @Test
    public void summaryCountsEachOutcomeAndShowsFailDetailFirstLineOnly() {
        String s = BatchRunner.summary(sample);
        assertTrue(s.contains("PASS  add-user"));
        assertTrue(s.contains("FAIL  terminate"));
        assertTrue(s.contains("output: 3 nodes differ"));
        assertFalse("only the first line of detail is inlined", s.contains("at /nds/output"));
        assertTrue(s.contains("4 cases: 1 passed, 1 failed, 1 error, 1 skipped"));
    }

    @Test
    public void anyFailedTrueWhenFailOrError() {
        assertTrue(BatchRunner.anyFailed(sample));
        assertFalse(BatchRunner.anyFailed(List.of(
            r("a", BatchRunner.Outcome.PASS, ""),
            r("b", BatchRunner.Outcome.SKIP, "x"))));
    }

    @Test
    public void junitHasCountsAndElementPerOutcome() {
        String x = BatchRunner.toJunit(sample, "suite");
        assertTrue(x.startsWith("<?xml"));
        assertTrue(x.contains("tests=\"4\" failures=\"1\" errors=\"1\" skipped=\"1\""));
        assertTrue(x.contains("<testcase name=\"add-user\" classname=\"dirxml-sim\""));
        assertTrue(x.contains("<failure message=\"output: 3 nodes differ\">"));
        assertTrue(x.contains("<error message=\"IllegalArgumentException: bad chain\">"));
        assertTrue(x.contains("<skipped/>"));
        assertTrue(x.contains("</testsuite>"));
    }

    @Test
    public void junitEscapesXmlSpecialChars() {
        String x = BatchRunner.toJunit(List.of(
            r("c", BatchRunner.Outcome.FAIL, "expected <a> & \"b\" > c")), "s & <t>");
        assertTrue(x.contains("name=\"s &amp; &lt;t&gt;\""));
        assertTrue(x.contains("&lt;a&gt; &amp; &quot;b&quot; &gt; c"));
        assertFalse(x.contains("<a>"));    // the raw angle bracket must be escaped
    }

    @Test
    public void jsonIsWellFormedAndEscaped() {
        String j = BatchRunner.toJson(List.of(
            r("x", BatchRunner.Outcome.FAIL, "line1\n\"quoted\"")));
        assertTrue(j.contains("\"name\": \"x\""));
        assertTrue(j.contains("\"outcome\": \"FAIL\""));
        assertTrue(j.contains("\"millis\": 12"));
        assertTrue(j.contains("line1\\n\\\"quoted\\\""));
    }

    @Test
    public void emptyResultsAreReportableNotCrashing() {
        assertFalse(BatchRunner.anyFailed(List.of()));
        assertTrue(BatchRunner.summary(List.of()).contains("0 cases"));
        assertTrue(BatchRunner.toJunit(List.of(), "s").contains("tests=\"0\""));
        assertEquals("[\n]\n", BatchRunner.toJson(List.of()));
    }
}
