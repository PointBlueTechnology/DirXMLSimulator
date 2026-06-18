package com.pointblue.dirxml.sim;

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
import java.util.Set;

/**
 * Loads sample directory data from an <b>LDIF</b> file into the form the
 * {@link FakeDirectory} seeds from — so you can dump a few real objects with
 * {@code ldapsearch}/ICE instead of hand-authoring {@code directory.xds}.
 *
 * <p>An LDIF entry is just a serialized LDAP read, so the same mapping the live
 * {@link LdapQueryProcessor} uses applies: attribute and class names are mapped
 * DirXML-ward via {@link SchemaModel} ({@code ldap=}), and values are normalized
 * to native XDS form via {@link LdapValueNormalizer} ({@code syn=}) — a base64
 * ({@code ::}) octet value stays base64, generalized time becomes seconds, a
 * DN-syntax value becomes slash form. LDIF line folding and {@code ::} base64 are
 * handled; operational attributes ({@code entryDN}, {@code structuralObjectClass},
 * timestamps, …) are dropped.
 *
 * <p>Optional — only used when a case sets {@code ldif=}.
 */
public final class LdifReader {

    /** LDAP operational/derived attributes that aren't real object data. */
    private static final Set<String> OPERATIONAL = Set.of(
        "dn", "objectclass", "structuralobjectclass", "entrydn", "entryflags",
        "subschemasubentry", "subordinatecount", "hassubordinates", "revision",
        "federationboundary", "localentryid", "createtimestamp", "modifytimestamp",
        "creatorsname", "modifiersname", "entryuuid");

    private final SchemaModel schema;
    private final LdapValueNormalizer normalizer;
    private final String driverDn;   // to pull <association> from dirxml-associations; may be null

    public LdifReader(SchemaModel schema, LdapValueNormalizer normalizer, String driverDn) {
        this.schema = schema == null ? SchemaModel.empty() : schema;
        this.normalizer = normalizer == null ? new LdapValueNormalizer() : normalizer;
        this.driverDn = driverDn;
    }

    /** Seed a fake directory directly from an LDIF file. */
    public void seed(FakeDirectory dir, Path ldifFile) {
        try {
            dir.loadState(toInstances(new String(Files.readAllBytes(ldifFile), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load LDIF " + ldifFile + ": " + e, e);
        }
    }

    /** Convert LDIF text to an XDS {@code <instance>} set the FakeDirectory loads. */
    public Document toInstances(String ldif) {
        Document doc = Xds.parse("<nds dtdversion=\"4.0\"><input/></nds>");
        Element input = Xds.firstByName(doc.getDocumentElement(), "input");
        for (Entry e : parse(ldif)) {
            Element instance = instanceElement(doc, e);
            if (instance != null) {
                input.appendChild(instance);
            }
        }
        return doc;
    }

    // ---- LDIF -> instance ----------------------------------------------

    private Element instanceElement(Document doc, Entry e) {
        if (e.dn == null) {
            return null;
        }
        Element instance = doc.createElement("instance");
        instance.setAttribute("class-name", schema.dirxmlClassName(structuralClass(e)));
        instance.setAttribute("src-dn", e.dn);

        String assoc = association(e);
        if (assoc != null) {
            Element a = doc.createElement("association");
            a.setAttribute("state", "associated");
            a.appendChild(doc.createTextNode(assoc));
            instance.appendChild(a);
        }

        for (Map.Entry<String, List<Object>> attr : e.attrs.entrySet()) {
            String ldapName = attr.getKey();
            if (OPERATIONAL.contains(ldapName.toLowerCase()) || ldapName.equalsIgnoreCase("dirxml-associations")) {
                continue;
            }
            Element attrEl = attrElement(doc, ldapName, attr.getValue());
            if (attrEl != null) {
                instance.appendChild(attrEl);
            }
        }
        return instance;
    }

    private Element attrElement(Document doc, String ldapName, List<Object> values) {
        SchemaModel.Attr a = schema.attr(ldapName);
        String dirxmlName = a != null ? a.name : ldapName;
        String syntax = a != null ? a.syntax : null;

        Element attrEl = doc.createElement("attr");
        attrEl.setAttribute("attr-name", dirxmlName);
        boolean any = false;
        for (Object raw : values) {
            LdapValueNormalizer.Value nv = normalizer.normalize(syntax, raw);
            if (nv.skip) {
                continue;
            }
            Element value = doc.createElement("value");
            if (nv.structured()) {
                // FakeDirectory stores string values; keep structured data as the
                // raw LDIF text rather than decomposing it for a seed.
                value.appendChild(doc.createTextNode(asString(raw)));
            } else {
                if (nv.type != null) {
                    value.setAttribute("type", nv.type);
                }
                if (nv.text != null) {
                    value.appendChild(doc.createTextNode(nv.text));
                }
            }
            attrEl.appendChild(value);
            any = true;
        }
        return any ? attrEl : null;
    }

    /** Structural class for the entry: structuralObjectClass if present, else a known objectClass. */
    private String structuralClass(Entry e) {
        List<Object> structural = e.attrs.get("structuralobjectclass");
        if (structural != null && !structural.isEmpty()) {
            return asString(structural.get(0));
        }
        List<Object> ocs = e.attrs.get("objectclass");
        String last = null;
        if (ocs != null) {
            for (Object oc : ocs) {
                String s = asString(oc);
                last = s;
                if (schema.hasClass(s)) {
                    return s;   // a class the schema recognizes
                }
            }
        }
        return last != null ? last : "";
    }

    /** {@code dirxml-associations} value matching the configured driver: {@code DN#state#value}. */
    private String association(Entry e) {
        List<Object> assocs = e.attrs.get("dirxml-associations");
        if (assocs == null) {
            return null;
        }
        for (Object o : assocs) {
            String[] parts = asString(o).split("#", 3);
            if (parts.length == 3) {
                if (driverDn == null) {
                    return parts[2];   // no driver filter — take the first
                }
                if (parts[0].trim().equalsIgnoreCase(driverDn.trim())) {
                    return parts[2];
                }
            }
        }
        return null;
    }

    // ---- LDIF parsing --------------------------------------------------

    private static final class Entry {
        String dn;
        final Map<String, List<Object>> attrs = new LinkedHashMap<>();  // key: lower(ldap name)
        void add(String name, Object value) {
            attrs.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(value);
        }
    }

    /** Parse LDIF into entries, unfolding continuation lines and decoding {@code ::} base64. */
    private List<Entry> parse(String ldif) {
        List<Entry> entries = new ArrayList<>();
        Entry current = null;
        for (String logical : unfold(ldif)) {
            if (logical.isEmpty()) {
                if (current != null) {
                    entries.add(current);
                    current = null;
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
            Object value = parseValue(logical, colon);
            if (name.equalsIgnoreCase("dn")) {
                if (current != null) {
                    entries.add(current);
                }
                current = new Entry();
                current.dn = asString(value);
            } else if (current != null) {
                current.add(name, value);
            }
        }
        if (current != null) {
            entries.add(current);
        }
        return entries;
    }

    /** Value after the name: {@code :: b64} ⇒ byte[]; {@code :< url} ⇒ skipped; else String. */
    private static Object parseValue(String line, int colon) {
        int i = colon + 1;
        if (i < line.length() && line.charAt(i) == ':') {       // ":: base64"
            String b64 = line.substring(i + 1).trim();
            try {
                return Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                return b64;
            }
        }
        if (i < line.length() && line.charAt(i) == '<') {       // ":< url" — unsupported, keep raw
            return line.substring(i + 1).trim();
        }
        return line.substring(i).trim();
    }

    /** Join LDIF continuation lines (a leading single space) into logical lines. */
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

    private static String asString(Object v) {
        if (v instanceof byte[]) {
            return new String((byte[]) v, StandardCharsets.UTF_8);
        }
        return v == null ? "" : v.toString();
    }
}
