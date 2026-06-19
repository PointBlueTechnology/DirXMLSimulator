package com.pointblue.dirxml.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares two channel runs of the <em>same input</em> through two policy sets
 * (e.g. a driver export vs an edited/older revision of it) and reports where the
 * behavior diverges — per stage and in the final output. The headline is whether
 * the final output differs; the per-stage view tells you which rule set first
 * changed it.
 *
 * <p>{@link #diff} is a pure function of two {@link ChannelSimulator.Result}s, so
 * it unit-tests offline against synthetic runs.
 */
public final class Comparer {

    private Comparer() {
    }

    /** One stage's comparison. {@code presentIn} is "both", "a", or "b". */
    public static final class StageDiff {
        public final String name;
        public final String presentIn;
        public final boolean same;
        public final String detail;   // XmlCompare message when they differ; "" otherwise

        StageDiff(String name, String presentIn, boolean same, String detail) {
            this.name = name;
            this.presentIn = presentIn;
            this.same = same;
            this.detail = detail == null ? "" : detail;
        }
    }

    public static final class Comparison {
        public final List<StageDiff> stages;
        public final boolean finalSame;
        public final String finalDetail;
        public final String firstDivergesAt;   // stage name, or null if the final output matches

        Comparison(List<StageDiff> stages, boolean finalSame, String finalDetail, String firstDivergesAt) {
            this.stages = stages;
            this.finalSame = finalSame;
            this.finalDetail = finalDetail == null ? "" : finalDetail;
            this.firstDivergesAt = firstDivergesAt;
        }
    }

    /** Diff run {@code a} against run {@code b}, aligning stages by position. */
    public static Comparison diff(ChannelSimulator.Result a, ChannelSimulator.Result b) {
        List<StageDiff> stages = new ArrayList<>();
        String firstDiverges = null;
        int n = Math.max(a.stages.size(), b.stages.size());
        for (int i = 0; i < n; i++) {
            StageSnapshot sa = i < a.stages.size() ? a.stages.get(i) : null;
            StageSnapshot sb = i < b.stages.size() ? b.stages.get(i) : null;
            StageDiff sd;
            if (sa != null && sb != null) {
                XmlCompare.Diff d = XmlCompare.compare(sa.outputXds, sb.outputXds);
                String name = sa.stageName.equals(sb.stageName)
                    ? sa.stageName : sa.stageName + " / " + sb.stageName;
                sd = new StageDiff(name, "both", d.equal, d.equal ? "" : d.message);
            } else if (sa != null) {
                sd = new StageDiff(sa.stageName, "a", false, "stage only in A");
            } else {
                sd = new StageDiff(sb.stageName, "b", false, "stage only in B");
            }
            stages.add(sd);
            if (!sd.same && firstDiverges == null) {
                firstDiverges = sd.name;
            }
        }
        XmlCompare.Diff fin = XmlCompare.compare(a.finalXds, b.finalXds);
        return new Comparison(stages, fin.equal, fin.equal ? "" : fin.message,
            fin.equal ? null : (firstDiverges != null ? firstDiverges : "final"));
    }
}
