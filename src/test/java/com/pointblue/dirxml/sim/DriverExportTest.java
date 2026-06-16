package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Verifies that {@link DriverExport} assembles channel chains from a Designer
 * driver-configuration in the correct IDM policy-set order, resolving linkage
 * items to the right rules across channel/driver scope.
 */
public class DriverExportTest {

    /** A minimal driver export: 2 driver-level rules + 2 subscriber rules. */
    private static final String EXPORT =
        "<driver-configuration name='Test' dn='cn=Test,cn=ds,o=sys'>" +
        "  <policy-linkage>" +
        "    <linkage-item dn='cn=smap,cn=Test,cn=ds,o=sys' order='0' policy-set='0' policy-set-name='Schema Mapping'/>" +
        "    <linkage-item dn='cn=otp,cn=Test,cn=ds,o=sys' order='0' policy-set='2' policy-set-name='Output'/>" +
        "    <linkage-item dn='cn=se2,cn=Subscriber,cn=Test,cn=ds,o=sys' order='1' policy-set='4' policy-set-name='Subscriber Event'/>" +
        "    <linkage-item dn='cn=se1,cn=Subscriber,cn=Test,cn=ds,o=sys' order='0' policy-set='4' policy-set-name='Subscriber Event'/>" +
        "    <linkage-item dn='cn=sc,cn=Subscriber,cn=Test,cn=ds,o=sys' order='0' policy-set='10' policy-set-name='Subscriber Command'/>" +
        "  </policy-linkage>" +
        "  <subscriber name='Subscriber'><children>" +
        "    <rule name='se1'>" + emptyPolicy("se1") + "</rule>" +
        "    <rule name='se2'>" + emptyPolicy("se2") + "</rule>" +
        "    <rule name='sc'>" + emptyPolicy("sc") + "</rule>" +
        "  </children></subscriber>" +
        "  <rule name='otp'>" + emptyPolicy("otp") + "</rule>" +
        "  <rule name='smap'><attr-name-map>" +
        "     <class-name><nds-name>User</nds-name><app-name>User</app-name></class-name>" +
        "  </attr-name-map></rule>" +
        "</driver-configuration>";

    private static String emptyPolicy(String desc) {
        return "<policy><rule><description>" + desc + "</description><conditions/><actions/></rule></policy>";
    }

    @Test
    public void subscriberChainInCanonicalOrder() {
        DriverExport ex = DriverExport.load(Xds.parse(EXPORT));
        EngineContext ctx = EngineContext.create("\\sys\\ds\\Test");
        List<PolicyStage> chain = ex.subscriberChain(ctx);

        // Subscriber order: event -> command -> schema-mapping -> output-transform.
        // Within the event set, order attribute decides (se1 before se2).
        assertEquals(5, chain.size());
        assertEquals("subscriber-event:se1", chain.get(0).name());
        assertEquals("subscriber-event:se2", chain.get(1).name());
        assertEquals("subscriber-command:sc", chain.get(2).name());
        assertEquals("schema-mapping:smap", chain.get(3).name());
        assertEquals("output-transform:otp", chain.get(4).name());
    }

    @Test
    public void exportChainRunsEndToEnd() {
        DriverExport ex = DriverExport.load(Xds.parse(EXPORT));
        EngineContext ctx = EngineContext.create("\\sys\\ds\\Test");
        ChannelSimulator sim = new ChannelSimulator(ctx, new FakeDirectory());
        sim.addAll(ex.subscriberChain(ctx));

        ChannelSimulator.Result r = sim.run(
            "<nds dtdversion='4.0'><input>" +
            "<add class-name='User' src-dn='\\sys\\u\\x'>" +
            "<add-attr attr-name='Surname'><value>Doe</value></add-attr>" +
            "</add></input></nds>");

        // All five stages ran (no-ops here, but executed without error).
        assertEquals(5, r.stages.size());
        assertTrue(r.finalXds.contains("Doe"));
    }
}
