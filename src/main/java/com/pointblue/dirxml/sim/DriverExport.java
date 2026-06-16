package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.gcv.GCDefinitions;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a Designer "Export Driver Configuration" file ({@code
 * <driver-configuration>}) and assembles the ordered policy chain for a channel,
 * exactly as the engine wires it.
 *
 * <p>Policies live in the object tree as {@code <rule name="…">} wrappers (each
 * holding a {@code <policy>}, {@code <style-sheet>}, or {@code <attr-name-map>})
 * under {@code <publisher>}, {@code <subscriber>}, or the driver itself. The
 * {@code <policy-linkage>} lists {@code <linkage-item>}s keyed by the standard
 * IDM {@code policy-set} id and {@code order}; resolving those in channel order
 * yields the chain.
 */
public final class DriverExport {

    // Standard IDM policy-set ids.
    private static final int SCHEMA_MAPPING = 0;
    private static final int INPUT_XFORM    = 1;
    private static final int OUTPUT_XFORM   = 2;
    private static final int SUB_EVENT      = 4;
    private static final int PUB_EVENT      = 5;
    private static final int SUB_MATCH      = 6;
    private static final int PUB_MATCH      = 7;
    private static final int SUB_CREATE     = 8;
    private static final int PUB_CREATE     = 9;
    private static final int SUB_COMMAND    = 10;
    private static final int PUB_COMMAND    = 11;
    private static final int SUB_PLACEMENT  = 12;
    private static final int PUB_PLACEMENT  = 13;

    /** Subscriber channel order (Identity Vault → application). */
    private static final int[] SUBSCRIBER_ORDER = {
        SUB_EVENT, SUB_MATCH, SUB_CREATE, SUB_PLACEMENT, SUB_COMMAND, SCHEMA_MAPPING, OUTPUT_XFORM
    };
    /** Publisher channel order (application → Identity Vault). */
    private static final int[] PUBLISHER_ORDER = {
        INPUT_XFORM, SCHEMA_MAPPING, PUB_EVENT, PUB_MATCH, PUB_CREATE, PUB_PLACEMENT, PUB_COMMAND
    };

    private static final Map<Integer, String> SET_NAMES = new TreeMap<>();
    static {
        SET_NAMES.put(SCHEMA_MAPPING, "schema-mapping");
        SET_NAMES.put(INPUT_XFORM, "input-transform");
        SET_NAMES.put(OUTPUT_XFORM, "output-transform");
        SET_NAMES.put(SUB_EVENT, "subscriber-event");
        SET_NAMES.put(PUB_EVENT, "publisher-event");
        SET_NAMES.put(SUB_MATCH, "subscriber-matching");
        SET_NAMES.put(PUB_MATCH, "publisher-matching");
        SET_NAMES.put(SUB_CREATE, "subscriber-create");
        SET_NAMES.put(PUB_CREATE, "publisher-create");
        SET_NAMES.put(SUB_COMMAND, "subscriber-command");
        SET_NAMES.put(PUB_COMMAND, "publisher-command");
        SET_NAMES.put(SUB_PLACEMENT, "subscriber-placement");
        SET_NAMES.put(PUB_PLACEMENT, "publisher-placement");
    }

    private static final class Linkage {
        int set;
        int order;
        String channel; // publisher | subscriber | driver
        String name;
    }

    private final String driverName;
    private Element root;
    /** (channel, rule-name) -> content element (policy / style-sheet / attr-name-map). */
    private final Map<String, Element> rulesByKey = new LinkedHashMap<>();
    /** policy-set id -> ordered linkages. */
    private final Map<Integer, List<Linkage>> linkagesBySet = new TreeMap<>();

    private DriverExport(String driverName) {
        this.driverName = driverName;
    }

    public String driverName() {
        return driverName;
    }

    public static DriverExport load(Path file) {
        return load(Xds.parseFile(file));
    }

    public static DriverExport load(Document doc) {
        Element root = doc.getDocumentElement(); // <driver-configuration>
        DriverExport ex = new DriverExport(root.getAttribute("name"));
        ex.root = root;
        ex.indexRules(root, "driver");
        ex.readLinkage(root);
        return ex;
    }

    /**
     * Build the driver's merged GCV definitions from every
     * {@code <configuration-values>} block in the export (driver options,
     * publisher/subscriber options, global engine values, and the
     * {@code <global-config-values>} GCVs policies read via
     * {@code token-global-variable}). Blocks that fail to parse are skipped.
     */
    public GCDefinitions gcvDefinitions() {
        GCDefinitions merged = new GCDefinitions();
        for (Element cv : allDescendants(root, "configuration-values")) {
            // Only blocks that actually define values are worth merging.
            if (Xds.firstByName(cv, "definition") == null) {
                continue;
            }
            try {
                // construct(Node) looks for a <configuration-values> child, so pass the parent.
                merged.merge(GCDefinitions.construct(cv.getParentNode()));
            } catch (Throwable t) {
                System.err.println("warning: skipping a <configuration-values> block: " + t);
            }
        }
        return merged;
    }

    private static List<Element> allDescendants(Node ctx, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList kids = ctx.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                if (localName.equals(k.getLocalName())) {
                    out.add((Element) k);
                }
                out.addAll(allDescendants(k, localName));
            }
        }
        return out;
    }

    // ---- chain assembly ------------------------------------------------------

    public List<PolicyStage> subscriberChain(EngineContext ctx) {
        return chain(ctx, SUBSCRIBER_ORDER);
    }

    public List<PolicyStage> publisherChain(EngineContext ctx) {
        return chain(ctx, PUBLISHER_ORDER);
    }

    private List<PolicyStage> chain(EngineContext ctx, int[] setOrder) {
        List<PolicyStage> stages = new ArrayList<>();
        for (int set : setOrder) {
            List<Linkage> links = linkagesBySet.get(set);
            if (links == null) {
                continue;
            }
            for (Linkage l : links) {
                Element content = resolve(l);
                if (content == null) {
                    System.err.println("warning: linkage '" + l.name + "' (set " + set
                        + "/" + SET_NAMES.get(set) + ", channel " + l.channel
                        + ") resolved to no policy content; skipping");
                    continue;
                }
                String stageName = SET_NAMES.get(set) + ":" + l.name;
                stages.add(PolicyStage.fromElement(stageName, content, ctx));
            }
        }
        return stages;
    }

    private Element resolve(Linkage l) {
        // Schema mapping / input / output live at driver scope even though their
        // linkage may be channel-agnostic; try the named channel then driver.
        Element e = rulesByKey.get(key(l.channel, l.name));
        if (e == null) {
            e = rulesByKey.get(key("driver", l.name));
        }
        if (e == null) {
            e = rulesByKey.get(key("publisher", l.name));
        }
        if (e == null) {
            e = rulesByKey.get(key("subscriber", l.name));
        }
        return e;
    }

    // ---- indexing ------------------------------------------------------------

    private void indexRules(Node node, String channel) {
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) k;
            String ln = el.getLocalName();
            String childChannel = channel;
            if (ln.equals("publisher")) {
                childChannel = "publisher";
            } else if (ln.equals("subscriber")) {
                childChannel = "subscriber";
            } else if (ln.equals("rule")) {
                Element content = policyContent(el);
                if (content != null) {
                    rulesByKey.put(key(channel, el.getAttribute("name")), content);
                }
                continue; // rules don't nest rules
            }
            indexRules(el, childChannel);
        }
    }

    /** First policy-bearing child of a rule: policy / style-sheet / attr-name-map. */
    private static Element policyContent(Element rule) {
        for (Element c : Xds.childElements(rule)) {
            switch (c.getLocalName()) {
                case "policy":
                case "style-sheet":
                case "attr-name-map":
                    return c;
                default:
            }
        }
        return null;
    }

    private void readLinkage(Element root) {
        Element linkage = Xds.firstByName(root, "policy-linkage");
        if (linkage == null) {
            return;
        }
        for (Element item : Xds.childrenByName(linkage, "linkage-item")) {
            Linkage l = new Linkage();
            l.set = parseInt(item.getAttribute("policy-set"), -1);
            l.order = parseInt(item.getAttribute("order"), 0);
            parseDn(item.getAttribute("dn"), l);
            linkagesBySet.computeIfAbsent(l.set, k -> new ArrayList<>()).add(l);
        }
        for (List<Linkage> list : linkagesBySet.values()) {
            list.sort((a, b) -> Integer.compare(a.order, b.order));
        }
    }

    /** dn like "cn=Name,cn=Publisher,cn=Driver,..." -> name + channel. */
    private static void parseDn(String dn, Linkage l) {
        String[] parts = dn.split(",");
        l.name = parts.length > 0 ? stripCn(parts[0]) : "";
        l.channel = "driver";
        if (parts.length > 1) {
            String second = stripCn(parts[1]);
            if (second.equalsIgnoreCase("Publisher")) {
                l.channel = "publisher";
            } else if (second.equalsIgnoreCase("Subscriber")) {
                l.channel = "subscriber";
            }
        }
    }

    private static String stripCn(String comp) {
        String s = comp.trim();
        int eq = s.indexOf('=');
        return eq >= 0 ? s.substring(eq + 1) : s;
    }

    private static String key(String channel, String name) {
        return channel + " " + name;
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }
}
