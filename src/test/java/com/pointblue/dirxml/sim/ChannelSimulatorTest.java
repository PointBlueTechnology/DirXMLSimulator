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
