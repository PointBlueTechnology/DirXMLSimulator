package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Schema parsing (with inheritance) and input validation. */
public class SchemaModelTest {

    private static final String SCHEMA =
        "<schema>"
        + "<attr name='CN' ldap='cn' syn='ci-string'/>"
        + "<attr name='Surname' ldap='sn' syn='ci-string'/>"
        + "<attr name='Login Disabled' ldap='loginDisabled' syn='boolean' sngl='1'/>"
        + "<class name='Top' ldap='top'/>"
        + "<class name='Person' ldap='person'><sup>Top</sup><opt>CN, Surname</opt></class>"
        + "<class name='User' ldap='inetOrgPerson'><sup>Person</sup><opt>Login Disabled</opt></class>"
        + "<class name='Group' ldap='groupOfNames'><sup>Top</sup><opt>CN</opt></class>"
        + "</schema>";

    private static SchemaModel schema() {
        return SchemaModel.parse(Xds.parse(SCHEMA));
    }

    @Test
    public void resolvesInheritedAttributes() {
        SchemaModel s = schema();
        assertTrue(s.hasClass("User"));
        assertTrue(s.hasAttr("CN"));
        assertTrue("ldap name resolves", s.hasAttr("sn"));
        // User inherits CN/Surname from Person and adds Login Disabled.
        assertTrue(s.attrAllowedForClass("User", "CN"));
        assertTrue(s.attrAllowedForClass("User", "Surname"));
        assertTrue(s.attrAllowedForClass("User", "Login Disabled"));
        // Group does not allow Surname.
        assertFalse(s.attrAllowedForClass("Group", "Surname"));
    }

    @Test
    public void validateFlagsTypoSingleValuedAndUnknownClass() {
        List<String> w = schema().validate(Xds.parse(
            "<nds dtdversion='4.0'><input>"
            + "<add class-name='User' src-dn='\\x'>"
            + "<add-attr attr-name='Surnam'><value>Doe</value></add-attr>"          // typo
            + "<add-attr attr-name='Login Disabled'><value>a</value><value>b</value></add-attr>"  // single-valued x2
            + "</add>"
            + "<add class-name='Widget' src-dn='\\y'/>"                              // unknown class
            + "</input></nds>"));
        assertTrue(w.stream().anyMatch(s -> s.contains("unknown attribute 'Surnam'")));
        assertTrue(w.stream().anyMatch(s -> s.contains("single-valued attribute 'Login Disabled'")));
        assertTrue(w.stream().anyMatch(s -> s.contains("unknown class 'Widget'")));
    }

    @Test
    public void cleanInputHasNoWarnings() {
        assertTrue(schema().validate(Xds.parse(
            "<nds dtdversion='4.0'><input><add class-name='User' src-dn='\\x'>"
            + "<add-attr attr-name='Surname'><value>Doe</value></add-attr></add></input></nds>")).isEmpty());
    }

    @Test
    public void emptySchemaValidatesNothing() {
        assertTrue(SchemaModel.empty().validate(Xds.parse(
            "<nds dtdversion='4.0'><input><add class-name='Anything'/></input></nds>")).isEmpty());
    }
}
