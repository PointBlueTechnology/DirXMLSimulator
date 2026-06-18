package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Builds a SchemaModel from real eDir RFC 4512 subschema definitions: NDS name
 * via X-NDS_NAME (or the LDAP name), syntax OID → eDir syn, SINGLE-VALUE, and
 * class structure.
 */
public class LdapSchemaReaderTest {

    // Real eDir subschema definitions (shapes taken verbatim from a live cn=schema).
    private static final List<String> ATTRS = List.of(
        "( 2.5.4.4 NAME ( 'sn' 'surname' ) SUP name SYNTAX 1.3.6.1.4.1.1466.115.121.1.15{64} X-NDS_NAME 'Surname' )",
        "( 2.16.840.1.113719.1.1.4.1.6 NAME 'backLink' SYNTAX 2.16.840.1.113719.1.1.5.1.23 X-NDS_NAME 'Back Link' )",
        "( 1.2.3.4 NAME 'guid' SYNTAX 1.3.6.1.4.1.1466.115.121.1.40 SINGLE-VALUE X-NDS_NAME 'GUID' )",
        "( 2.5.18.1 NAME 'createTimestamp' SYNTAX 1.3.6.1.4.1.1466.115.121.1.24 SINGLE-VALUE )",
        "( 1.2.3.5 NAME 'groupMembership' SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 X-NDS_NAME 'Group Membership' )",
        "( 1.2.3.6 NAME 'afEndDate' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )");

    private static final List<String> CLASSES = List.of(
        "( 2.5.6.6 NAME 'person' SUP Top STRUCTURAL MUST ( cn $ sn ) MAY ( description ) X-NDS_NAME 'Person' )",
        "( 2.16.840.1.113730.3.2.2 NAME 'inetOrgPerson' SUP person STRUCTURAL MAY ( guid $ groupMembership ) X-NDS_NAME 'User' )");

    private static SchemaModel schema() {
        return LdapSchemaReader.parse(ATTRS, CLASSES);
    }

    @Test
    public void ndsNameFromXNdsName_ldapNameAlsoResolves() {
        SchemaModel s = schema();
        SchemaModel.Attr byNds = s.attr("Surname");
        assertNotNull("NDS name keyed", byNds);
        assertEquals("Surname", byNds.name);
        assertEquals("sn", byNds.ldap);
        // the LDAP name resolves to the same attr
        assertEquals("Surname", s.attr("sn").name);
    }

    @Test
    public void ndsNameDefaultsToLdapWhenNoExtension() {
        // afEndDate has no X-NDS_NAME → NDS name == LDAP name
        SchemaModel.Attr a = schema().attr("afEndDate");
        assertNotNull(a);
        assertEquals("afEndDate", a.name);
    }

    @Test
    public void mapsSyntaxOidsToEdirSyn() {
        SchemaModel s = schema();
        assertEquals("ci-string", s.attr("Surname").syntax);
        assertEquals("octet-string", s.attr("GUID").syntax);     // .1.40
        assertEquals("time", s.attr("createTimestamp").syntax);  // .1.24
        assertEquals("dist-name", s.attr("Group Membership").syntax); // .1.12
        assertEquals("back-link", s.attr("backLink").syntax);    // eDir arc .23
    }

    @Test
    public void singleValueFlag() {
        assertTrue(schema().attr("GUID").singleValued);
        assertFalse(schema().attr("Surname").singleValued);
    }

    @Test
    public void classesResolveByNdsAndLdapName() {
        SchemaModel s = schema();
        assertTrue(s.hasClass("User"));          // X-NDS_NAME
        assertTrue(s.hasClass("inetOrgPerson")); // LDAP name
        assertEquals("User", s.dirxmlClassName("inetOrgPerson"));
        assertEquals("inetOrgPerson", s.ldapClassName("User"));
    }

    @Test
    public void inheritedAttrsAllowedByEitherName() {
        SchemaModel s = schema();
        // User -> person -> Top; person MUST cn/sn, User MAY guid/groupMembership
        assertTrue(s.attrAllowedForClass("User", "Surname"));   // NDS name
        assertTrue(s.attrAllowedForClass("User", "sn"));        // LDAP name
        assertTrue(s.attrAllowedForClass("User", "GUID"));
        assertTrue(s.attrAllowedForClass("User", "Group Membership"));
    }

    @Test
    public void parseLdifReadsAttributeTypeAndObjectClassLines() {
        String ldif =
            "dn: cn=schema\n"
            + "attributeTypes: ( 1.2.3.6 NAME 'afEndDate' SYNTAX 1.3.6.1.4.1.1466.115.121.1.24\n"
            + "  X-NDS_NAME 'afEndDate' )\n"          // folded line
            + "objectClasses: ( 1.2.3.7 NAME 'worker' SUP Top STRUCTURAL MAY ( afEndDate ) X-NDS_NAME 'worker' )\n";
        SchemaModel s = LdapSchemaReader.parseLdif(ldif);
        assertTrue(s.hasClass("worker"));
        assertEquals("time", s.attr("afEndDate").syntax);   // folded def parsed correctly
    }
}
