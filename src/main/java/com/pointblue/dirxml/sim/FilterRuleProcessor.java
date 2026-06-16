package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.rules.RuleDynamicContext;
import com.novell.nds.dirxml.engine.rules.RuleProcessor;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a driver's filter to a channel document, the way the engine gates
 * what syncs: a class whose channel state is {@code ignore} (or that isn't in the
 * filter) has its operations dropped; an attribute whose channel state is
 * {@code ignore} (or that isn't listed for its class) is stripped from the
 * operation.
 *
 * <p>Implemented directly (parsing the {@code <filter>} XML) rather than via the
 * engine's {@code Filter} class, which requires a live {@code ConfigAbstraction}.
 * It implements {@link RuleProcessor} so it slots into the chain as a normal stage.
 */
public final class FilterRuleProcessor implements RuleProcessor {

    private final String channelAttr;                       // "subscriber" or "publisher"
    private final Map<String, String> classState = new HashMap<>();
    private final Map<String, Map<String, String>> attrState = new HashMap<>();

    public FilterRuleProcessor(Element filter, boolean publisherChannel) {
        this.channelAttr = publisherChannel ? "publisher" : "subscriber";
        if (filter != null) {
            parse(filter);
        }
    }

    private void parse(Element filter) {
        for (Element fc : Xds.childrenByName(filter, "filter-class")) {
            String cls = fc.getAttribute("class-name");
            classState.put(cls.toLowerCase(), fc.getAttribute(channelAttr));
            Map<String, String> attrs = new HashMap<>();
            for (Element fa : Xds.childrenByName(fc, "filter-attr")) {
                attrs.put(fa.getAttribute("attr-name").toLowerCase(), fa.getAttribute(channelAttr));
            }
            attrState.put(cls.toLowerCase(), attrs);
        }
    }

    @Override
    public Document applyRules(Document doc, RuleDynamicContext dynamicContext) {
        Element root = doc.getDocumentElement();
        Element container = Xds.firstByName(root, "input");
        if (container == null) {
            container = Xds.firstByName(root, "output");
        }
        if (container == null) {
            return doc;
        }
        for (Element op : Xds.childElements(container)) {
            if (!isOperation(op)) {
                continue;
            }
            String cls = op.getAttribute("class-name").toLowerCase();
            String cs = classState.get(cls);
            if (cs == null || cs.equalsIgnoreCase("ignore")) {
                // class not in filter, or ignored on this channel -> drop the operation
                container.removeChild(op);
                continue;
            }
            Map<String, String> attrs = attrState.getOrDefault(cls, new HashMap<>());
            for (Element attr : new ArrayList<>(Xds.childElements(op))) {
                String an = attr.getLocalName();
                if (!an.equals("add-attr") && !an.equals("modify-attr") && !an.equals("attr")) {
                    continue;
                }
                String name = attr.getAttribute("attr-name").toLowerCase();
                String as = attrs.get(name);
                if (as == null || as.equalsIgnoreCase("ignore")) {
                    op.removeChild(attr);
                }
            }
        }
        // Re-serialize so the structural edits are reflected downstream.
        return Xds.parse(Xds.serialize(doc));
    }

    private static boolean isOperation(Element op) {
        switch (op.getLocalName()) {
            case "add":
            case "modify":
            case "delete":
            case "rename":
            case "move":
            case "instance":
                return true;
            default:
                return false;
        }
    }

    @Override
    public void setNextRule(RuleProcessor nextRule) {
        // not chained; the ChannelSimulator drives stages itself
    }
}
