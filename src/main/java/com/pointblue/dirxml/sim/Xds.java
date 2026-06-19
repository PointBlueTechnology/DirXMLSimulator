package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.driver.XmlDocument;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * XDS / DOM helpers. All parsing goes through {@link XmlDocument} so the DOM is
 * the Novell {@code com.novell.xml.dom.DocumentImpl} the engine requires, and
 * all serialization goes through it so DOM edits are re-serialized (the string
 * cache is invalidated by constructing from a Document).
 */
public final class Xds {

    private Xds() {}

    /** Parse XDS text into a Novell-DOM document. */
    public static Document parse(String xml) {
        return new XmlDocument(xml).getDocument();
    }

    /** Parse a file's XDS text into a Novell-DOM document. */
    public static Document parseFile(Path p) {
        try {
            return parse(new String(Files.readAllBytes(p), "UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read XDS from " + p + ": " + e, e);
        }
    }

    /** Serialize a document back to XDS text (re-serializes any DOM edits). */
    public static String serialize(Document doc) {
        return new XmlDocument(doc).getDocumentString();
    }

    /** Serialize a single element by wrapping it in its own document view. */
    public static String serialize(Element el) {
        return new XmlDocument(el.getOwnerDocument()).getDocumentString();
    }

    /**
     * Serialize just this element's subtree (not its whole owner document) to a
     * standalone XML string. Used to lift one policy out of a driver export.
     */
    public static String serializeElement(Element el) {
        try {
            javax.xml.transform.Transformer t =
                javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            java.io.StringWriter sw = new java.io.StringWriter();
            t.transform(new javax.xml.transform.dom.DOMSource(el),
                new javax.xml.transform.stream.StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize element <" + el.getLocalName() + ">: " + e, e);
        }
    }

    /** Deep-clone a document (for per-stage snapshots that must not alias). */
    public static Document copy(Document doc) {
        return parse(serialize(doc));
    }

    /** First descendant element with the given local name, or null. */
    public static Element firstByName(Node ctx, String localName) {
        if (ctx instanceof Element && ((Element) ctx).getLocalName().equals(localName)) {
            return (Element) ctx;
        }
        NodeList kids = ctx.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                Element found = firstByName(k, localName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Immediate child elements with the given local name. */
    public static List<Element> childrenByName(Node parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && k.getLocalName().equals(localName)) {
                out.add((Element) k);
            }
        }
        return out;
    }

    /** All descendant elements with the given local name, in document order. */
    public static List<Element> descendantsByName(Node root, String localName) {
        List<Element> out = new ArrayList<>();
        collectDescendants(root, localName, out);
        return out;
    }

    private static void collectDescendants(Node node, String localName, List<Element> out) {
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                if (localName.equals(k.getLocalName())) {
                    out.add((Element) k);
                }
                collectDescendants(k, localName, out);
            }
        }
    }

    /** Immediate child elements (any name). */
    public static List<Element> childElements(Node parent) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) k);
            }
        }
        return out;
    }

    /**
     * Text content of an element, DOM Level 2 safe (the Novell DOM returns null
     * from getTextContent()). Concatenates direct TEXT/CDATA children.
     */
    public static String text(Node node) {
        if (node == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(k.getNodeValue());
            }
        }
        return sb.toString();
    }
}
