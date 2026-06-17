package com.pointblue.dirxml.sim;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects the named passwords a policy references, so the CLI can warn about any
 * that weren't supplied to the case (a named password is a secret value, like a
 * GCV — see {@code namedPassword.<name>} in case.properties).
 *
 * <p>External-service actions (REST, email, RBPM role/resource, workflow, …) are
 * handled separately by {@link FakeActions}, which fakes them. Entitlements are
 * op-driven attribute values and need no special handling.
 */
public final class UnsupportedFeatures {

    private UnsupportedFeatures() {}

    /** Named-password names a policy references (token-named-password / if-named-password). */
    public static List<String> referencedNamedPasswords(Element policy) {
        Set<String> out = new LinkedHashSet<>();
        collectNamedPasswords(policy, out);
        return new ArrayList<>(out);
    }

    private static void collectNamedPasswords(Element el, Set<String> out) {
        String ln = el.getLocalName();
        if ("token-named-password".equals(ln) || "if-named-password".equals(ln)) {
            String name = el.getAttribute("name");
            if (name != null && !name.isEmpty()) {
                out.add(name);
            }
        }
        for (Element c : Xds.childElements(el)) {
            collectNamedPasswords(c, out);
        }
    }
}
