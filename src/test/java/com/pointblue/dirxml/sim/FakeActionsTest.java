package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** External actions (REST/email/RBPM/…) are detected and faked — no live calls. */
public class FakeActionsTest {

    private static final String INPUT =
        "<nds dtdversion='4.0'><input><add class-name='User' src-dn='\\x\\y'/></input></nds>";

    @Test
    public void detectsExternalActionsButNotEntitlements() {
        org.w3c.dom.Element p = PolicyLoader.load(
            "<policy><rule><description>x</description><conditions/><actions>"
            + "<do-send-email/>"
            + "<do-add-role><arg-dn><token-text>\\r</token-text></arg-dn></do-add-role>"
            + "<do-set-dest-attr-value name='E'><arg-value type='string'>"
            + "<token-added-entitlement name='g'/></arg-value></do-set-dest-attr-value>"
            + "</actions></rule></policy>");
        List<String> ext = FakeActions.externalActions(p);
        assertTrue(ext.contains("do-send-email"));
        assertTrue(ext.contains("do-add-role"));
        assertFalse("entitlements are op-driven, not external", ext.contains("token-added-entitlement"));
    }

    @Test
    public void emailIsFakedNoError() {
        // do-send-email would hit SMTP; faked it must not error.
        EngineContext ctx = EngineContext.create("\\T\\sys\\DS\\D"); // faking on by default
        String policy =
            "<policy><rule><description>notify</description><conditions/><actions>"
            + "<do-send-email><arg-string name='to'><token-text>x@y.z</token-text></arg-string></do-send-email>"
            + "<do-set-dest-attr-value name='Done'><arg-value type='string'><token-text>yes</token-text></arg-value>"
            + "</do-set-dest-attr-value></actions></rule></policy>";
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("t", PolicyLoader.load(policy), ctx))
            .run(INPUT);
        assertNull("faked email must not error", r.stages.get(0).error);
        assertTrue("policy continued past the faked email", r.finalXds.contains("Done"));
        assertTrue("the fake is recorded in the trace", r.fullTrace.contains("FAKED: do-send-email"));
    }

    @Test
    public void restCannedResponseFlowsToDownstreamRule() {
        EngineContext ctx = EngineContext.create("\\T\\sys\\DS\\D");
        ctx.fakeConfig().defaultRestResponse = "{\"id\":42}";
        // Invoke REST, then copy the success variable into an attribute.
        String policy =
            "<policy><rule><description>call rest then use response</description><conditions/><actions>"
            + "<do-invoke-rest-endpoint url='https://api.example/x' type='GET'>"
            + "<arg-password><token-text>pw</token-text></arg-password></do-invoke-rest-endpoint>"
            + "<do-set-dest-attr-value name='Resp'><arg-value type='string'>"
            + "<token-local-variable name='success.do-invoke-rest-endpoint'/></arg-value>"
            + "</do-set-dest-attr-value></actions></rule></policy>";
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("t", PolicyLoader.load(policy), ctx))
            .run(INPUT);
        assertNull(r.stages.get(0).error);
        assertTrue("canned REST response flows into the downstream rule: " + r.finalXds,
            r.finalXds.contains("{\"id\":42}") || r.finalXds.contains("&quot;id&quot;"));
    }

    @Test
    public void rewriteRemovesExternalActions() {
        // After rewrite, no external action remains in the policy (so the engine
        // never constructs/runs one — no live call, and no construction validation).
        org.w3c.dom.Element p = PolicyLoader.load(
            "<policy><rule><description>x</description><conditions/><actions>"
            + "<do-send-email server='smtp' to='a@b'/>"
            + "<do-invoke-rest-endpoint url='https://x' type='GET'/>"
            + "</actions></rule></policy>");
        assertEquals(2, FakeActions.externalActions(p).size());
        FakeActions.rewrite(p, new FakeActions.Config());
        assertTrue("external actions removed after rewrite", FakeActions.externalActions(p).isEmpty());
        assertTrue("a fake marker is left behind", Xds.serializeElement(p).contains("do-trace-message"));
    }
}
