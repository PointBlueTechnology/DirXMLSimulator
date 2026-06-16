package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.rules.DirXMLScriptProcessor;
import com.novell.nds.dirxml.engine.rules.RuleDynamicContext;
import com.novell.nds.dirxml.engine.rules.RuleProcessor;
import com.novell.nds.dirxml.engine.rules.SchemaMappingRuleProcessor;
import com.novell.nds.dirxml.engine.rules.XSLTRuleProcessor;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Path;

/**
 * One named stage in a channel chain: a {@link RuleProcessor} (DirXML Script,
 * XSLT, or schema mapping) plus a label for trace/snapshot attribution.
 *
 * <p>Stages are intentionally <em>not</em> linked via {@code setNextRule} — the
 * {@link ChannelSimulator} drives each stage's {@code applyRules} itself so it
 * can capture the document between stages (stepping).
 */
public final class PolicyStage {

    private final String name;
    private final RuleProcessor processor;

    private PolicyStage(String name, RuleProcessor processor) {
        this.name = name;
        this.processor = processor;
    }

    public String name() {
        return name;
    }

    public Document apply(Document doc, RuleDynamicContext dyn) throws Exception {
        return processor.applyRules(doc, dyn);
    }

    /** Build a stage from a policy element, choosing the processor by root name. */
    public static PolicyStage fromElement(String name, Element policy, EngineContext ctx) {
        try {
            RuleProcessor proc;
            String root = policy.getLocalName();
            switch (root) {
                case "policy":
                    proc = new DirXMLScriptProcessor(policy, ctx.staticContext());
                    break;
                case "style-sheet":
                    proc = new XSLTRuleProcessor(policy, ctx.staticContext());
                    break;
                case "attr-name-map":
                case "schema-mapping":
                    proc = new SchemaMappingRuleProcessor(policy, ctx.staticContext());
                    break;
                default:
                    // Default to DirXML Script; most authored policies are <policy>.
                    proc = new DirXMLScriptProcessor(policy, ctx.staticContext());
            }
            return new PolicyStage(name, proc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build stage '" + name + "': " + e, e);
        }
    }

    /** Build a stage by loading a policy file (Designer *_contents.xml, export, etc.). */
    public static PolicyStage fromFile(Path file, EngineContext ctx) {
        return fromElement(file.getFileName().toString(), PolicyLoader.load(file), ctx);
    }

    public static PolicyStage fromFile(String name, Path file, EngineContext ctx) {
        return fromElement(name, PolicyLoader.load(file), ctx);
    }
}
