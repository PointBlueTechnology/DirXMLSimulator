package com.pointblue.dirxml.sim;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.*;

/** LDIF → fake-directory seed: name mapping, value normalization, folding, operational skips. */
public class LdifReaderTest {

    private static final String SCHEMA =
        "<schema>"
        + "<attr name='Surname' ldap='sn' syn='ci-string'/>"
        + "<attr name='GUID' ldap='guid' syn='octet-string'/>"
        + "<attr name='Login Time' ldap='loginTime' syn='time'/>"
        + "<attr name='Group Membership' ldap='groupMembership' syn='dist-name'/>"
        + "<class name='User' ldap='inetOrgPerson'><opt>Surname</opt></class>"
        + "</schema>";

    private static SchemaModel schema() {
        return SchemaModel.parse(Xds.parse(SCHEMA));
    }

    private static LdifReader reader(String driverDn) {
        return new LdifReader(schema(), new LdapValueNormalizer(), driverDn);
    }

    // sn folded across two lines; guid as base64 octet; loginTime generalized; a DN attr;
    // an operational attr (entryFlags) that must be dropped; a dirxml-associations value.
    private static String ldif(String guidB64) {
        return "version: 1\n"
            + "# a sample user\n"
            + "dn: cn=jdoe,ou=users,o=data\n"
            + "objectClass: inetOrgPerson\n"
            + "structuralObjectClass: inetOrgPerson\n"
            + "cn: jdoe\n"
            + "sn: van\n"
            + " derberg\n"                                    // folded continuation of sn (one leading space)
            + "guid:: " + guidB64 + "\n"
            + "loginTime: 20240101000000Z\n"
            + "groupMembership: cn=Admins,ou=groups,o=data\n"
            + "entryFlags: 0\n"
            + "dirxml-associations: cn=CC,cn=ds,o=system#1#jdoe-assoc\n"
            + "\n";
    }

    @Test
    public void mapsClassAndFoldedString() {
        Document doc = reader(null).toInstances(ldif(Base64.getEncoder().encodeToString(new byte[]{1, 2})));
        Element inst = Xds.firstByName(doc.getDocumentElement(), "instance");
        assertEquals("User", inst.getAttribute("class-name"));   // inetOrgPerson -> User
        assertEquals("cn=jdoe,ou=users,o=data", inst.getAttribute("src-dn"));
        assertEquals("vanderberg", value(inst, "Surname"));      // folded line rejoined
    }

    @Test
    public void octetBase64StaysBase64AsOctet() {
        byte[] raw = {0x00, 0x10, (byte) 0xFF};
        String b64 = Base64.getEncoder().encodeToString(raw);
        Element inst = Xds.firstByName(
            reader(null).toInstances(ldif(b64)).getDocumentElement(), "instance");
        Element v = valueEl(inst, "GUID");
        assertEquals("octet", v.getAttribute("type"));
        assertEquals(b64, Xds.text(v));   // round-trips the original bytes
    }

    @Test
    public void timeBecomesSecondsAndDnBecomesSlash() {
        Element inst = Xds.firstByName(
            reader(null).toInstances(ldif("AAA=")).getDocumentElement(), "instance");
        assertEquals("1704067200", value(inst, "Login Time"));
        assertEquals("\\data\\groups\\Admins", value(inst, "Group Membership"));
    }

    @Test
    public void operationalAttrsAreDropped() {
        Element inst = Xds.firstByName(
            reader(null).toInstances(ldif("AAA=")).getDocumentElement(), "instance");
        assertNull("entryFlags dropped", attrEl(inst, "entryFlags"));
        assertNull("structuralObjectClass not emitted as an attr",
            attrEl(inst, "structuralObjectClass"));
    }

    @Test
    public void associationPulledForMatchingDriver() {
        Element inst = Xds.firstByName(
            reader("cn=CC,cn=ds,o=system").toInstances(ldif("AAA=")).getDocumentElement(), "instance");
        Element assoc = Xds.firstByName(inst, "association");
        assertNotNull(assoc);
        assertEquals("jdoe-assoc", Xds.text(assoc));
    }

    @Test
    public void noAssociationWhenDriverDoesNotMatch() {
        Element inst = Xds.firstByName(
            reader("cn=Other,cn=ds,o=system").toInstances(ldif("AAA=")).getDocumentElement(), "instance");
        assertNull(Xds.firstByName(inst, "association"));
    }

    @Test
    public void seedsAndAnswersAQuery() {
        FakeDirectory dir = new FakeDirectory();
        reader(null).seed(dir, writeTemp(ldif(Base64.getEncoder().encodeToString(new byte[]{9}))));
        assertEquals(1, dir.size());
        // a search-attr query against the seeded data returns the entry
        Document resp = dir.query(Xds.parse(
            "<nds dtdversion='4.0'><input><query class-name='User' scope='subtree'>"
            + "<search-attr attr-name='Surname'><value>vanderberg</value></search-attr>"
            + "<read-attr attr-name='Surname'/></query></input></nds>"));
        Element inst = Xds.firstByName(resp.getDocumentElement(), "instance");
        assertNotNull("seeded entry is queryable", inst);
        assertEquals("cn=jdoe,ou=users,o=data", inst.getAttribute("src-dn"));
    }

    @Test
    public void structuralClassPrefersFirstNonTopWithoutSchema() {
        // eDir lists structural class first, auxiliary classes (pwmUser) last;
        // with no schema to consult, the structural one must still win.
        String e = "dn: cn=u,o=data\n"
            + "objectClass: inetOrgPerson\nobjectClass: organizationalPerson\n"
            + "objectClass: Person\nobjectClass: Top\nobjectClass: pwmUser\n"
            + "sn: U\n\n";
        LdifReader noSchema = new LdifReader(SchemaModel.empty(), new LdapValueNormalizer(), null);
        Element inst = Xds.firstByName(noSchema.toInstances(e).getDocumentElement(), "instance");
        assertEquals("inetOrgPerson", inst.getAttribute("class-name"));
    }

    @Test
    public void multipleEntriesParsed() {
        String two =
            "dn: cn=a,o=data\nobjectClass: inetOrgPerson\nsn: A\n\n"
            + "dn: cn=b,o=data\nobjectClass: inetOrgPerson\nsn: B\n\n";
        Document doc = reader(null).toInstances(two);
        assertEquals(2, Xds.childrenByName(Xds.firstByName(doc.getDocumentElement(), "input"), "instance").size());
    }

    // ---- helpers --------------------------------------------------------

    private static java.nio.file.Path writeTemp(String content) {
        try {
            java.nio.file.Path f = java.nio.file.Files.createTempFile("seed", ".ldif");
            java.nio.file.Files.write(f, content.getBytes(StandardCharsets.UTF_8));
            return f;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Element attrEl(Element instance, String attrName) {
        for (Element a : Xds.childrenByName(instance, "attr")) {
            if (attrName.equals(a.getAttribute("attr-name"))) {
                return a;
            }
        }
        return null;
    }

    private static Element valueEl(Element instance, String attrName) {
        Element a = attrEl(instance, attrName);
        assertNotNull("attr " + attrName, a);
        return Xds.firstByName(a, "value");
    }

    private static String value(Element instance, String attrName) {
        return Xds.text(valueEl(instance, attrName));
    }

    @SuppressWarnings("unused")
    private static List<String> values(Element instance, String attrName) {
        List<String> out = new ArrayList<>();
        for (Element v : Xds.childrenByName(attrEl(instance, attrName), "value")) {
            out.add(Xds.text(v));
        }
        return out;
    }
}
