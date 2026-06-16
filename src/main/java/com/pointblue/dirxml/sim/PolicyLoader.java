package com.pointblue.dirxml.sim;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a policy element from a file or string, tolerating the several shapes a
 * DirXML policy can arrive in:
 *
 * <ul>
 *   <li>a bare {@code <policy>} (DirXML Script) — e.g. Designer's per-policy
 *       {@code *_contents.xml}, or a policy exported on its own;</li>
 *   <li>a bare {@code <style-sheet>} (XSLT policy);</li>
 *   <li>a driver-export object where the policy is embedded — the first
 *       {@code <policy>} / {@code <style-sheet>} descendant is extracted.</li>
 * </ul>
 *
 * Reading the ordered policy <em>chain</em> from a full driver export (channel
 * linkage, schema map, filter) is layered on top of this in DriverExport.
 */
public final class PolicyLoader {

    private PolicyLoader() {}

    /** Recognized policy root local names, in extraction-preference order. */
    private static final String[] POLICY_ROOTS = {"policy", "style-sheet"};

    public static Element load(Path file) {
        try {
            return load(new String(Files.readAllBytes(file), "UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load policy from " + file + ": " + e, e);
        }
    }

    public static Element load(String xml) {
        Document doc = Xds.parse(xml);
        Element root = doc.getDocumentElement();
        if (root == null) {
            throw new IllegalArgumentException("Empty policy document");
        }
        for (String name : POLICY_ROOTS) {
            if (name.equals(root.getLocalName())) {
                return root;
            }
        }
        // Wrapped form (e.g. DirXML-Policy object / XmlData) — find the policy inside.
        for (String name : POLICY_ROOTS) {
            Element found = firstDescendant(root, name);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalArgumentException(
            "No <policy> or <style-sheet> found; root was <" + root.getLocalName() + ">");
    }

    private static Element firstDescendant(Node ctx, String localName) {
        NodeList kids = ctx.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                if (localName.equals(k.getLocalName())) {
                    return (Element) k;
                }
                Element deeper = firstDescendant(k, localName);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }
}
