package com.pointblue.dirxml.sim;

import java.util.List;

/**
 * The captured result of one stage in a channel run: the document that entered
 * the stage, the document it produced, the trace it emitted, and the
 * queries/commands it issued to the directory seam while running.
 *
 * <p>This is what makes the harness a stepper rather than a black box.
 */
public final class StageSnapshot {

    public final String stageName;
    public final String inputXds;
    public final String outputXds;
    public final String trace;
    public final List<String> queries;
    public final List<String> commands;

    public StageSnapshot(String stageName, String inputXds, String outputXds,
                         String trace, List<String> queries, List<String> commands) {
        this.stageName = stageName;
        this.inputXds = inputXds;
        this.outputXds = outputXds;
        this.trace = trace;
        this.queries = queries;
        this.commands = commands;
    }

    /** True if this stage changed the document. */
    public boolean changed() {
        return !inputXds.equals(outputXds);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("== stage: ").append(stageName).append(" ==\n");
        sb.append("changed: ").append(changed()).append('\n');
        if (!queries.isEmpty()) {
            sb.append("queries issued: ").append(queries.size()).append('\n');
        }
        if (!commands.isEmpty()) {
            sb.append("commands issued: ").append(commands.size()).append('\n');
        }
        sb.append("output:\n").append(outputXds).append('\n');
        return sb.toString();
    }
}
