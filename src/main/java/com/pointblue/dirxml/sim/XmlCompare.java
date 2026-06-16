package com.pointblue.dirxml.sim;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical XDS comparison for golden assertions. Normalizes the document into a
 * stable text form — elements with attributes sorted by name, inter-element
 * whitespace dropped — so a golden match doesn't depend on attribute order or
 * formatting. Reports the first divergence.
 */
public final class XmlCompare {

    private XmlCompare() {}

    public static final class Diff {
        public final boolean equal;
        public final String message;
        Diff(boolean equal, String message) {
            this.equal = equal;
            this.message = message;
        }
    }

    /** Normalize to a stable form: attributes sorted by name, no inter-element whitespace. */
    public static String canonical(String xml) {
        StringBuilder sb = new StringBuilder();
        Document doc = Xds.parse(xml);
        if (doc.getDocumentElement() != null) {
            writeCanonical(doc.getDocumentElement(), sb);
        }
        return sb.toString();
    }

    private static void writeCanonical(Element el, StringBuilder sb) {
        sb.append('<').append(el.getNodeName());
        NamedNodeMap attrs = el.getAttributes();
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            pairs.add(a.getNodeName() + "=\"" + a.getNodeValue() + "\"");
        }
        pairs.sort(String::compareTo);
        for (String p : pairs) {
            sb.append(' ').append(p);
        }
        sb.append('>');
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                writeCanonical((Element) k, sb);
            } else if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) {
                String t = k.getNodeValue();
                if (t != null && !t.trim().isEmpty()) {
                    sb.append(t.trim());
                }
            }
        }
        sb.append("</").append(el.getNodeName()).append('>');
    }

    public static Diff compare(String expected, String actual) {
        String e = canonical(expected);
        String a = canonical(actual);
        if (e.equals(a)) {
            return new Diff(true, "match");
        }
        int i = 0;
        int min = Math.min(e.length(), a.length());
        while (i < min && e.charAt(i) == a.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 40);
        String eCtx = e.substring(from, Math.min(e.length(), i + 40));
        String aCtx = a.substring(from, Math.min(a.length(), i + 40));
        return new Diff(false,
            "first difference at offset " + i + ":\n"
            + "  expected: …" + eCtx + "…\n"
            + "  actual:   …" + aCtx + "…");
    }
}
