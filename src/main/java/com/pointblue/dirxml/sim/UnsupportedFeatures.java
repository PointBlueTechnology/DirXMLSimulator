package com.pointblue.dirxml.sim;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects policy constructs that depend on IDM subsystems the harness does not
 * stand up, so the CLI can warn before a result is misread.
 *
 * <p>Note what is <b>not</b> here: entitlements and resource/role <i>membership</i>
 * are ordinary attribute values on the operation ({@code DirXML-EntitlementRef}).
 * The entitlement tokens/conditions ({@code token-added-entitlement},
 * {@code if-entitlement}, …) and {@code do-implement-entitlement} are op-driven and
 * work whenever the input carries those values — they are deliberately not flagged.
 *
 * <p>Named passwords are supported too — they're secret <i>values</i> (like GCVs)
 * supplied to the case ({@code namedPassword.<name>=…}); a case-aware check warns
 * only about names a policy references but that weren't supplied. What remains
 * genuinely missing is the <b>User App role/resource actions</b> ({@code do-add-role},
 * {@code do-create-resource}, …), which call the RBPM role service over SOAP.
 */
public final class UnsupportedFeatures {

    /** RBPM/User App role & resource actions — these call the role service over SOAP. */
    private static final String[] RBPM_ACTIONS = {
        "do-add-role", "do-remove-role", "do-create-resource", "do-modify-resource",
        "do-delete-resource"
    };

    private UnsupportedFeatures() {}

    /** Human-readable warnings for unsupported subsystems this policy touches. */
    public static List<String> scan(Element policy) {
        Set<String> names = new HashSet<>();
        collect(policy, names);
        List<String> msgs = new ArrayList<>();
        if (containsAny(names, RBPM_ACTIONS)) {
            msgs.add("User App role/resource actions (do-add-role, do-create-resource, …) — "
                + "these call the RBPM role service over SOAP, which the harness doesn't run; "
                + "they no-op or error (entitlement *values* are unaffected — those are op-driven)");
        }
        return msgs;
    }

    /** Named-password names a policy references (token-named-password / if-named-password). */
    public static List<String> referencedNamedPasswords(Element policy) {
        Set<String> out = new java.util.LinkedHashSet<>();
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

    private static boolean containsAny(Set<String> names, String[] watch) {
        for (String w : watch) {
            if (names.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(Element el, Set<String> out) {
        out.add(el.getLocalName());
        for (Element c : Xds.childElements(el)) {
            collect(c, out);
        }
    }
}
