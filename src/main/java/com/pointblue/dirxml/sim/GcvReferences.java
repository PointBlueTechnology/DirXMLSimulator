package com.pointblue.dirxml.sim;

import org.w3c.dom.Element;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Finds the Global Config Values a policy <em>references</em>, so the harness can
 * warn when one is referenced but not defined in scope (a DriverSet-scope GCV
 * missing from a single-driver export, say) — the signal that drives "ask the user
 * for it" (see {@code docs/scope-resolution-design.md}).
 *
 * <p>DirXML Script references a GCV with the <b>Global Variable</b> token,
 * {@code <token-global-variable name="…"/>} (the dominant form — 248× in a real
 * driver set), or the explicit {@code <token-global-config-value name="…"/>}. A
 * {@code ~name~} in a string is <em>not</em> a policy GCV reference (it's literal
 * in a token-text), so it is intentionally not scanned here.
 *
 * <p>Pure and offline-testable.
 */
public final class GcvReferences {

    private GcvReferences() {
    }

    /** Elements that name a referenced GCV in their {@code name} attribute. */
    private static final String[] GCV_TOKENS = {"token-global-variable", "token-global-config-value"};

    /** GCV names referenced by a policy element (order-preserving, de-duplicated). */
    public static Set<String> referenced(Element policy) {
        Set<String> names = new LinkedHashSet<>();
        if (policy == null) {
            return names;
        }
        for (String token : GCV_TOKENS) {
            for (Element e : Xds.descendantsByName(policy, token)) {
                add(names, e.getAttribute("name"));
            }
        }
        return names;
    }

    private static void add(Set<String> names, String n) {
        if (n != null && !n.isBlank()) {
            names.add(n.trim());
        }
    }

    /**
     * True if a referenced name is one the <em>engine</em> provides at runtime and
     * the harness shouldn't flag as missing (the {@code dirxml.auto.*} family).
     */
    public static boolean isEngineProvided(String name) {
        return name != null && name.startsWith("dirxml.auto.");
    }
}
