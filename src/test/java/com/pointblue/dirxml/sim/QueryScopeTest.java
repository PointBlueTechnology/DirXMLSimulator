package com.pointblue.dirxml.sim;

import org.junit.Test;

import org.w3c.dom.Document;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** The fake directory honours a query's scope (entry / subordinates / subtree) under its base DN. */
public class QueryScopeTest {

    private static FakeDirectory seed() {
        return new FakeDirectory().loadState(Xds.parse(
            "<nds dtdversion='4.0'><input>"
            + "<instance class-name='Organizational Unit' src-dn='RFI\\Users'/>"
            + "<instance class-name='User' src-dn='RFI\\Users\\Active\\DDUCK'>"
            + "<attr attr-name='workforceID'><value>1890020</value></attr></instance>"
            + "<instance class-name='User' src-dn='RFI\\Users\\Top'>"
            + "<attr attr-name='workforceID'><value>1890021</value></attr></instance>"
            + "<instance class-name='User' src-dn='RFI\\Other\\X'>"
            + "<attr attr-name='workforceID'><value>1890020</value></attr></instance>"
            + "</input></nds>"));
    }

    private static int count(Document d) {
        Matcher m = Pattern.compile("<instance\\b").matcher(Xds.serialize(d));
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static Document q(FakeDirectory dir, String attrs, String body) {
        return dir.query(Xds.parse("<nds dtdversion='4.0'><input><query class-name='User' "
            + attrs + ">" + body + "</query></input></nds>"));
    }

    @Test
    public void subtreeFindsDescendantsOnlyUnderBase() {
        Document r = q(seed(), "dest-dn='RFI\\Users' scope='subtree'",
            "<search-attr attr-name='workforceID'><value>1890020</value></search-attr>");
        assertEquals(1, count(r));
        assertTrue(Xds.serialize(r).contains("DDUCK"));
    }

    @Test
    public void absentScopeDefaultsToSubtree() {
        assertEquals(2, count(q(seed(), "dest-dn='rfi\\users'", "")));
    }

    @Test
    public void subordinatesIsOneLevel() {
        Document r = q(seed(), "dest-dn='RFI\\Users' scope='subordinates'", "");
        assertEquals(1, count(r));
        assertTrue(Xds.serialize(r).contains("Top"));
    }

    @Test
    public void entryIsTheBaseObjectOnly() {
        assertEquals(1, count(q(seed(), "dest-dn='RFI\\Users\\Top' scope='entry'", "")));
        assertEquals(0, count(q(seed(), "dest-dn='RFI\\Users' scope='entry'", "")));
    }

    @Test
    public void ldapAndDotFormatsPutTheRootLast() {
        assertTrue(FakeDirectory.inScope("cn=a,ou=users,o=rfi", "ou=users,o=rfi", "subtree"));
        assertFalse(FakeDirectory.inScope("cn=a,ou=other,o=rfi", "ou=users,o=rfi", "subtree"));
        assertTrue(FakeDirectory.inScope("a.users.rfi", "users.rfi", "subordinates"));
        assertFalse(FakeDirectory.inScope("a.b.users.rfi", "users.rfi", "subordinates"));
    }

    /** AD names a user CN=Smith\, John: the escaped comma is part of the RDN, not a separator. */
    @Test
    public void ldapDnWithAnEscapedCommaIsInScope() {
        String user = "CN=Smith\\, John,OU=Users,DC=x,DC=com";
        assertTrue(FakeDirectory.inScope(user, "OU=Users,DC=x,DC=com", "subtree"));
        assertTrue(FakeDirectory.inScope(user, "ou=users,dc=x,dc=com", "subordinates"));
        assertTrue(FakeDirectory.inScope(user, "DC=x,DC=com", null));
        assertFalse(FakeDirectory.inScope(user, "DC=x,DC=com", "subordinates"));
        assertFalse(FakeDirectory.inScope(user, "OU=Other,DC=x,DC=com", "subtree"));
    }

    @Test
    public void otherDnFormsStillMatch() {
        assertTrue(FakeDirectory.inScope("\\T\\O=acme\\CN=bob", "\\T\\O=acme", "subordinates"));
        assertTrue(FakeDirectory.inScope("O=acme\\CN=bob", "O=acme", "subordinates"));
        assertTrue(FakeDirectory.inScope("cn=john.smith,ou=users,o=acme", "ou=users,o=acme", "subordinates"));
        assertTrue(FakeDirectory.inScope("bob.users.acme", "users.acme", "subordinates"));
        assertFalse(FakeDirectory.inScope("uid=j,ou=people,dc=example,dc=com", "dc=example,dc=com", "subordinates"));
    }
}
