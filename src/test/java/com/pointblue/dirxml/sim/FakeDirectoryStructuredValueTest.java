package com.pointblue.dirxml.sim;

import org.junit.Test;
import org.w3c.dom.Document;

import static org.junit.Assert.*;

/**
 * Structured values (e.g. {@code DirXML-EntitlementRef}) must survive the fake
 * directory: a query returns their components intact, a {@code remove-value}
 * matches them regardless of formatting, and the engine's entitlement tokens can
 * read the user's held entitlements from the directory.
 */
public class FakeDirectoryStructuredValueTest {

    private static final String DRV = "\\ACME\\sys\\DS\\Drv";
    private static final String USER = "\\ACME\\users\\jdoe";

    private static String ent(String name, String id) {
        return ent(name, id, 1);
    }

    /** {@code state} 1 = granted, 0 = revoked (how a revoke reaches the subscriber). */
    private static String ent(String name, String id, int state) {
        return "<value type='structured'>"
            + "<component name='nameSpace'>" + state + "</component>"
            + "<component name='volume'>" + DRV + "\\" + name + "</component>"
            + "<component name='path.xml'><ref><src>IG</src><id/><param>" + id + "</param></ref></component>"
            + "</value>";
    }

    private static FakeDirectory dirWith(String... values) {
        return new FakeDirectory().loadState(Xds.parse(
            "<nds dtdversion='4.0'><input>"
            + "<instance class-name='User' src-dn='" + USER + "'>"
            + "<association>a1</association>"
            + "<attr attr-name='DirXML-EntitlementRef'>" + String.join("", values) + "</attr>"
            + "<attr attr-name='Surname'><value>Doe</value></attr>"
            + "</instance></input></nds>"));
    }

    private static String readEntRefs(FakeDirectory dir) {
        Document out = dir.query(Xds.parse(
            "<nds dtdversion='4.0'><input><query class-name='User' dest-dn='" + USER + "' scope='entry'>"
            + "<read-attr attr-name='DirXML-EntitlementRef'/></query></input></nds>"));
        return Xds.serialize(out);
    }

    @Test
    public void queryReturnsStructuredValueIntact() {
        String xml = readEntRefs(dirWith(ent("Role", "r1")));
        assertTrue("components survive: " + xml, xml.contains("<component name=\"volume\">"));
        assertTrue("nested path.xml survives: " + xml, xml.contains("<param>r1</param>"));
    }

    @Test
    public void plainValuesUnchanged() {
        FakeDirectory dir = dirWith(ent("Role", "r1"));
        assertEquals("[Doe]", dir.get(USER).attrs.get("Surname").toString());
        String xml = Xds.serialize(dir.query(Xds.parse(
            "<nds dtdversion='4.0'><input><query class-name='User' dest-dn='" + USER + "' scope='entry'>"
            + "<read-attr attr-name='Surname'/></query></input></nds>")));
        assertTrue(xml, xml.contains("<value>Doe</value>"));
    }

    @Test
    public void removeValueMatchesStructuredValueDespiteWhitespace() {
        FakeDirectory dir = dirWith(ent("Role", "r1"), ent("Role", "r2"));
        String spaced = ent("Role", "r1").replace("><", ">\n  <");
        dir.execute(Xds.parse("<nds dtdversion='4.0'><input><modify class-name='User' dest-dn='" + USER + "'>"
            + "<modify-attr attr-name='DirXML-EntitlementRef'><remove-value>" + spaced
            + "</remove-value></modify-attr></modify></input></nds>"));
        String xml = readEntRefs(dir);
        assertFalse("r1 removed: " + xml, xml.contains("<param>r1</param>"));
        assertTrue("r2 kept: " + xml, xml.contains("<param>r2</param>"));
        assertEquals(1, dir.get(USER).attrs.get("DirXML-EntitlementRef").size());
    }

    @Test
    public void addValueKeepsStructure() {
        FakeDirectory dir = dirWith(ent("Role", "r1"));
        dir.execute(Xds.parse("<nds dtdversion='4.0'><input><modify class-name='User' dest-dn='" + USER + "'>"
            + "<modify-attr attr-name='DirXML-EntitlementRef'><add-value>" + ent("Group", "g1")
            + "</add-value></modify-attr></modify></input></nds>"));
        String xml = readEntRefs(dir);
        assertTrue(xml, xml.contains("<param>g1</param>"));
        assertTrue(xml, xml.contains("<param>r1</param>"));
    }

    @Test
    public void engineReadsHeldEntitlementsFromDirectory() {
        // The op revokes Role r1 (granted value out, state-0 value in). Account and
        // Role r2 are held, not changing, so the engine must read them from the directory.
        FakeDirectory dir = dirWith(ent("Account", "acct"), ent("Role", "r2"));
        String policy =
            "<policy>"
            + "<rule><description>veto without account</description><conditions><and>"
            + "<if-entitlement name='Account' op='not-available'/></and></conditions>"
            + "<actions><do-veto/></actions></rule>"
            + "<rule><description>list roles</description><conditions><and>"
            + "<if-entitlement name='Role' op='changing'/></and></conditions><actions>"
            + "<do-for-each><arg-node-set><token-entitlement name='Role'/></arg-node-set><arg-actions>"
            + "<do-add-dest-attr-value name='roles'><arg-value type='string'>"
            + "<token-local-variable name='current-node'/></arg-value></do-add-dest-attr-value>"
            + "</arg-actions></do-for-each></actions></rule></policy>";
        String input = "<nds dtdversion='4.0'><input>"
            + "<modify class-name='User' src-dn='" + USER + "'><association>a1</association>"
            + "<modify-attr attr-name='DirXML-EntitlementRef'><remove-value>" + ent("Role", "r1")
            + "</remove-value><add-value>" + ent("Role", "r1", 0)
            + "</add-value></modify-attr></modify></input></nds>";

        EngineContext ctx = EngineContext.create(DRV);
        ChannelSimulator.Result r = new ChannelSimulator(ctx, dir)
            .add(PolicyStage.fromElement("ctp", PolicyLoader.load(policy), ctx))
            .run(input);

        assertNull(r.stages.get(0).error);
        assertFalse("not vetoed — Account is held in the directory: " + r.finalXds,
            r.finalXds.contains("<input/>"));
        String roles = r.finalXds.substring(r.finalXds.indexOf("attr-name=\"roles\""));
        assertTrue("held Role r2 listed from the directory: " + r.finalXds, roles.contains("r2"));
        assertFalse("revoked Role r1 not listed as held: " + r.finalXds,
            roles.substring(0, roles.indexOf("</modify-attr>")).contains("r1"));
    }

    @Test
    public void matchingAndCreateSkippedForAssociatedModify() {
        // The engine runs Matching/Create/Placement only for adds and unassociated objects.
        String findMatch = "<policy><rule><description>m</description><conditions/><actions>"
            + "<do-find-matching-object scope='subtree'><arg-match-attr name='CN'/></do-find-matching-object>"
            + "</actions></rule></policy>";
        String assoc = "<nds dtdversion='4.0'><input><modify class-name='User' src-dn='" + USER + "'>"
            + "<association>a1</association><modify-attr attr-name='Title'><add-value><value>x</value>"
            + "</add-value></modify-attr></modify></input></nds>";
        EngineContext ctx = EngineContext.create(DRV);
        ChannelSimulator.Result r = new ChannelSimulator(ctx, dirWith(ent("Account", "acct")))
            .add(PolicyStage.fromElement("subscriber-matching:m", PolicyLoader.load(findMatch), ctx))
            .add(PolicyStage.fromElement("subscriber-create:c", PolicyLoader.load(findMatch), ctx))
            .run(assoc);
        assertEquals(2, r.stages.size());
        assertNull(r.stages.get(0).error);
        assertTrue(r.stages.get(0).trace.startsWith("Skipped"));
        assertTrue(r.finalXds.contains("attr-name=\"Title\""));

        // An unassociated modify still goes through matching (the engine treats it as an add).
        String unassoc = assoc.replace("<association>a1</association>", "");
        assertTrue(ChannelSimulator.needsAddPolicies(Xds.parse(unassoc)));
        assertFalse(ChannelSimulator.needsAddPolicies(Xds.parse(assoc)));
    }
}
