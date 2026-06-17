package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Policies touching unsupported subsystems are flagged (not silently empty). */
public class UnsupportedFeaturesTest {

    private static List<String> scan(String actions) {
        return UnsupportedFeatures.scan(PolicyLoader.load(
            "<policy><rule><description>x</description><conditions/><actions>" + actions
            + "</actions></rule></policy>"));
    }

    @Test
    public void flagsRbpmActionsOnly() {
        List<String> w = scan(
            "<do-add-role><arg-dn><token-text>\\r</token-text></arg-dn>"
            + "<arg-dn><token-src-dn/></arg-dn></do-add-role>");
        assertEquals(1, w.size());
        assertTrue(w.get(0).contains("role/resource"));
    }

    @Test
    public void referencedNamedPasswordsDetected() {
        org.w3c.dom.Element p = PolicyLoader.load(
            "<policy><rule><description>x</description><conditions/><actions>"
            + "<do-set-dest-attr-value name='P'><arg-value type='string'>"
            + "<token-named-password name='apiKey'/></arg-value></do-set-dest-attr-value>"
            + "</actions></rule></policy>");
        assertEquals(java.util.List.of("apiKey"), UnsupportedFeatures.referencedNamedPasswords(p));
    }

    @Test
    public void entitlementTokensAreNotFlagged() {
        // Entitlements are op-driven attribute values — supported, never warned.
        assertTrue(scan(
            "<do-set-dest-attr-value name='E'><arg-value type='string'>"
            + "<token-added-entitlement name='grp'/></arg-value></do-set-dest-attr-value>").isEmpty());
    }

    @Test
    public void cleanPolicyHasNoWarnings() {
        assertTrue(scan("<do-set-dest-attr-value name='X'><arg-value type='string'>"
            + "<token-text>hi</token-text></arg-value></do-set-dest-attr-value>").isEmpty());
    }
}
