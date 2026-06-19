package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.XdsCommandProcessor;
import com.novell.nds.dirxml.engine.XdsQueryProcessor;
import com.novell.nds.dirxml.engine.rules.RuleDynamicContext;

import org.w3c.dom.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs an XDS operation through an ordered list of {@link PolicyStage}s — a
 * simulated channel — capturing a {@link StageSnapshot} between every stage.
 *
 * <p>Stages are driven individually (not via {@code setNextRule}) so the
 * document, trace, and directory interactions are captured per stage. A single
 * {@link RuleDynamicContext} is shared across the run, matching how the engine
 * carries one operation context through a channel's policy sets.
 */
public final class ChannelSimulator {

    /** The result of a channel run. */
    public static final class Result {
        public final List<StageSnapshot> stages;
        public final Document finalDoc;
        public final String finalXds;
        public final String fullTrace;

        Result(List<StageSnapshot> stages, Document finalDoc, String fullTrace) {
            this.stages = stages;
            this.finalDoc = finalDoc;
            this.finalXds = Xds.serialize(finalDoc);
            this.fullTrace = fullTrace;
        }

        public StageSnapshot stage(String name) {
            for (StageSnapshot s : stages) {
                if (s.stageName.equals(name)) {
                    return s;
                }
            }
            return null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (StageSnapshot s : stages) {
                sb.append(s).append('\n');
            }
            return sb.toString();
        }
    }

    private final EngineContext ctx;
    private final FakeDirectory dir;
    private final List<PolicyStage> stages = new ArrayList<>();
    private XdsQueryProcessor querySource;   // optional: live LDAP instead of FakeDirectory
    private XdsCommandProcessor commandSink;  // optional: a real shim as terminal command sink

    public ChannelSimulator(EngineContext ctx, FakeDirectory dir) {
        this.ctx = ctx;
        this.dir = dir != null ? dir : new FakeDirectory();
    }

    /**
     * Answer the chain's queries from this source (e.g. {@link LdapQueryProcessor}
     * over live eDir) instead of the {@link FakeDirectory}. Optional — null keeps
     * the FakeDirectory as the query source. Commands still write back to the
     * FakeDirectory during the chain.
     */
    public ChannelSimulator withQuerySource(XdsQueryProcessor source) {
        this.querySource = source;
        return this;
    }

    /**
     * After the chain, hand the final command to this sink (a {@link ShimAdapter}
     * over a real connector) and capture its response as a terminal {@code shim}
     * snapshot. Optional — null ends the run at the chain's output, as before.
     */
    public ChannelSimulator withCommandSink(XdsCommandProcessor sink) {
        this.commandSink = sink;
        return this;
    }

    public ChannelSimulator add(PolicyStage stage) {
        stages.add(stage);
        return this;
    }

    public ChannelSimulator addAll(List<PolicyStage> s) {
        stages.addAll(s);
        return this;
    }

    /** The ordered policy stages of this channel (read-only) — e.g. for rule coverage. */
    public List<PolicyStage> policyStages() {
        return List.copyOf(stages);
    }

    /** Distinct Java extension classes referenced by any stage that aren't on the classpath. */
    public List<String> missingJavaClasses() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (PolicyStage s : stages) {
            all.addAll(s.missingJavaClasses());
        }
        return new ArrayList<>(all);
    }

    /** Distinct named-password names referenced by any stage. */
    public List<String> referencedNamedPasswords() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (PolicyStage s : stages) {
            all.addAll(s.referencedNamedPasswords());
        }
        return new ArrayList<>(all);
    }

    /** Distinct external actions across all stages (faked when faking is enabled). */
    public List<String> externalActions() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (PolicyStage s : stages) {
            all.addAll(s.externalActions());
        }
        return new ArrayList<>(all);
    }

    /** Distinct GCV names referenced by any stage. */
    public List<String> referencedGcvs() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (PolicyStage s : stages) {
            all.addAll(s.referencedGcvs());
        }
        return new ArrayList<>(all);
    }

    /** Run the input operation through all stages, capturing per-stage snapshots. */
    public Result run(Document input) {
        return run(input, false);
    }

    /**
     * Run the input through all stages. When {@code perRule} is true, each DirXML
     * Script policy is expanded into one snapshot per {@code <rule>} (see
     * {@link PolicyStage#explodeByRule}); other stage types run whole.
     */
    public Result run(Document input, boolean perRule) {
        ctx.resetTrace();
        dir.drainQueries();   // clear any prior interactions
        dir.drainCommands();

        RuleDynamicContext dyn = ctx.newDynamicContext(
            querySource != null ? querySource : dir, dir);
        Document doc = input;
        List<StageSnapshot> snapshots = new ArrayList<>();

        List<PolicyStage> effective = new ArrayList<>();
        for (PolicyStage s : stages) {
            effective.addAll(perRule ? s.explodeByRule(ctx) : List.of(s));
        }

        for (PolicyStage stage : effective) {
            String inXds = Xds.serialize(doc);
            int traceMark = ctx.trace().length();
            try {
                doc = stage.apply(doc, dyn);
            } catch (Exception e) {
                // A failing stage shouldn't crash the run — record it and stop, so
                // the snapshots up to the failure (and the error) are still shown.
                String t = ctx.trace().substring(Math.min(traceMark, ctx.trace().length()));
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                snapshots.add(new StageSnapshot(stage.name(), inXds, inXds, t,
                    dir.drainQueries(), dir.drainCommands(), cause.getMessage()));
                break;
            }
            String outXds = Xds.serialize(doc);
            String stageTrace = ctx.trace().substring(Math.min(traceMark, ctx.trace().length()));
            snapshots.add(new StageSnapshot(
                stage.name(), inXds, outXds, stageTrace,
                dir.drainQueries(), dir.drainCommands()));
        }

        // Optional terminal sink: hand the chain's final command to a real shim
        // and capture its response as a "shim" snapshot. The chain output (doc) is
        // unchanged — the shim's status/association is shown separately.
        if (commandSink != null) {
            String inXds = Xds.serialize(doc);
            int mark = ctx.trace().length();
            try {
                Document resp = commandSink.execute(doc);
                String outXds = resp != null ? Xds.serialize(resp) : "";
                String t = ctx.trace().substring(Math.min(mark, ctx.trace().length()));
                snapshots.add(new StageSnapshot("shim", inXds, outXds, t,
                    dir.drainQueries(), dir.drainCommands()));
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                snapshots.add(new StageSnapshot("shim", inXds, inXds, "",
                    dir.drainQueries(), dir.drainCommands(), cause.getMessage()));
            }
        }

        return new Result(snapshots, doc, ctx.trace());
    }

    public Result run(String inputXds) {
        return run(Xds.parse(inputXds));
    }

    public Result run(String inputXds, boolean perRule) {
        return run(Xds.parse(inputXds), perRule);
    }
}
