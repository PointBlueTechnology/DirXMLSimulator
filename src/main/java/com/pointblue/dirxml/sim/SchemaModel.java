package com.pointblue.dirxml.sim;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The eDirectory schema from a Designer project's {@code *_schema.xml} (which an
 * export omits): attributes (name, LDAP name, syntax, single-valued) and classes
 * (with superclasses and allowed attributes). Used to validate hand-authored
 * inputs — unknown class/attribute, an attribute not valid for its class, or
 * multiple values on a single-valued attribute.
 */
public final class SchemaModel {

    public static final class Attr {
        public final String name;
        public final String ldap;
        public final String syntax;
        public final boolean singleValued;
        Attr(String name, String ldap, String syntax, boolean singleValued) {
            this.name = name;
            this.ldap = ldap;
            this.syntax = syntax;
            this.singleValued = singleValued;
        }
    }

    private static final class Klass {
        String name;
        List<String> sup = new ArrayList<>();
        Set<String> opt = new LinkedHashSet<>();
    }

    private final Map<String, Attr> attrs = new LinkedHashMap<>();      // key: lower(name/ldap)
    private final Map<String, Klass> classes = new LinkedHashMap<>();   // key: lower(name/ldap)
    private final Map<String, Set<String>> effectiveCache = new LinkedHashMap<>();

    private SchemaModel() {}

    public static SchemaModel empty() {
        return new SchemaModel();
    }

    public boolean isEmpty() {
        return attrs.isEmpty() && classes.isEmpty();
    }

    public static SchemaModel parseFile(Path schemaXml) {
        return parse(Xds.parseFile(schemaXml));
    }

    public static SchemaModel parse(Document doc) {
        SchemaModel m = new SchemaModel();
        Element root = doc.getDocumentElement();
        if (root == null) {
            return m;
        }
        for (Element e : Xds.childElements(root)) {
            if (e.getLocalName().equals("attr")) {
                Attr a = new Attr(e.getAttribute("name"), e.getAttribute("ldap"),
                    e.getAttribute("syn"), "1".equals(e.getAttribute("sngl")));
                m.put(m.attrs, a.name, a);
                m.put(m.attrs, a.ldap, a);
            } else if (e.getLocalName().equals("class")) {
                Klass k = new Klass();
                k.name = e.getAttribute("name");
                for (Element c : Xds.childElements(e)) {
                    if (c.getLocalName().equals("sup")) {
                        k.sup.addAll(splitList(Xds.text(c)));
                    } else if (c.getLocalName().equals("opt") || c.getLocalName().equals("nam")
                            || c.getLocalName().equals("mand")) {
                        k.opt.addAll(splitList(Xds.text(c)));
                    }
                }
                m.put(m.classes, k.name, k);
                m.put(m.classes, e.getAttribute("ldap"), k);
            }
        }
        return m;
    }

    public boolean hasClass(String name) {
        return name != null && classes.containsKey(name.toLowerCase());
    }

    public boolean hasAttr(String name) {
        return name != null && attrs.containsKey(name.toLowerCase());
    }

    public Attr attr(String name) {
        return name == null ? null : attrs.get(name.toLowerCase());
    }

    /** All attributes allowed on a class, resolving superclasses. Empty if class unknown. */
    public Set<String> effectiveAttrs(String className) {
        if (className == null) {
            return Set.of();
        }
        String key = className.toLowerCase();
        Set<String> cached = effectiveCache.get(key);
        if (cached != null) {
            return cached;
        }
        Set<String> acc = new LinkedHashSet<>();
        effectiveCache.put(key, acc); // guard against cycles
        Klass k = classes.get(key);
        if (k != null) {
            for (String a : k.opt) {
                acc.add(a.toLowerCase());
            }
            for (String s : k.sup) {
                if (!s.equalsIgnoreCase(k.name)) {
                    acc.addAll(effectiveAttrs(s));
                }
            }
        }
        return acc;
    }

    public boolean attrAllowedForClass(String className, String attrName) {
        return effectiveAttrs(className).contains(attrName.toLowerCase());
    }

    private <T> void put(Map<String, T> map, String key, T val) {
        if (key != null && !key.isEmpty()) {
            map.putIfAbsent(key.toLowerCase(), val);
        }
    }

    private static List<String> splitList(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : text.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Validate the classes/attributes used by an XDS document against the schema.
     * Returns human-readable warnings (empty if all clean, or if the schema is empty).
     */
    public List<String> validate(Document xds) {
        List<String> warnings = new ArrayList<>();
        if (isEmpty()) {
            return warnings;
        }
        Element root = xds.getDocumentElement();
        Element container = Xds.firstByName(root, "input");
        if (container == null) {
            container = Xds.firstByName(root, "output");
        }
        if (container == null) {
            return warnings;
        }
        Set<String> reported = new LinkedHashSet<>();
        for (Element op : Xds.childElements(container)) {
            String cls = op.getAttribute("class-name");
            if (cls.isEmpty()) {
                continue;
            }
            if (!hasClass(cls) && reported.add("class:" + cls)) {
                warnings.add("unknown class '" + cls + "' (not in schema)");
            }
            for (Element a : Xds.childElements(op)) {
                String ln = a.getLocalName();
                if (!ln.equals("add-attr") && !ln.equals("modify-attr") && !ln.equals("attr")) {
                    continue;
                }
                String an = a.getAttribute("attr-name");
                if (an.isEmpty()) {
                    continue;
                }
                if (!hasAttr(an)) {
                    if (reported.add("attr:" + an)) {
                        warnings.add("unknown attribute '" + an + "' (not in schema — typo?)");
                    }
                } else if (hasClass(cls) && !attrAllowedForClass(cls, an)
                        && reported.add("attrcls:" + cls + "/" + an)) {
                    warnings.add("attribute '" + an + "' is not valid for class '" + cls + "'");
                }
                Attr def = attr(an);
                if (def != null && def.singleValued && valueCount(a) > 1
                        && reported.add("sngl:" + an)) {
                    warnings.add("single-valued attribute '" + an + "' has multiple values");
                }
            }
        }
        return warnings;
    }

    private static int valueCount(Element attrEl) {
        int n = Xds.childrenByName(attrEl, "value").size();
        for (Element ch : Xds.childElements(attrEl)) {            // modify-attr/add-value/remove-value
            n += Xds.childrenByName(ch, "value").size();
        }
        return n;
    }
}
