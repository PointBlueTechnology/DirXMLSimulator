package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.XdsCommandProcessor;
import com.novell.nds.dirxml.engine.XdsQueryProcessor;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory directory that answers the queries a policy issues and absorbs the
 * commands it emits. It implements both engine seams
 * ({@link XdsQueryProcessor#query} and {@link XdsCommandProcessor#execute}) so a
 * policy doing {@code token-query}, {@code do-find-matching-object},
 * source/dest attribute reads, or writes behaves as if a real application were
 * on the other side.
 *
 * <p>State is a set of {@link Entry} objects keyed by DN. Queries are evaluated
 * against that state (class + search-attr match, scope, read-attr projection).
 * Commands (add/modify/delete/rename/move) mutate the state, so multi-step
 * scenarios (matching → merge, create-then-read) work.
 *
 * <p>Every interaction is recorded so {@link ChannelSimulator} can attribute the
 * queries/commands a given stage issued.
 */
public final class FakeDirectory implements XdsQueryProcessor, XdsCommandProcessor {

    /** A directory entry. */
    public static final class Entry {
        public String className;
        public String dn;
        public String association;
        public final Map<String, List<String>> attrs = new LinkedHashMap<>();

        Entry(String className, String dn, String association) {
            this.className = className;
            this.dn = dn;
            this.association = association;
        }

        void set(String name, List<String> values) {
            attrs.put(name, new ArrayList<>(values));
        }

        void add(String name, List<String> values) {
            attrs.computeIfAbsent(name, k -> new ArrayList<>()).addAll(values);
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> queryLog = new ArrayList<>();
    private final List<String> commandLog = new ArrayList<>();

    // ---- loading state -------------------------------------------------------

    /** Load directory state from an XDS document containing <instance>/<add> elements. */
    public FakeDirectory loadState(Document doc) {
        Element root = doc.getDocumentElement();
        Element container = Xds.firstByName(root, "input");
        Element scan = container != null ? container : root;
        for (Element el : Xds.childElements(scan)) {
            String ln = el.getLocalName();
            if (ln.equals("instance") || ln.equals("add")) {
                addEntryFromElement(el);
            }
        }
        return this;
    }

    public FakeDirectory loadStateFile(Path p) {
        return loadState(Xds.parseFile(p));
    }

    private void addEntryFromElement(Element el) {
        String cls = el.getAttribute("class-name");
        String dn = el.getAttribute("src-dn");
        if (dn.isEmpty()) {
            dn = el.getAttribute("dest-dn");
        }
        Element assocEl = firstChild(el, "association");
        String assoc = assocEl != null ? Xds.text(assocEl).trim() : null;
        Entry entry = new Entry(cls, dn, assoc);
        // <attr> (instance form) and <add-attr> (add form) both carry values.
        for (Element attr : Xds.childElements(el)) {
            String an = attr.getLocalName();
            if (an.equals("attr") || an.equals("add-attr")) {
                entry.set(attr.getAttribute("attr-name"), readValues(attr));
            }
        }
        entries.put(dn, entry);
    }

    public int size() {
        return entries.size();
    }

    public Entry get(String dn) {
        return entries.get(dn);
    }

    // ---- query seam ----------------------------------------------------------

    @Override
    public Document query(Document queryDoc) {
        queryLog.add(Xds.serialize(queryDoc));
        Element q = Xds.firstByName(queryDoc.getDocumentElement(), "query");
        if (q == null) {
            q = Xds.firstByName(queryDoc.getDocumentElement(), "query-ex");
        }
        if (q == null) {
            return wrapOutput("");
        }

        String wantClass = q.getAttribute("class-name");
        Element scEl = firstChild(q, "search-class");
        if (scEl != null && !scEl.getAttribute("class-name").isEmpty()) {
            wantClass = scEl.getAttribute("class-name");
        }
        String scope = q.getAttribute("scope");
        String destDn = q.getAttribute("dest-dn");
        String srcDn = q.getAttribute("src-dn");
        String assoc = readAssociation(q);

        List<MatchAttr> criteria = readSearchAttrs(q);
        ReadAttrs read = readReadAttrs(q);

        List<Entry> matches = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (assoc != null && !assoc.isEmpty()) {
                if (assoc.equals(e.association)) {
                    matches.add(e);
                }
                continue;
            }
            if (!destDn.isEmpty() && !destDn.equals(e.dn)) {
                continue;
            }
            if (!srcDn.isEmpty() && !srcDn.equals(e.dn)) {
                continue;
            }
            if (wantClass != null && !wantClass.isEmpty()
                    && e.className != null && !wantClass.equalsIgnoreCase(e.className)) {
                continue;
            }
            if (!matchesAll(e, criteria)) {
                continue;
            }
            matches.add(e);
        }

        StringBuilder sb = new StringBuilder();
        for (Entry e : matches) {
            sb.append(renderInstance(e, read));
        }
        return wrapOutput(sb.toString());
    }

    // ---- command seam --------------------------------------------------------

    @Override
    public Document execute(Document commandDoc) {
        commandLog.add(Xds.serialize(commandDoc));
        Element root = commandDoc.getDocumentElement();
        Element container = Xds.firstByName(root, "input");
        Element scan = container != null ? container : root;
        StringBuilder statuses = new StringBuilder();
        for (Element op : Xds.childElements(scan)) {
            switch (op.getLocalName()) {
                case "add":
                    addEntryFromElement(op);
                    statuses.append("<status level=\"success\" event-id=\"")
                            .append(esc(op.getAttribute("event-id"))).append("\">")
                            .append("<association>").append(esc(syntheticAssoc(op)))
                            .append("</association></status>");
                    break;
                case "modify":
                    applyModify(op);
                    statuses.append(successFor(op));
                    break;
                case "delete":
                    entries.remove(targetDn(op));
                    statuses.append(successFor(op));
                    break;
                case "rename":
                case "move":
                    statuses.append(successFor(op));
                    break;
                default:
                    statuses.append(successFor(op));
            }
        }
        if (statuses.length() == 0) {
            statuses.append("<status level=\"success\"/>");
        }
        return Xds.parse("<nds dtdversion=\"4.0\"><output>" + statuses + "</output></nds>");
    }

    private void applyModify(Element modify) {
        Entry e = entries.get(targetDn(modify));
        if (e == null) {
            return;
        }
        for (Element ma : Xds.childrenByName(modify, "modify-attr")) {
            String name = ma.getAttribute("attr-name");
            for (Element change : Xds.childElements(ma)) {
                switch (change.getLocalName()) {
                    case "remove-all-values":
                        e.attrs.remove(name);
                        break;
                    case "add-value":
                        e.add(name, readValues(change));
                        break;
                    case "remove-value":
                        List<String> vals = e.attrs.get(name);
                        if (vals != null) {
                            vals.removeAll(readValues(change));
                        }
                        break;
                    default:
                }
            }
        }
    }

    // ---- interaction log (drained per stage by ChannelSimulator) -------------

    public List<String> drainQueries() {
        List<String> out = new ArrayList<>(queryLog);
        queryLog.clear();
        return out;
    }

    public List<String> drainCommands() {
        List<String> out = new ArrayList<>(commandLog);
        commandLog.clear();
        return out;
    }

    // ---- helpers -------------------------------------------------------------

    private static final class MatchAttr {
        String name;
        List<String> values;
    }

    private static final class ReadAttrs {
        boolean all;
        final List<String> names = new ArrayList<>();
    }

    private List<MatchAttr> readSearchAttrs(Element q) {
        List<MatchAttr> out = new ArrayList<>();
        for (Element sa : Xds.childrenByName(q, "search-attr")) {
            MatchAttr m = new MatchAttr();
            m.name = sa.getAttribute("attr-name");
            m.values = readValues(sa);
            out.add(m);
        }
        return out;
    }

    private ReadAttrs readReadAttrs(Element q) {
        ReadAttrs r = new ReadAttrs();
        List<Element> ras = Xds.childrenByName(q, "read-attr");
        for (Element ra : ras) {
            String name = ra.getAttribute("attr-name");
            if (name.isEmpty()) {
                r.all = true; // <read-attr/> with no name => all attributes
            } else {
                r.names.add(name);
            }
        }
        if (ras.isEmpty()) {
            r.all = true; // no read-attr specified => return all (lenient default for testing)
        }
        return r;
    }

    private String readAssociation(Element q) {
        Element a = firstChild(q, "association");
        return a != null ? Xds.text(a).trim() : null;
    }

    private boolean matchesAll(Entry e, List<MatchAttr> criteria) {
        for (MatchAttr m : criteria) {
            List<String> have = e.attrs.get(m.name);
            if (have == null) {
                return false;
            }
            boolean ok = false;
            for (String want : m.values) {
                for (String h : have) {
                    if (h.equalsIgnoreCase(want)) {
                        ok = true;
                        break;
                    }
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private String renderInstance(Entry e, ReadAttrs read) {
        StringBuilder sb = new StringBuilder();
        sb.append("<instance class-name=\"").append(esc(e.className)).append("\"");
        if (e.dn != null && !e.dn.isEmpty()) {
            sb.append(" src-dn=\"").append(esc(e.dn)).append("\"");
        }
        sb.append(">");
        if (e.association != null) {
            sb.append("<association>").append(esc(e.association)).append("</association>");
        }
        for (Map.Entry<String, List<String>> a : e.attrs.entrySet()) {
            if (!read.all && !read.names.contains(a.getKey())) {
                continue;
            }
            sb.append("<attr attr-name=\"").append(esc(a.getKey())).append("\">");
            for (String v : a.getValue()) {
                sb.append("<value>").append(esc(v)).append("</value>");
            }
            sb.append("</attr>");
        }
        sb.append("</instance>");
        return sb.toString();
    }

    private Document wrapOutput(String instances) {
        return Xds.parse("<nds dtdversion=\"4.0\"><output>" + instances
            + "<status level=\"success\"/></output></nds>");
    }

    private List<String> readValues(Element parent) {
        List<String> out = new ArrayList<>();
        for (Element v : Xds.childrenByName(parent, "value")) {
            out.add(Xds.text(v));
        }
        return out;
    }

    private static Element firstChild(Element parent, String localName) {
        List<Element> kids = Xds.childrenByName(parent, localName);
        return kids.isEmpty() ? null : kids.get(0);
    }

    private String targetDn(Element op) {
        String dn = op.getAttribute("dest-dn");
        if (dn.isEmpty()) {
            dn = op.getAttribute("src-dn");
        }
        if (dn.isEmpty()) {
            Element assoc = firstChild(op, "association");
            if (assoc != null) {
                String a = Xds.text(assoc).trim();
                for (Entry e : entries.values()) {
                    if (a.equals(e.association)) {
                        return e.dn;
                    }
                }
            }
        }
        return dn;
    }

    private String syntheticAssoc(Element addOp) {
        Element assoc = firstChild(addOp, "association");
        if (assoc != null && !Xds.text(assoc).trim().isEmpty()) {
            return Xds.text(assoc).trim();
        }
        String dn = addOp.getAttribute("src-dn");
        return dn.isEmpty() ? ("assoc-" + (entries.size())) : ("assoc-" + dn.hashCode());
    }

    private String successFor(Element op) {
        String eid = op.getAttribute("event-id");
        return "<status level=\"success\"" + (eid.isEmpty() ? "" : " event-id=\"" + esc(eid) + "\"") + "/>";
    }

    private static String attr(Element e, String name, String dflt) {
        String v = e.getAttribute(name);
        return v.isEmpty() ? dflt : v;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
