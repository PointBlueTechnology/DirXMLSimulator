package com.pointblue.dirxml.sim;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects policy constructs that depend on IDM subsystems the harness does not
 * stand up — named passwords, entitlements, and roles/resources (RBPM). These do
 * not error; tokens/conditions resolve to empty/false and role/resource actions
 * no-op (or fail), which can silently mislead. The CLI warns up front when a
 * policy uses them so the result isn't misread.
 */
public final class UnsupportedFeatures {

    private static final String[] NAMED_PASSWORD = {"token-named-password", "if-named-password"};
    private static final String[] ENTITLEMENTS = {
        "token-added-entitlement", "token-removed-entitlement", "token-entitlement",
        "token-op-entitlement", "if-entitlement", "if-op-entitlement"
    };
    private static final String[] ROLES_RESOURCES = {
        "do-add-role", "do-remove-role", "do-create-resource", "do-modify-resource",
        "do-delete-resource", "do-implement-entitlement", "do-revoke-entitlement"
    };

    private UnsupportedFeatures() {}

    /** Human-readable warnings for unsupported subsystems this policy touches. */
    public static List<String> scan(Element policy) {
        Set<String> names = new HashSet<>();
        collect(policy, names);
        List<String> msgs = new ArrayList<>();
        if (containsAny(names, NAMED_PASSWORD)) {
            msgs.add("named passwords (token-named-password / if-named-password) — "
                + "no password store; resolves to empty / condition false");
        }
        if (containsAny(names, ENTITLEMENTS)) {
            msgs.add("entitlements (token-*-entitlement / if-entitlement) — "
                + "no entitlement context; resolve to empty / conditions false");
        }
        if (containsAny(names, ROLES_RESOURCES)) {
            msgs.add("roles/resources (do-add-role, do-create-resource, …) — "
                + "RBPM/entitlement service not wired; these no-op or error");
        }
        return msgs;
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
