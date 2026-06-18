package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.*;

/**
 * LDAP→native value normalization keyed on eDir syntax. These are the
 * representation deltas a naive {@code value.toString()} gets wrong.
 */
public class LdapValueNormalizerTest {

    private final LdapValueNormalizer n = new LdapValueNormalizer();

    @Test
    public void stringFamilyPassesThrough() {
        LdapValueNormalizer.Value v = n.normalize("ci-string", "Doe");
        assertNull("plain string has no type attr", v.type);
        assertEquals("Doe", v.text);
        assertFalse(v.structured());
        assertFalse(v.skip);
    }

    @Test
    public void integerPassesThrough() {
        assertEquals("42", n.normalize("integer", "42").text);
        assertNull(n.normalize("integer", "42").type);
    }

    @Test
    public void octetStringBecomesBase64() {
        byte[] raw = {0x00, 0x01, (byte) 0xFF, 0x10};
        LdapValueNormalizer.Value v = n.normalize("octet-string", raw);
        assertEquals("octet", v.type);
        assertEquals(Base64.getEncoder().encodeToString(raw), v.text);
        // the bug we're fixing: toString() on a byte[] is [B@hash garbage
        assertNotEquals(raw.toString(), v.text);
    }

    @Test
    public void netAddressIsOctet() {
        assertEquals("octet", n.normalize("net-address", new byte[]{1, 2, 3}).type);
    }

    @Test
    public void booleanIsLowerCased() {
        assertEquals("true", n.normalize("boolean", "TRUE").text);
        assertEquals("false", n.normalize("boolean", "FALSE").text);
        assertEquals("string", n.normalize("boolean", "TRUE").type);
    }

    @Test
    public void generalizedTimeBecomesEpochSeconds() {
        // 2024-01-01T00:00:00Z = 1704067200
        assertEquals("1704067200", n.normalize("time", "20240101000000Z").text);
        assertEquals("time", n.normalize("time", "20240101000000Z").type);
    }

    @Test
    public void timeWithOffsetParses() {
        // 2024-01-01T00:00:00-0500 = 1704085200
        assertEquals("1704085200", n.normalize("time", "20240101000000-0500").text);
    }

    @Test
    public void unparseableTimePassesThrough() {
        // already integer seconds — leave it alone rather than corrupt it
        assertEquals("1704067200", n.normalize("time", "1704067200").text);
    }

    @Test
    public void timestampGetsSyntheticEventId() {
        assertEquals("1704067200#0", n.normalize("timestamp", "20240101000000Z").text);
    }

    @Test
    public void distNameReversesAndStripsRdns() {
        LdapValueNormalizer.Value v = n.normalize("dist-name", "cn=Role,ou=idm,o=system");
        assertNull("DN syntax renders as plain text", v.type);
        assertEquals("\\system\\idm\\Role", v.text);
    }

    @Test
    public void distNameWithTreePrefix() {
        LdapValueNormalizer withTree = new LdapValueNormalizer("ACME-TREE");
        assertEquals("\\ACME-TREE\\system\\idm\\Role",
                withTree.normalize("dist-name", "cn=Role,ou=idm,o=system").text);
    }

    @Test
    public void pathSyntaxDecomposesToComponents() {
        LdapValueNormalizer.Value v = n.normalize("path", "1#data#\\some\\path");
        assertTrue(v.structured());
        assertEquals(3, v.components.size());
        assertEquals("nameSpace", v.components.get(0).name);
        assertEquals("1", v.components.get(0).text);
        assertEquals("volume", v.components.get(1).name);
        assertEquals("data", v.components.get(1).text);
        assertEquals("path", v.components.get(2).name);
        assertEquals("\\some\\path", v.components.get(2).text);
    }

    @Test
    public void typedNameDecomposesAndReformatsDn() {
        LdapValueNormalizer.Value v = n.normalize("typed-name", "cn=Mgr,o=system#1#0");
        assertTrue(v.structured());
        assertEquals("typedName", v.components.get(0).name);
        assertEquals("\\system\\Mgr", v.components.get(0).text);
        assertEquals("1", v.components.get(1).text);
        assertEquals("0", v.components.get(2).text);
    }

    @Test
    public void malformedStructuredFallsBackToFlat() {
        // missing the '#' separators — keep the raw value rather than throw
        LdapValueNormalizer.Value v = n.normalize("path", "justastring");
        assertFalse(v.structured());
        assertEquals("justastring", v.text);
    }

    @Test
    public void streamIsSkipped() {
        assertTrue(n.normalize("stream", new byte[]{1, 2, 3}).skip);
    }

    @Test
    public void unknownSyntaxPassesThrough() {
        assertEquals("whatever", n.normalize("unknown", "whatever").text);
        assertEquals("whatever", n.normalize(null, "whatever").text);
    }

    @Test
    public void octetFromStringEncodesUtf8Bytes() {
        String s = "héllo";
        assertEquals(Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)),
                n.normalize("octet-string", s).text);
    }
}
