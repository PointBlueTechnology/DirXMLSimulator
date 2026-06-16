package com.pointblue.dirxml.sim;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DirXML policies can bind a namespace prefix to a Java class and call its static
 * methods as XPath extension functions, e.g.
 *
 * <pre>{@code  <policy xmlns:m="http://www.novell.com/nxsl/java/java.lang.Math">
 *    ... <token-xpath expression="m:max(3,7)"/> ...}</pre>
 *
 * These work in the harness whenever the class is on the classpath. When it
 * isn't, the engine reports a vague "function not found"; this helper detects the
 * real cause up front so the harness can report "class X is not on the classpath".
 */
public final class JavaExtensions {

    static final String JAVA_NS = "http://www.novell.com/nxsl/java/";

    private JavaExtensions() {}

    /** Fully-qualified class names bound via {@code nxsl/java/} namespaces in the policy. */
    public static List<String> referencedClasses(Element policy) {
        Set<String> classes = new LinkedHashSet<>();
        collect(policy, classes);
        return new ArrayList<>(classes);
    }

    /** Of the referenced Java extension classes, those NOT loadable on the classpath. */
    public static List<String> missingClasses(Element policy) {
        List<String> missing = new ArrayList<>();
        for (String cn : referencedClasses(policy)) {
            try {
                Class.forName(cn, false, JavaExtensions.class.getClassLoader());
            } catch (Throwable t) {
                missing.add(cn);
            }
        }
        return missing;
    }

    private static void collect(Element el, Set<String> out) {
        NamedNodeMap attrs = el.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                String n = a.getNodeName();
                String v = a.getNodeValue();
                // namespace declarations: xmlns:prefix="http://www.novell.com/nxsl/java/<class>"
                if (v != null && v.startsWith(JAVA_NS)
                        && (n != null && (n.startsWith("xmlns:") || n.equals("xmlns")))) {
                    String className = v.substring(JAVA_NS.length()).trim();
                    if (!className.isEmpty()) {
                        out.add(className);
                    }
                }
            }
        }
        for (Element c : Xds.childElements(el)) {
            collect(c, out);
        }
    }
}
