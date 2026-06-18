package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.XdsQueryProcessor;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Answers a policy's (or a shim's) {@code <query>} from a live eDirectory read
 * over LDAP, returning XDS in the form the engine's native query seam would —
 * names mapped via {@link SchemaModel} ({@code ldap=}) and values normalized via
 * {@link LdapValueNormalizer} ({@code syn=}). This is the schema-driven
 * generalization of ClaimsCenter's hand-mapped {@code LDAPQueryProcessor}.
 *
 * <p>Implements the engine seam {@link XdsQueryProcessor} so it can stand in for
 * {@link FakeDirectory} as the query source. The LDAP search itself is behind the
 * {@link Search} interface so the XDS↔LDAP mapping and response building are
 * unit-testable without a directory; the live JNDI implementation
 * ({@code JndiLdapSearch}) plugs in only when a case configures {@code ldap=}.
 *
 * <p>Optional collaborator — never instantiated unless a case opts into live LDAP.
 * See {@code docs/shim-testing-design.md}.
 */
public final class LdapQueryProcessor implements XdsQueryProcessor {

    public static final int SCOPE_BASE = 0;
    public static final int SCOPE_ONE_LEVEL = 1;
    public static final int SCOPE_SUBTREE = 2;

    /** A directory search abstracted from JNDI so the mapping is testable. */
    public interface Search {
        List<Entry> search(Request request);
    }

    public static final class Request {
        public final String base;
        public final String filter;
        /** LDAP attribute names to return; empty ⇒ all. */
        public final List<String> attrs;
        public final int scope;
        public Request(String base, String filter, List<String> attrs, int scope) {
            this.base = base;
            this.filter = filter;
            this.attrs = attrs;
            this.scope = scope;
        }
    }

    /** One returned entry: its DN and LDAP-named attributes (values {@code String} or {@code byte[]}). */
    public static final class Entry {
        public final String dn;
        public final Map<String, List<Object>> attrs;
        public Entry(String dn, Map<String, List<Object>> attrs) {
            this.dn = dn;
            this.attrs = attrs;
        }
    }

    private final Search search;
    private final SchemaModel schema;
    private final LdapValueNormalizer normalizer;
    private final String defaultBase;
    private final String assocFilterPrefix;
    private final String assocAttr;

    public LdapQueryProcessor(Search search, SchemaModel schema, LdapValueNormalizer normalizer,
                              String defaultBase, String assocFilterPrefix) {
        this.search = search;
        this.schema = schema == null ? SchemaModel.empty() : schema;
        this.normalizer = normalizer == null ? new LdapValueNormalizer() : normalizer;
        this.defaultBase = defaultBase == null ? "" : defaultBase;
        this.assocFilterPrefix = assocFilterPrefix == null ? "" : assocFilterPrefix;
        this.assocAttr = "DirXML-Associations";
    }

    @Override
    public Document query(Document queryDoc) {
        Element query = Xds.firstByName(queryDoc.getDocumentElement(), "query");
        if (query == null) {
            return response(List.of(), null);
        }
        String className = query.getAttribute("class-name");
        Request req = buildRequest(query, className);
        List<Entry> entries;
        try {
            entries = search.search(req);
        } catch (RuntimeException e) {
            return errorResponse(e.getMessage());
        }
        return response(entries, className);
    }

    /** Visible for testing: the LDAP request an XDS query maps to. */
    Request buildRequest(Element query, String className) {
        String ldapClass = schema.ldapClassName(className);
        String assoc = childText(query, "association");
        StringBuilder filter = new StringBuilder();
        String objClass = (ldapClass == null || ldapClass.isEmpty()) ? "*" : ldapClass;

        if (assoc != null && !assoc.isEmpty()) {
            filter.append("(&(objectClass=").append(objClass).append(")(")
                  .append(assocAttr).append('=').append(assocFilterPrefix).append(assoc).append("))");
        } else {
            List<Element> searchAttrs = Xds.childrenByName(query, "search-attr");
            if (searchAttrs.isEmpty()) {
                filter.append("(objectClass=").append(objClass).append(')');
            } else {
                filter.append("(&(objectClass=").append(objClass).append(')');
                for (Element sa : searchAttrs) {
                    String ldapName = ldapAttrName(sa.getAttribute("attr-name"));
                    filter.append('(').append(ldapName).append('=')
                          .append(childText(sa, "value")).append(')');
                }
                filter.append(')');
            }
        }

        List<String> attrs = new ArrayList<>();
        for (Element ra : Xds.childrenByName(query, "read-attr")) {
            String an = ra.getAttribute("attr-name");
            if (!an.isEmpty()) {
                attrs.add(ldapAttrName(an));
            }
        }
        if (!attrs.isEmpty()) {
            attrs.add(assocAttr);  // always available to populate <association>
        }
        return new Request(defaultBase, filter.toString(), attrs, scopeOf(query.getAttribute("scope")));
    }

    private static int scopeOf(String scope) {
        if ("entry".equalsIgnoreCase(scope)) {
            return SCOPE_BASE;
        }
        if ("subordinates".equalsIgnoreCase(scope)) {
            return SCOPE_ONE_LEVEL;
        }
        return SCOPE_SUBTREE;
    }

    private String ldapAttrName(String dirxmlName) {
        SchemaModel.Attr a = schema.attr(dirxmlName);
        return (a != null && a.ldap != null && !a.ldap.isEmpty()) ? a.ldap : dirxmlName;
    }

    // ---- response building ---------------------------------------------

    private Document response(List<Entry> entries, String className) {
        Document doc = Xds.parse(
            "<nds dtdversion=\"4.0\" ndsversion=\"8.x\"><output/></nds>");
        Element output = Xds.firstByName(doc.getDocumentElement(), "output");
        for (Entry e : entries) {
            output.appendChild(instanceElement(doc, e, className));
        }
        Element status = doc.createElement("status");
        status.setAttribute("level", "success");
        output.appendChild(status);
        return doc;
    }

    private Element instanceElement(Document doc, Entry e, String className) {
        Element instance = doc.createElement("instance");
        if (className != null && !className.isEmpty()) {
            instance.setAttribute("class-name", className);
        }
        if (e.dn != null && !e.dn.isEmpty()) {
            instance.setAttribute("src-dn", e.dn);
        }
        // association first, from the DirXML-Associations attribute if present
        List<Object> assocVals = e.attrs.get(assocAttr);
        if (assocVals != null && !assocVals.isEmpty()) {
            Element assoc = doc.createElement("association");
            assoc.setAttribute("state", "associated");
            assoc.appendChild(doc.createTextNode(String.valueOf(assocVals.get(0))));
            instance.appendChild(assoc);
        }
        for (Map.Entry<String, List<Object>> attr : e.attrs.entrySet()) {
            if (assocAttr.equals(attr.getKey())) {
                continue;
            }
            Element attrEl = attrElement(doc, attr.getKey(), attr.getValue());
            if (attrEl != null) {
                instance.appendChild(attrEl);
            }
        }
        return instance;
    }

    private Element attrElement(Document doc, String ldapName, List<Object> values) {
        SchemaModel.Attr a = schema.attr(ldapName);
        String dirxmlName = (a != null) ? a.name : ldapName;
        String syntax = (a != null) ? a.syntax : null;

        Element attrEl = doc.createElement("attr");
        attrEl.setAttribute("attr-name", dirxmlName);
        boolean any = false;
        for (Object v : values) {
            LdapValueNormalizer.Value nv = normalizer.normalize(syntax, v);
            if (nv.skip) {
                continue;
            }
            attrEl.appendChild(valueElement(doc, nv));
            any = true;
        }
        return any ? attrEl : null;
    }

    private static Element valueElement(Document doc, LdapValueNormalizer.Value nv) {
        Element value = doc.createElement("value");
        if (nv.type != null) {
            value.setAttribute("type", nv.type);
        }
        if (nv.structured()) {
            for (LdapValueNormalizer.Component c : nv.components) {
                Element comp = doc.createElement("component");
                comp.setAttribute("name", c.name);
                comp.appendChild(doc.createTextNode(c.text == null ? "" : c.text));
                value.appendChild(comp);
            }
        } else if (nv.text != null) {
            value.appendChild(doc.createTextNode(nv.text));
        }
        return value;
    }

    private Document errorResponse(String message) {
        Document doc = Xds.parse(
            "<nds dtdversion=\"4.0\" ndsversion=\"8.x\"><output/></nds>");
        Element output = Xds.firstByName(doc.getDocumentElement(), "output");
        Element status = doc.createElement("status");
        status.setAttribute("level", "error");
        status.appendChild(doc.createTextNode(message == null ? "LDAP query failed" : message));
        output.appendChild(status);
        return doc;
    }

    private static String childText(Element parent, String localName) {
        Element c = Xds.firstByName(parent, localName);
        return c == null ? null : Xds.text(c);
    }
}
