package com.pointblue.dirxml.sim;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * End-to-end harness tests: multi-stage stepping and a policy that queries the
 * in-memory {@link FakeDirectory}.
 */
public class ChannelSimulatorTest {

    private static final String STAMP_L =
        "<policy><rule><description>stamp L</description><conditions/><actions>" +
        "<do-set-dest-attr-value name='L'>" +
        "<arg-value type='string'><token-text>here</token-text></arg-value>" +
        "</do-set-dest-attr-value></actions></rule></policy>";

    private static final String STAMP_TITLE =
        "<policy><rule><description>stamp Title</description><conditions/><actions>" +
        "<do-set-dest-attr-value name='Title'>" +
        "<arg-value type='string'><token-text>eng</token-text></arg-value>" +
        "</do-set-dest-attr-value></actions></rule></policy>";

    private static final String ADD_INPUT =
        "<nds dtdversion='4.0'><input>" +
        "<add class-name='User' src-dn='\\ACME\\users\\jdoe'>" +
        "<add-attr attr-name='Given Name'><value>John</value></add-attr>" +
        "</add></input></nds>";

    @Test
    public void steppingCapturesEachStage() {
        EngineContext ctx = EngineContext.create("\\ACME\\sys\\DS\\Drv");
        ChannelSimulator sim = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("event-transform", PolicyLoader.load(STAMP_L), ctx))
            .add(PolicyStage.fromElement("command-transform", PolicyLoader.load(STAMP_TITLE), ctx));

        ChannelSimulator.Result r = sim.run(ADD_INPUT);

        assertEquals(2, r.stages.size());
        // Stage 1 added L but not Title.
        assertTrue(r.stage("event-transform").outputXds.contains("\"L\""));
        assertFalse(r.stage("event-transform").outputXds.contains("\"Title\""));
        // Stage 2's input is stage 1's output (the chain carries forward).
        assertEquals(r.stage("event-transform").outputXds, r.stage("command-transform").inputXds);
        // Final has both.
        assertTrue(r.finalXds.contains("\"L\""));
        assertTrue(r.finalXds.contains("\"Title\""));
        // Each stage produced trace.
        assertTrue(r.stage("event-transform").trace.contains("stamp L"));
        assertTrue(r.stage("command-transform").trace.contains("stamp Title"));
    }

    private static final String THREE_RULES =
        "<policy>" +
        "  <rule><description>stamp A</description><conditions/><actions>" +
        "    <do-set-dest-attr-value name='A'><arg-value type='string'><token-text>1</token-text></arg-value></do-set-dest-attr-value>" +
        "  </actions></rule>" +
        "  <rule><description>stamp B</description><conditions/><actions>" +
        "    <do-set-dest-attr-value name='B'><arg-value type='string'><token-text>2</token-text></arg-value></do-set-dest-attr-value>" +
        "  </actions></rule>" +
        "  <rule><description>veto if no Surname</description>" +
        "    <conditions><and><if-op-attr name='Surname' op='not-available'/></and></conditions>" +
        "    <actions><do-veto/></actions></rule>" +
        "</policy>";

    @Test
    public void perRuleSteppingExpandsAndCarriesForward() {
        EngineContext ctx = EngineContext.create("\\ACME\\sys\\DS\\Drv");
        ChannelSimulator sim = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("transform", PolicyLoader.load(THREE_RULES), ctx));

        // Whole-policy: one stage.
        assertEquals(1, sim.run(ADD_INPUT).stages.size());

        // Per-rule: one stage per rule, document carried forward.
        ChannelSimulator.Result r = sim.run(Xds.parse(ADD_INPUT), true);
        assertEquals(3, r.stages.size());
        assertTrue(r.stages.get(0).stageName.contains("#1 stamp A"));
        // Rule 1 added A but not B.
        assertTrue(r.stages.get(0).outputXds.contains("\"A\""));
        assertFalse(r.stages.get(0).outputXds.contains("\"B\""));
        // Rule 2's input is rule 1's output; it adds B.
        assertEquals(r.stages.get(0).outputXds, r.stages.get(1).inputXds);
        assertTrue(r.stages.get(1).outputXds.contains("\"B\""));
        // Surname present (in ADD_INPUT? no) -> veto fires at rule 3.
        // ADD_INPUT has only Given Name, so the veto rule strips the op.
        assertTrue(r.finalXds.contains("<input/>"));
    }

    @Test
    public void xsltPolicyTransforms() {
        EngineContext ctx = EngineContext.create("\\T\\sys\\DS\\D");
        String xslt =
            "<xsl:stylesheet xmlns:xsl='http://www.w3.org/1999/XSL/Transform' version='1.0'>" +
            "<xsl:template match='@*|node()'><xsl:copy><xsl:apply-templates select='@*|node()'/></xsl:copy></xsl:template>" +
            "<xsl:template match=\"add-attr[@attr-name='Surname']/value/text()\">CHANGED-BY-XSLT</xsl:template>" +
            "</xsl:stylesheet>";
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("output", PolicyLoader.load(xslt), ctx))
            .run("<nds dtdversion='4.0'><input><add class-name='User' src-dn='\\x\\y'>" +
                 "<add-attr attr-name='Surname'><value>Doe</value></add-attr></add></input></nds>");
        assertTrue("XSLT should transform the value: " + r.finalXds,
            r.finalXds.contains("CHANGED-BY-XSLT"));
    }

    @Test
    public void ecmaScriptFunctionResolves() {
        EngineContext ctx = EngineContext.create("\\T\\sys\\DS\\D");
        ctx.enableEcmaScript(java.util.List.of("function up(s){ return ('' + s).toUpperCase(); }"));
        String policy =
            "<policy xmlns:es='http://www.novell.com/nxsl/ecmascript'>" +
            "<rule><description>es upper</description><conditions/><actions>" +
            "<do-set-dest-attr-value name='U'>" +
            "<arg-value type='string'><token-xpath expression=\"es:up('doe')\"/></arg-value>" +
            "</do-set-dest-attr-value></actions></rule></policy>";
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("t", PolicyLoader.load(policy), ctx))
            .run(ADD_INPUT);
        assertNull("es: function should not error", r.stages.get(0).error);
        assertTrue("es:up('doe') -> DOE in output: " + r.finalXds, r.finalXds.contains("DOE"));
    }

    @Test
    public void autoDriverDnResolves() {
        // dirxml.auto.driverdn is auto-seeded from the driver DN (the engine does
        // this at runtime; exports don't carry it).
        EngineContext ctx = EngineContext.create("\\TREE\\sys\\DS\\MyDriver");
        String policy =
            "<policy><rule><description>stamp driverdn</description><conditions/><actions>" +
            "<do-set-dest-attr-value name='DDN'>" +
            "<arg-value type='string'><token-global-variable name='dirxml.auto.driverdn'/></arg-value>" +
            "</do-set-dest-attr-value></actions></rule></policy>";
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("t", PolicyLoader.load(policy), ctx))
            .run(ADD_INPUT);
        assertTrue("driver DN should resolve into output: " + r.finalXds,
            r.finalXds.contains("MyDriver"));
    }

    @Test
    public void stageErrorIsGraceful() {
        // A policy that throws (ECMAScript function with no script processor) must
        // not crash the run; it's captured as a stage error.
        EngineContext ctx = EngineContext.create("\\T\\sys\\DS\\D");
        String boom =
            "<policy xmlns:es='http://www.novell.com/nxsl/ecmascript'>" +
            "<rule><description>boom</description><conditions/><actions>" +
            "<do-set-dest-attr-value name='X'>" +
            "<arg-value type='string'><token-xpath expression=\"es:guid2string('x')\"/></arg-value>" +
            "</do-set-dest-attr-value></actions></rule></policy>";
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("boom", PolicyLoader.load(boom), ctx))
            .run(ADD_INPUT); // does not throw
        assertEquals(1, r.stages.size());
        assertNotNull("stage should have recorded an error", r.stages.get(0).error);
    }

    @Test
    public void entitlementTokenResolvesFromOperation() {
        // Entitlements are attribute values on the op (DirXML-EntitlementRef); the
        // engine's token reads them from the operation — no external service.
        EngineContext ctx = EngineContext.create("\\ACME\\sys\\DS\\Drv");
        String policy =
            "<policy><rule><description>read added entitlement</description><conditions/><actions>" +
            "<do-set-dest-attr-value name='Granted'>" +
            "<arg-value type='string'><token-added-entitlement name='Group'/></arg-value>" +
            "</do-set-dest-attr-value></actions></rule></policy>";
        String input =
            "<nds dtdversion='4.0'><input>" +
            "<modify class-name='User' src-dn='\\ACME\\users\\jdoe'><association>a1</association>" +
            "<modify-attr attr-name='DirXML-EntitlementRef'><add-value><value type='structured'>" +
            "<component name='nameSpace'>1</component>" +
            "<component name='volume'>\\ACME\\sys\\DS\\Drv\\Group</component>" +
            "<component name='path.xml'><ref><src>UA</src><id/><param>cn=admins,o=data</param></ref></component>" +
            "</value></add-value></modify-attr></modify></input></nds>";
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("t", PolicyLoader.load(policy), ctx))
            .run(input);
        assertNull(r.stages.get(0).error);
        assertTrue("added entitlement value flows into output: " + r.finalXds,
            r.finalXds.contains("cn=admins,o=data"));
    }

    @Test
    public void policyQueriesFakeDirectory() {
        // Directory holds jdoe with a Surname the policy will copy via a query.
        FakeDirectory dir = new FakeDirectory().loadState(Xds.parse(
            "<nds dtdversion='4.0'><input>" +
            "<instance class-name='User' src-dn='\\ACME\\users\\jdoe'>" +
            "<association>jdoe-assoc</association>" +
            "<attr attr-name='Surname'><value>Doe</value></attr>" +
            "<attr attr-name='Given Name'><value>John</value></attr>" +
            "</instance></input></nds>"));
        assertEquals(1, dir.size());

        // Policy reads Surname from the destination datastore and stamps a copy.
        String policy =
            "<policy><rule><description>copy surname via query</description><conditions/><actions>" +
            "<do-set-dest-attr-value name='CopiedSurname'>" +
            "<arg-value type='string'><token-dest-attr name='Surname'/></arg-value>" +
            "</do-set-dest-attr-value></actions></rule></policy>";

        String input =
            "<nds dtdversion='4.0'><input>" +
            "<modify class-name='User' src-dn='\\ACME\\users\\jdoe' dest-dn='\\ACME\\users\\jdoe'>" +
            "<association>jdoe-assoc</association>" +
            "<modify-attr attr-name='Title'><add-value><value>Engineer</value></add-value></modify-attr>" +
            "</modify></input></nds>";

        EngineContext ctx = EngineContext.create("\\ACME\\sys\\DS\\Drv");
        ChannelSimulator.Result r = new ChannelSimulator(ctx, dir)
            .add(PolicyStage.fromElement("create", PolicyLoader.load(policy), ctx))
            .run(input);

        // The directory was queried, and the queried value flowed into the output.
        StageSnapshot s = r.stage("create");
        assertFalse("expected at least one query to the directory", s.queries.isEmpty());
        assertTrue("queried Surname should appear in output: " + r.finalXds,
            r.finalXds.contains("CopiedSurname"));
        assertTrue("copied value should be Doe: " + r.finalXds, r.finalXds.contains("Doe"));
    }
}
