package com.pointblue.dirxml.sim;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites policy actions that would make a live external call — REST, email,
 * RBPM role/resource SOAP, workflow, audit, SSO — into safe stand-ins, so a
 * simulated run never connects out (no hang, no failure) yet still shows what the
 * policy would have done.
 *
 * <p>Each such action is replaced with a {@code do-trace-message} marker
 * ("FAKED: …"). For {@code do-invoke-rest-endpoint}, a canned response can be
 * supplied per case; it is injected into the same local variable the real action
 * sets ({@code success.do-invoke-rest-endpoint}), so downstream rules that consume
 * the response behave as they would in production.
 */
public final class FakeActions {

    /** REST result variable the engine action sets on HTTP 200. */
    static final String REST_SUCCESS_VAR = "success.do-invoke-rest-endpoint";
    static final String REST_ERROR_VAR = "error.do-invoke-rest-endpoint";

    /** Actions that reach an external service (and must be faked). */
    static final Set<String> EXTERNAL = Set.of(
        "do-invoke-rest-endpoint", "do-send-email", "do-send-email-from-template",
        "do-start-workflow", "do-generate-xdas-event",
        "do-add-role", "do-remove-role", "do-create-role", "do-modify-role", "do-delete-role",
        "do-add-resource", "do-remove-resource", "do-create-resource", "do-modify-resource", "do-delete-resource",
        "do-set-sso-credential", "do-clear-sso-credential", "do-set-sso-passphrase");

    /** Per-case faking configuration. */
    public static final class Config {
        public boolean enabled = true;
        /** Canned REST success body used when no keyed match applies. */
        public String defaultRestResponse = null;
        /** Canned REST success bodies keyed by a substring of the action's {@code url} attribute. */
        public final Map<String, String> restResponsesByUrl = new LinkedHashMap<>();

        String restBodyFor(String urlAttr) {
            for (Map.Entry<String, String> e : restResponsesByUrl.entrySet()) {
                if (urlAttr != null && urlAttr.contains(e.getKey())) {
                    return e.getValue();
                }
            }
            return defaultRestResponse;
        }
    }

    private FakeActions() {}

    /** External actions present in a policy (for reporting). */
    public static List<String> externalActions(Element policy) {
        List<String> found = new ArrayList<>();
        collect(policy, found);
        return found;
    }

    private static void collect(Element el, List<String> found) {
        if (EXTERNAL.contains(el.getLocalName()) && !found.contains(el.getLocalName())) {
            found.add(el.getLocalName());
        }
        for (Element c : Xds.childElements(el)) {
            collect(c, found);
        }
    }

    /**
     * Rewrite external actions in place to safe stand-ins. Returns the distinct
     * action names that were faked. Operate on a copy if you need the original.
     */
    public static List<String> rewrite(Element policy, Config cfg) {
        List<String> faked = new ArrayList<>();
        Document doc = policy.getOwnerDocument();
        for (Element action : externalActionElements(policy)) {
            String name = action.getLocalName();
            Node parent = action.getParentNode();
            List<Element> replacements = new ArrayList<>();
            replacements.add(traceMessage(doc, "FAKED: " + name + describe(action)));
            if (name.equals("do-invoke-rest-endpoint")) {
                String body = cfg.restBodyFor(action.getAttribute("url"));
                if (body != null) {
                    replacements.add(setLocalVar(doc, REST_SUCCESS_VAR, body));
                }
            }
            for (Element r : replacements) {
                parent.insertBefore(r, action);
            }
            parent.removeChild(action);
            if (!faked.contains(name)) {
                faked.add(name);
            }
        }
        return faked;
    }

    /** Collect external action elements (depth-first), parents before children. */
    private static List<Element> externalActionElements(Element root) {
        List<Element> out = new ArrayList<>();
        gather(root, out);
        return out;
    }

    private static void gather(Element el, List<Element> out) {
        // snapshot children first; a matched external action is not descended into
        // (its args may themselves be tokens, but it's being removed wholesale).
        if (FakeActions.EXTERNAL.contains(el.getLocalName())) {
            out.add(el);
            return;
        }
        for (Element c : Xds.childElements(el)) {
            gather(c, out);
        }
    }

    private static String describe(Element action) {
        StringBuilder sb = new StringBuilder();
        for (String a : new String[] {"url", "type", "dn", "to", "template"}) {
            String v = action.getAttribute(a);
            if (v != null && !v.isEmpty()) {
                sb.append(' ').append(a).append('=').append(v);
            }
        }
        return sb.toString();
    }

    private static Element traceMessage(Document doc, String message) {
        Element trace = doc.createElementNS(null, "do-trace-message");
        Element argString = doc.createElementNS(null, "arg-string");
        Element token = doc.createElementNS(null, "token-text");
        token.appendChild(doc.createTextNode(message));
        argString.appendChild(token);
        trace.appendChild(argString);
        return trace;
    }

    private static Element setLocalVar(Document doc, String name, String value) {
        Element set = doc.createElementNS(null, "do-set-local-variable");
        set.setAttributeNS(null, "name", name);
        Element argString = doc.createElementNS(null, "arg-string");
        Element token = doc.createElementNS(null, "token-text");
        token.appendChild(doc.createTextNode(value));
        argString.appendChild(token);
        set.appendChild(argString);
        return set;
    }
}
