package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Per-stage / final divergence between two runs. No engine needed. */
public class ComparerTest {

    private static StageSnapshot stage(String name, String output) {
        return new StageSnapshot(name, "<in/>", output, "", List.of(), List.of());
    }

    private static ChannelSimulator.Result result(String finalXds, StageSnapshot... stages) {
        return new ChannelSimulator.Result(List.of(stages), Xds.parse(finalXds), "");
    }

    @Test
    public void identicalRunsReportNoDivergence() {
        ChannelSimulator.Result a = result("<out>x</out>", stage("matching", "<a/>"), stage("command", "<b/>"));
        ChannelSimulator.Result b = result("<out>x</out>", stage("matching", "<a/>"), stage("command", "<b/>"));
        Comparer.Comparison c = Comparer.diff(a, b);

        assertTrue(c.finalSame);
        assertNull(c.firstDivergesAt);
        assertTrue(c.stages.stream().allMatch(s -> s.same));
    }

    @Test
    public void pinpointsFirstDivergingStageAndFinal() {
        ChannelSimulator.Result a = result("<out>A</out>", stage("matching", "<a/>"), stage("command", "<b1/>"));
        ChannelSimulator.Result b = result("<out>B</out>", stage("matching", "<a/>"), stage("command", "<b2/>"));
        Comparer.Comparison c = Comparer.diff(a, b);

        assertFalse(c.finalSame);
        assertEquals("command", c.firstDivergesAt);
        assertTrue(c.stages.get(0).same);                 // matching unchanged
        assertFalse(c.stages.get(1).same);                // command diverged
        assertFalse(c.finalDetail.isBlank());
    }

    @Test
    public void differentStageCountsAreFlagged() {
        ChannelSimulator.Result a = result("<o/>", stage("matching", "<a/>"), stage("create", "<c/>"));
        ChannelSimulator.Result b = result("<o/>", stage("matching", "<a/>"));
        Comparer.Comparison c = Comparer.diff(a, b);

        assertEquals(2, c.stages.size());
        assertEquals("a", c.stages.get(1).presentIn);     // extra stage only in A
        assertFalse(c.stages.get(1).same);
        assertTrue("final output still equal", c.finalSame);
    }

    @Test
    public void mismatchedStageNamesAreShownBothSides() {
        ChannelSimulator.Result a = result("<o/>", stage("input", "<x/>"));
        ChannelSimulator.Result b = result("<o/>", stage("output", "<x/>"));
        Comparer.Comparison c = Comparer.diff(a, b);

        assertEquals("input / output", c.stages.get(0).name);
        assertTrue(c.stages.get(0).same);                 // same output despite different stage name
    }
}
