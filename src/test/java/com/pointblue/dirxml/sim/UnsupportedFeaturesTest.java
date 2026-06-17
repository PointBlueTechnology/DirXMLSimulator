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
    public void flagsNamedPasswordAndEntitlements() {
        List<String> w = scan(
            "<do-set-dest-attr-value name='P'><arg-value type='string'>"
            + "<token-named-password name='pw'/></arg-value></do-set-dest-attr-value>"
            + "<do-set-dest-attr-value name='E'><arg-value type='string'>"
            + "<token-added-entitlement name='grp'/></arg-value></do-set-dest-attr-value>");
        assertEquals(2, w.size());
        assertTrue(w.stream().anyMatch(s -> s.contains("named passwords")));
        assertTrue(w.stream().anyMatch(s -> s.contains("entitlements")));
    }

    @Test
    public void cleanPolicyHasNoWarnings() {
        assertTrue(scan("<do-set-dest-attr-value name='X'><arg-value type='string'>"
            + "<token-text>hi</token-text></arg-value></do-set-dest-attr-value>").isEmpty());
    }
}
