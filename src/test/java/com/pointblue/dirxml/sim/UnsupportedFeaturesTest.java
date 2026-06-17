package com.pointblue.dirxml.sim;

import org.junit.Test;

import static org.junit.Assert.*;

/** Named-password reference detection (so the CLI can warn about unsupplied ones). */
public class UnsupportedFeaturesTest {

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
    public void cleanPolicyHasNoNamedPasswords() {
        org.w3c.dom.Element p = PolicyLoader.load(
            "<policy><rule><description>x</description><conditions/><actions>"
            + "<do-set-dest-attr-value name='X'><arg-value type='string'>"
            + "<token-text>hi</token-text></arg-value></do-set-dest-attr-value>"
            + "</actions></rule></policy>");
        assertTrue(UnsupportedFeatures.referencedNamedPasswords(p).isEmpty());
    }
}
