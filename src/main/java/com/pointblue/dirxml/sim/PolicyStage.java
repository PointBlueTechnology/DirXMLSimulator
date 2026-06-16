package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.rules.DirXMLScriptProcessor;
import com.novell.nds.dirxml.engine.rules.RuleDynamicContext;
import com.novell.nds.dirxml.engine.rules.RuleProcessor;
import com.novell.nds.dirxml.engine.rules.SchemaMappingRuleProcessor;
import com.novell.nds.dirxml.engine.rules.XSLTRuleProcessor;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private final Element source;   // the policy/style-sheet/attr-name-map element
    private final String type;      // policy | style-sheet | attr-name-map

    private PolicyStage(String name, RuleProcessor processor, Element source, String type) {
        this.name = name;
        this.processor = processor;
        this.source = source;
        this.type = type;
    }

    public String name() {
        return name;
    }

    public Document apply(Document doc, RuleDynamicContext dyn) throws Exception {
        return processor.applyRules(doc, dyn);
    }

    /** True if this is a DirXML Script policy (the only kind that has rules to step). */
    public boolean isDirXMLScript() {
        return "policy".equals(type);
    }

    /** Java extension classes (nxsl/java/ namespaces) this policy references but that aren't on the classpath. */
    public List<String> missingJavaClasses() {
        return source == null ? Collections.emptyList() : JavaExtensions.missingClasses(source);
    }

    /**
     * Expand a DirXML Script policy into one single-rule stage per {@code <rule>},
     * for per-rule stepping. Each sub-stage is a real one-rule policy run in order
     * (sharing the channel's dynamic context). Non-policy stages, and policies with
     * a single rule, return just themselves.
     *
     * <p><b>Caveat:</b> {@code DirXMLScriptProcessor.applyRules} re-initializes
     * policy-scoped local variables per call, so a {@code scope="policy"} local set
     * by one rule is not visible to a later rule when stepped (driver-scoped locals
     * persist via the static context). The rule trace still reflects true behavior.
     */
    public List<PolicyStage> explodeByRule(EngineContext ctx) {
        if (!isDirXMLScript() || source == null) {
            return Collections.singletonList(this);
        }
        // Re-parse the policy standalone so removals don't touch the source doc.
        Element policy = Xds.parse(Xds.serializeElement(source)).getDocumentElement();
        List<Element> rules = Xds.childrenByName(policy, "rule");
        if (rules.size() <= 1) {
            return Collections.singletonList(this);
        }
        List<PolicyStage> out = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            Element single = Xds.parse(Xds.serializeElement(source)).getDocumentElement();
            List<Element> singleRules = Xds.childrenByName(single, "rule");
            for (int k = singleRules.size() - 1; k >= 0; k--) {
                if (k != i) {
                    single.removeChild(singleRules.get(k));
                }
            }
            String desc = ruleDescription(rules.get(i));
            String label = name + " ▸ #" + (i + 1) + (desc.isEmpty() ? "" : " " + desc);
            out.add(fromElement(label, single, ctx));
        }
        return out;
    }

    private static String ruleDescription(Element rule) {
        List<Element> d = Xds.childrenByName(rule, "description");
        if (d.isEmpty()) {
            return "";
        }
        String text = Xds.text(d.get(0)).trim();
        return text.length() > 60 ? text.substring(0, 57) + "…" : text;
    }

    /** Build a stage from a policy element, choosing the processor by root name. */
    public static PolicyStage fromElement(String name, Element policy, EngineContext ctx) {
        try {
            RuleProcessor proc;
            String root = policy.getLocalName();
            String type = root;
            // XSLT policies are <xsl:stylesheet>/<xsl:transform> in the XSLT namespace.
            if (PolicyLoader.XSLT_NS.equals(policy.getNamespaceURI())) {
                proc = new XSLTRuleProcessor(policy, ctx.staticContext());
                return new PolicyStage(name, proc, policy, "xslt");
            }
            switch (root) {
                case "policy":
                    proc = new DirXMLScriptProcessor(policy, ctx.staticContext());
                    break;
                case "style-sheet":
                    proc = new XSLTRuleProcessor(policy, ctx.staticContext());
                    type = "xslt";
                    break;
                case "attr-name-map":
                case "schema-mapping":
                    proc = new SchemaMappingRuleProcessor(policy, ctx.staticContext());
                    break;
                default:
                    // Default to DirXML Script; most authored policies are <policy>.
                    proc = new DirXMLScriptProcessor(policy, ctx.staticContext());
                    type = "policy";
            }
            return new PolicyStage(name, proc, policy, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build stage '" + name + "': " + e, e);
        }
    }

    /** A channel filter stage (drops ignored classes / strips ignored attributes). */
    public static PolicyStage filter(String name, Element filterXml, boolean publisherChannel) {
        return new PolicyStage(name, new FilterRuleProcessor(filterXml, publisherChannel), null, "filter");
    }

    /** Build a stage by loading a policy file (Designer *_contents.xml, export, etc.). */
    public static PolicyStage fromFile(Path file, EngineContext ctx) {
        return fromElement(file.getFileName().toString(), PolicyLoader.load(file), ctx);
    }

    public static PolicyStage fromFile(String name, Path file, EngineContext ctx) {
        return fromElement(name, PolicyLoader.load(file), ctx);
    }
}
