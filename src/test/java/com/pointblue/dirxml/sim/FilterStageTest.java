package com.pointblue.dirxml.sim;

import org.junit.Test;

import static org.junit.Assert.*;

/** The filter stage drops ignored classes and strips ignored attributes per channel. */
public class FilterStageTest {

    private static final String FILTER =
        "<filter>" +
        "  <filter-class class-name='User' subscriber='sync' publisher='sync'>" +
        "    <filter-attr attr-name='Surname' subscriber='sync' publisher='sync'/>" +
        "    <filter-attr attr-name='Secret' subscriber='ignore' publisher='sync'/>" +
        "  </filter-class>" +
        "  <filter-class class-name='Device' subscriber='ignore' publisher='sync'/>" +
        "</filter>";

    private static final String INPUT =
        "<nds dtdversion='4.0'><input>" +
        "  <add class-name='User' src-dn='\\o\\u'>" +
        "    <add-attr attr-name='Surname'><value>Doe</value></add-attr>" +
        "    <add-attr attr-name='Secret'><value>x</value></add-attr>" +
        "    <add-attr attr-name='Unlisted'><value>y</value></add-attr>" +
        "  </add>" +
        "  <add class-name='Device' src-dn='\\o\\d'><add-attr attr-name='cn'><value>d1</value></add-attr></add>" +
        "</input></nds>";

    @Test
    public void subscriberFilterDropsIgnored() {
        EngineContext ctx = EngineContext.create("\\T\\sys\\DS\\D");
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.filter("filter", Xds.parse(FILTER).getDocumentElement(), false /* subscriber */))
            .run(INPUT);

        String out = r.finalXds;
        assertTrue("Surname (synced) kept", out.contains("Surname"));
        assertFalse("Secret (ignored on subscriber) stripped", out.contains("Secret"));
        assertFalse("Unlisted attr stripped", out.contains("Unlisted"));
        assertFalse("Device class (ignored on subscriber) dropped", out.contains("Device"));
    }

    @Test
    public void publisherFilterKeepsWhatSubscriberDropped() {
        EngineContext ctx = EngineContext.create("\\T\\sys\\DS\\D");
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.filter("filter", Xds.parse(FILTER).getDocumentElement(), true /* publisher */))
            .run(INPUT);

        String out = r.finalXds;
        assertTrue("Secret synced on publisher", out.contains("Secret"));
        assertTrue("Device synced on publisher", out.contains("Device"));
        assertFalse("Unlisted attr still stripped", out.contains("Unlisted"));
    }
}
