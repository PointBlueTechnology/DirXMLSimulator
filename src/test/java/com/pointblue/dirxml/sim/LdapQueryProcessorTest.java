package com.pointblue.dirxml.sim;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** XDS query → LDAP request mapping and response building (with value normalization). */
public class LdapQueryProcessorTest {

    private static final String SCHEMA =
        "<schema>"
        + "<attr name='Surname' ldap='sn' syn='ci-string'/>"
        + "<attr name='GUID' ldap='guid' syn='octet-string'/>"
        + "<attr name='Login Time' ldap='loginTime' syn='time'/>"
        + "<class name='User' ldap='inetOrgPerson'/>"
        + "</schema>";

    /** Stub search: captures the request, returns canned entries. */
    private static final class StubSearch implements LdapQueryProcessor.Search {
        LdapQueryProcessor.Request lastRequest;
        List<LdapQueryProcessor.Entry> result = new ArrayList<>();
        public List<LdapQueryProcessor.Entry> search(LdapQueryProcessor.Request r) {
            this.lastRequest = r;
            return result;
        }
    }

    private static LdapQueryProcessor processor(StubSearch search) {
        return new LdapQueryProcessor(search, SchemaModel.parse(Xds.parse(SCHEMA)),
                new LdapValueNormalizer(), "o=data", "");
    }

    private static Element query(String inner) {
        Document d = Xds.parse("<nds dtdversion='4.0'><input>" + inner + "</input></nds>");
        return Xds.firstByName(d.getDocumentElement(), "query");
    }

    @Test
    public void buildsAssociationFilter() {
        StubSearch s = new StubSearch();
        LdapQueryProcessor.Request r = processor(s).buildRequest(
            query("<query class-name='User'><association>abc#1#xyz</association></query>"), "User");
        assertEquals("(&(objectClass=inetOrgPerson)(DirXML-Associations=abc#1#xyz))", r.filter);
        assertEquals(LdapQueryProcessor.SCOPE_SUBTREE, r.scope);
    }

    @Test
    public void buildsSearchAttrFilterWithMappedNames() {
        StubSearch s = new StubSearch();
        LdapQueryProcessor.Request r = processor(s).buildRequest(
            query("<query class-name='User' scope='subtree'>"
                + "<search-attr attr-name='Surname'><value>Doe</value></search-attr>"
                + "<read-attr attr-name='GUID'/></query>"), "User");
        // class and attr names mapped to LDAP
        assertEquals("(&(objectClass=inetOrgPerson)(sn=Doe))", r.filter);
        assertTrue("read-attr mapped to ldap name", r.attrs.contains("guid"));
        assertTrue("association attr always requested", r.attrs.contains("DirXML-Associations"));
    }

    @Test
    public void mapsScope() {
        StubSearch s = new StubSearch();
        LdapQueryProcessor p = processor(s);
        assertEquals(LdapQueryProcessor.SCOPE_BASE,
            p.buildRequest(query("<query class-name='User' scope='entry'/>"), "User").scope);
        assertEquals(LdapQueryProcessor.SCOPE_ONE_LEVEL,
            p.buildRequest(query("<query class-name='User' scope='subordinates'/>"), "User").scope);
    }

    @Test
    public void unknownClassFallsBackToGivenName() {
        StubSearch s = new StubSearch();
        LdapQueryProcessor.Request r = processor(s).buildRequest(
            query("<query class-name='Widget'/>"), "Widget");
        assertEquals("(objectClass=Widget)", r.filter);
    }

    @Test
    public void buildsNormalizedResponse() {
        StubSearch s = new StubSearch();
        Map<String, List<Object>> attrs = new LinkedHashMap<>();
        attrs.put("sn", List.of("Doe"));
        attrs.put("guid", List.of(new byte[]{0, 1, 2}));
        attrs.put("loginTime", List.of("20240101000000Z"));
        attrs.put("DirXML-Associations", List.of("jdoe-assoc"));
        s.result.add(new LdapQueryProcessor.Entry("cn=jdoe,o=data", attrs));

        Document resp = processor(s).query(Xds.parse(
            "<nds dtdversion='4.0'><input><query class-name='User'>"
            + "<association>jdoe-assoc</association></query></input></nds>"));

        Element instance = Xds.firstByName(resp.getDocumentElement(), "instance");
        assertNotNull(instance);
        assertEquals("User", instance.getAttribute("class-name"));
        assertEquals("cn=jdoe,o=data", instance.getAttribute("src-dn"));

        Element assoc = Xds.firstByName(instance, "association");
        assertEquals("associated", assoc.getAttribute("state"));
        assertEquals("jdoe-assoc", Xds.text(assoc));

        // ldap names mapped back to DirXML names, values normalized by syntax
        assertEquals("Doe", attrValue(instance, "Surname").get(0));
        Element guid = attrValueEl(instance, "GUID");
        assertEquals("octet", guid.getAttribute("type"));
        assertEquals(Base64.getEncoder().encodeToString(new byte[]{0, 1, 2}), Xds.text(guid));
        Element lt = attrValueEl(instance, "Login Time");
        assertEquals("time", lt.getAttribute("type"));
        assertEquals("1704067200", Xds.text(lt));

        assertEquals("success",
            Xds.firstByName(resp.getDocumentElement(), "status").getAttribute("level"));
    }

    @Test
    public void emptyResultStillReturnsSuccessStatus() {
        StubSearch s = new StubSearch();
        Document resp = processor(s).query(Xds.parse(
            "<nds dtdversion='4.0'><input><query class-name='User'/></input></nds>"));
        assertNull("no instances", Xds.firstByName(resp.getDocumentElement(), "instance"));
        assertEquals("success",
            Xds.firstByName(resp.getDocumentElement(), "status").getAttribute("level"));
    }

    @Test
    public void searchFailureBecomesErrorStatus() {
        LdapQueryProcessor.Search boom = r -> { throw new RuntimeException("connection refused"); };
        LdapQueryProcessor p = new LdapQueryProcessor(boom, SchemaModel.parse(Xds.parse(SCHEMA)),
                new LdapValueNormalizer(), "o=data", "");
        Document resp = p.query(Xds.parse(
            "<nds dtdversion='4.0'><input><query class-name='User'/></input></nds>"));
        Element status = Xds.firstByName(resp.getDocumentElement(), "status");
        assertEquals("error", status.getAttribute("level"));
        assertTrue(Xds.text(status).contains("connection refused"));
    }

    // ---- helpers --------------------------------------------------------

    private static Element attrEl(Element instance, String attrName) {
        for (Element a : Xds.childrenByName(instance, "attr")) {
            if (attrName.equals(a.getAttribute("attr-name"))) {
                return a;
            }
        }
        return null;
    }

    private static Element attrValueEl(Element instance, String attrName) {
        Element a = attrEl(instance, attrName);
        assertNotNull("attr " + attrName + " present", a);
        return Xds.firstByName(a, "value");
    }

    private static List<String> attrValue(Element instance, String attrName) {
        List<String> out = new ArrayList<>();
        for (Element v : Xds.childrenByName(attrEl(instance, attrName), "value")) {
            out.add(Xds.text(v));
        }
        return out;
    }
}
