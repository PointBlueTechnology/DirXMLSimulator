package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.gcv.GCDefinitions;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Assembles a driver's channel chain from an <b>LDIF/LDAP export of the live
 * Identity Vault</b> — a third config source alongside {@link DriverExport} (a
 * Designer config file) and {@link DesignerProject} (a project on disk).
 *
 * <p>In eDir the driver config is an object subtree. A {@code DirXML-Driver}
 * entry's multi-valued {@code DirXML-Policies} attribute lists every policy as
 * {@code <policyDN>#<order>#<policySetId>} — and the set ids are the standard IDM
 * ones, identical to {@link DriverExport}. Each referenced {@code DirXML-Rule} /
 * {@code DirXML-StyleSheet} entry holds its policy XML in {@code XmlData}; the
 * shim params live in {@code DirXML-JavaModule}/{@code DirXML-ShimConfigInfo}/
 * {@code DirXML-ConfigValues}, exactly as in a Designer driver object.
 *
 * <p>The export must include those DirXML attributes (a plain {@code ldapsearch *}
 * omits them) — request them explicitly, e.g.
 * {@code ldapsearch -b cn=<DriverSet>,o=system -s sub "(objectclass=*)" '*' XmlData
 * DirXML-Policies DirXML-ShimConfigInfo DirXML-ConfigValues DirXML-JavaModule
 * DirXML-DriverFilter}.
 */
public final class LdifDriverSource {

    // Standard IDM policy-set ids — must match DriverExport.
    private static final int SCHEMA_MAPPING = 0;
    private static final int INPUT_XFORM = 1;
    private static final int OUTPUT_XFORM = 2;
    private static final int SUB_EVENT = 4;
    private static final int PUB_EVENT = 5;
    private static final int SUB_MATCH = 6;
    private static final int PUB_MATCH = 7;
    private static final int SUB_CREATE = 8;
    private static final int PUB_CREATE = 9;
    private static final int SUB_COMMAND = 10;
    private static final int PUB_COMMAND = 11;
    private static final int SUB_PLACEMENT = 12;
    private static final int PUB_PLACEMENT = 13;

    private static final int[] SUBSCRIBER_ORDER = {
        SUB_EVENT, SUB_MATCH, SUB_CREATE, SUB_PLACEMENT, SUB_COMMAND, SCHEMA_MAPPING, OUTPUT_XFORM
    };
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

    /** One directory entry: its DN and attributes (values as text; XML blobs decoded). */
    public static final class Entry {
        final String dn;
        final Map<String, List<String>> attrs;
        public Entry(String dn, Map<String, List<String>> attrs) {
            this.dn = dn;
            this.attrs = attrs;
        }
        String first(String name) {
            List<String> v = attrs.get(name.toLowerCase());
            return (v == null || v.isEmpty()) ? null : v.get(0);
        }
        List<String> all(String name) {
            return attrs.getOrDefault(name.toLowerCase(), List.of());
        }
        boolean hasClass(String oc) {
            for (String v : all("objectclass")) {
                if (v.equalsIgnoreCase(oc)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Map<String, Entry> byDn = new LinkedHashMap<>();         // key: lower(dn)
    private final Map<String, Entry> driversByName = new LinkedHashMap<>(); // key: cn

    private LdifDriverSource() {}

    public static LdifDriverSource load(Path ldif) {
        try {
            return parse(new String(Files.readAllBytes(ldif), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read driver LDIF " + ldif + ": " + e, e);
        }
    }

    public static LdifDriverSource parse(String ldif) {
        return fromEntries(parseEntries(ldif));
    }

    /**
     * Build from already-parsed directory entries — used by the live-LDAP path
     * ({@link JndiLdapSearch#readDriverConfig}) so the same chain-assembly logic
     * serves both an LDIF file and a live read of the DriverSet subtree.
     */
    public static LdifDriverSource fromEntries(List<Entry> entries) {
        LdifDriverSource s = new LdifDriverSource();
        for (Entry e : entries) {
            s.byDn.put(e.dn.toLowerCase(), e);
            if (e.hasClass("DirXML-Driver")) {
                s.driversByName.put(rdnValue(e.dn), e);
            }
        }
        return s;
    }

    public List<String> driverNames() {
        return new ArrayList<>(driversByName.keySet());
    }

    public List<PolicyStage> subscriberChain(String driver, EngineContext ctx) {
        return chain(driver, ctx, SUBSCRIBER_ORDER);
    }

    public List<PolicyStage> publisherChain(String driver, EngineContext ctx) {
        return chain(driver, ctx, PUBLISHER_ORDER);
    }

    private List<PolicyStage> chain(String driverName, EngineContext ctx, int[] setOrder) {
        Entry driver = driver(driverName);
        // setId -> ordered list of (order, policyDN)
        Map<Integer, List<long[]>> bySet = new TreeMap<>();   // value: {order, indexIntoDns}
        List<String> dns = new ArrayList<>();
        for (String link : driver.all("DirXML-Policies")) {
            int h2 = link.lastIndexOf('#');
            int h1 = link.lastIndexOf('#', h2 - 1);
            if (h1 < 0 || h2 < 0) {
                continue;
            }
            String policyDn = link.substring(0, h1);
            int order = parseInt(link.substring(h1 + 1, h2));
            int setId = parseInt(link.substring(h2 + 1));
            bySet.computeIfAbsent(setId, k -> new ArrayList<>())
                 .add(new long[]{order, dns.size()});
            dns.add(policyDn);
        }
        List<PolicyStage> stages = new ArrayList<>();
        for (int set : setOrder) {
            List<long[]> links = bySet.get(set);
            if (links == null) {
                continue;
            }
            links.sort((a, b) -> Long.compare(a[0], b[0]));
            for (long[] l : links) {
                String policyDn = dns.get((int) l[1]);
                Element content = policyContent(policyDn);
                if (content == null) {
                    System.err.println("warning: policy '" + policyDn + "' (set " + set + "/"
                        + SET_NAMES.get(set) + ") not found or has no XmlData; skipping");
                    continue;
                }
                try {
                    stages.add(PolicyStage.fromElement(
                        SET_NAMES.get(set) + ":" + rdnValue(policyDn), content, ctx));
                } catch (Throwable t) {
                    // A policy whose content the engine rejects at build time (an
                    // unresolved map-table/resource reference, an XPath it won't
                    // compile) shouldn't kill the whole chain — skip it and warn.
                    System.err.println("warning: skipping policy '" + rdnValue(policyDn)
                        + "' (" + SET_NAMES.get(set) + "): " + rootCause(t));
                }
            }
        }
        return stages;
    }

    /** Parse a policy object's {@code XmlData} into its content element. */
    private Element policyContent(String policyDn) {
        Entry e = byDn.get(policyDn.toLowerCase());
        if (e == null) {
            return null;
        }
        String xml = e.first("XmlData");
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            return Xds.parse(xml).getDocumentElement();
        } catch (Exception ex) {
            System.err.println("warning: could not parse XmlData for " + policyDn + ": " + ex);
            return null;
        }
    }

    /** The driver's filter ({@code DirXML-DriverFilter} XML), or null. */
    public Element filter(String driver) {
        String xml = driver(driver).first("DirXML-DriverFilter");
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            Element root = Xds.parse(xml).getDocumentElement();
            return "filter".equals(root.getLocalName()) ? root : Xds.firstByName(root, "filter");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * GCVs: the resolved {@code DirXML-ConfigValues} from the DriverSet (base) and
     * the driver (override).
     */
    public GCDefinitions gcvDefinitions(String driverName) {
        GCDefinitions merged = new GCDefinitions();
        Entry driver = driver(driverName);
        Entry driverSet = parentOf(driver.dn);
        if (driverSet != null) {
            mergeConfigValues(merged, driverSet);
        }
        mergeConfigValues(merged, driver);
        return merged;
    }

    private void mergeConfigValues(GCDefinitions merged, Entry e) {
        for (String cv : e.all("DirXML-ConfigValues")) {
            if (cv == null || cv.isBlank()) {
                continue;
            }
            try {
                // value root is <configuration-values>; construct(Document) finds it.
                merged.merge(GCDefinitions.construct(Xds.parse(cv)));
            } catch (Throwable t) {
                System.err.println("warning: skipping a DirXML-ConfigValues block: " + t);
            }
        }
    }

    /**
     * The shim init parameters defined on the driver object: class
     * ({@code DirXML-JavaModule}), auth ({@code DirXML-ShimAuthServer}/{@code -AuthID}),
     * and the option blocks from the {@code DirXML-ShimConfigInfo} {@code <driver-config>}.
     */
    public ShimConfig shimConfig(String driverName) {
        Entry driver = driver(driverName);
        Element drv = null;
        Element sub = null;
        Element pub = null;
        String configInfo = driver.first("DirXML-ShimConfigInfo");
        if (configInfo != null && !configInfo.isBlank()) {
            try {
                Element dc = Xds.parse(configInfo).getDocumentElement();
                drv = Xds.firstByName(dc, "driver-options");
                sub = Xds.firstByName(dc, "subscriber-options");
                pub = Xds.firstByName(dc, "publisher-options");
            } catch (Exception e) {
                System.err.println("warning: could not parse DirXML-ShimConfigInfo for "
                    + driverName + ": " + e);
            }
        }
        return new ShimConfig(driver.first("DirXML-JavaModule"), driver.dn,
            driver.first("DirXML-ShimAuthServer"), driver.first("DirXML-ShimAuthID"),
            drv, sub, pub);
    }

    /** ECMAScript resources ({@code DirXML-Resource} objects whose XmlData defines functions). */
    public List<String> ecmaScriptSources() {
        List<String> out = new ArrayList<>();
        for (Entry e : byDn.values()) {
            if (!e.hasClass("DirXML-Resource")) {
                continue;
            }
            String xml = e.first("XmlData");
            if (xml == null) {
                continue;
            }
            try {
                for (Element content : Xds.childrenByName(Xds.parse(xml).getDocumentElement(), "content")) {
                    String js = Xds.text(content);
                    if (js.contains("function ")) {
                        out.add(js);
                    }
                }
            } catch (Exception ignore) {
                // not an ECMAScript resource
            }
        }
        return out;
    }

    /** Mapping tables from DirXML-Resource entries (cn -&gt; &lt;mapping-table&gt; XML). */
    public java.util.Map<String, String> mappingTables() {
        java.util.Map<String, String> out = new LinkedHashMap<>();
        for (Entry e : byDn.values()) {
            if (!e.hasClass("DirXML-Resource")) {
                continue;
            }
            String xml = e.first("XmlData");
            if (xml == null || !xml.contains("<mapping-table")) {
                continue;
            }
            try {
                Element root = Xds.parse(xml).getDocumentElement();
                Element table = "mapping-table".equals(root.getLocalName())
                    ? root : Xds.descendantsByName(root, "mapping-table").stream().findFirst().orElse(null);
                if (table != null) {
                    out.put(rdnValue(e.dn), Xds.serializeElement(table));
                }
            } catch (Exception ignore) {
                // not a parseable mapping-table resource
            }
        }
        return out;
    }

    // ---- helpers --------------------------------------------------------

    private Entry driver(String name) {
        Entry d = driversByName.get(name);
        if (d == null) {
            throw new IllegalArgumentException(
                "No driver '" + name + "' in LDIF; have: " + driverNames());
        }
        return d;
    }

    private Entry parentOf(String dn) {
        int comma = dn.indexOf(',');
        return comma < 0 ? null : byDn.get(dn.substring(comma + 1).toLowerCase());
    }

    /** First RDN value: {@code cn=Foo,cn=Bar,...} -> {@code Foo}. */
    private static String rdnValue(String dn) {
        String first = dn.split(",", 2)[0].trim();
        int eq = first.indexOf('=');
        return eq >= 0 ? first.substring(eq + 1) : first;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }

    // ---- LDIF parsing (text values; base64 decoded to UTF-8) ------------

    private static List<Entry> parseEntries(String ldif) {
        List<Entry> entries = new ArrayList<>();
        String dn = null;
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        for (String logical : unfold(ldif)) {
            if (logical.isEmpty()) {
                if (dn != null) {
                    entries.add(new Entry(dn, attrs));
                    dn = null;
                    attrs = new LinkedHashMap<>();
                }
                continue;
            }
            if (logical.startsWith("#") || logical.regionMatches(true, 0, "version:", 0, 8)) {
                continue;
            }
            int colon = logical.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = logical.substring(0, colon);
            String value = valueAt(logical, colon);
            if (name.equalsIgnoreCase("dn")) {
                if (dn != null) {
                    entries.add(new Entry(dn, attrs));
                    attrs = new LinkedHashMap<>();
                }
                dn = value;
            } else if (dn != null) {
                attrs.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(value);
            }
        }
        if (dn != null) {
            entries.add(new Entry(dn, attrs));
        }
        return entries;
    }

    private static String valueAt(String line, int colon) {
        int i = colon + 1;
        if (i < line.length() && line.charAt(i) == ':') {   // base64
            try {
                return new String(Base64.getDecoder().decode(line.substring(i + 1).trim()),
                    StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return line.substring(i + 1).trim();
            }
        }
        return line.substring(i).trim();
    }

    private static List<String> unfold(String ldif) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String raw : ldif.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (raw.startsWith(" ")) {
                if (cur != null) {
                    cur.append(raw, 1, raw.length());
                }
            } else {
                if (cur != null) {
                    out.add(cur.toString());
                }
                cur = new StringBuilder(raw);
            }
        }
        if (cur != null) {
            out.add(cur.toString());
        }
        return out;
    }
}
