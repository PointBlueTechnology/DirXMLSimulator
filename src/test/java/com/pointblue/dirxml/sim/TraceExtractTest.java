package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Verifies trace mining classifies channel documents and labels them by message. */
public class TraceExtractTest {

    private static final List<String> TRACE = Arrays.asList(
        "[03/13/23 02:48:59.630]:UKG PT:Receiving DOM document from application.",
        "[03/13/23 02:48:59.630]:UKG PT:",
        "<nds dtdversion=\"2.0\"><input>",
        "  <add class-name=\"User\" src-dn=\"EMP1\">",
        "    <add-attr attr-name=\"Surname\"><value>Doe</value></add-attr>",
        "  </add>",
        "</input></nds>",
        "[03/13/23 02:48:59.700]:UKG PT:Query from policy",
        "[03/13/23 02:48:59.700]:UKG PT:",
        "<nds dtdversion=\"4.0\"><input><query class-name=\"User\" scope=\"subtree\">",
        "  <search-attr attr-name=\"workforceID\"><value>EMP1</value></search-attr>",
        "</query></input></nds>",
        "[03/13/23 02:48:59.750]:UKG PT:Query from policy result",
        "[03/13/23 02:48:59.750]:UKG PT:",
        "<nds dtdversion=\"4.0\"><output>",
        "  <instance class-name=\"User\" src-dn=\"\\T\\data\\users\\jdoe\">",
        "    <association>EMP1-assoc</association>",
        "    <attr attr-name=\"Surname\"><value>Doe</value></attr>",
        "  </instance>",
        "  <status level=\"success\"/>",
        "</output></nds>",
        "[03/13/23 02:48:59.800]:UKG PT:Policy returned:",
        "[03/13/23 02:48:59.800]:UKG PT:",
        "<nds dtdversion=\"4.0\"><input><add class-name=\"User\"/></input></nds>");

    @Test
    public void classifiesDocumentsByLabelAndChannel() {
        List<TraceExtract.Doc> docs = TraceExtract.parse(TRACE);
        assertEquals(4, docs.size());

        assertEquals("event", docs.get(0).kind());
        assertEquals("PT", docs.get(0).channel);
        assertTrue(docs.get(0).xds.contains("<add class-name=\"User\""));

        assertEquals("query", docs.get(1).kind());
        assertEquals("query-result", docs.get(2).kind());
        assertTrue(docs.get(2).xds.contains("EMP1-assoc"));
        assertEquals("policy-returned", docs.get(3).kind());
    }

    /** Multi-word driver name in the thread tag (e.g. "CC PS02 ST") + subscriber markers. */
    private static final List<String> SUBSCRIBER_TRACE = Arrays.asList(
        "[05/07/26 11:09:29.298]:CC PS02 ST:Processing events for transaction.",
        "[05/07/26 11:09:29.298]:CC PS02 ST:",
        "<nds dtdversion=\"4.0\"><input>",
        "  <modify class-name=\"User\" src-dn=\"\\T\\data\\users\\A1\"><association>cc:A1</association>",
        "    <modify-attr attr-name=\"active\"><add-value><value>false</value></add-value></modify-attr>",
        "  </modify></input></nds>",
        "[05/07/26 11:09:29.400]:CC PS02 ST:Policy returned:",
        "[05/07/26 11:09:29.400]:CC PS02 ST:",
        "<nds dtdversion=\"4.0\"><input><modify class-name=\"User\"/></input></nds>",
        "[05/07/26 11:09:29.500]:CC PS02 ST:Submitting document to subscriber shim:",
        "[05/07/26 11:09:29.500]:CC PS02 ST:",
        "<nds dtdversion=\"4.0\"><input><modify class-name=\"user\"/></input></nds>",
        "[05/07/26 11:09:29.600]:CC PS02 ST:SubscriptionShim.execute() returned:",
        "[05/07/26 11:09:29.600]:CC PS02 ST:",
        "<nds dtdversion=\"4.0\"><output><status level=\"success\"/></output></nds>");

    @Test
    public void handlesMultiWordDriverAndSubscriberMarkers() {
        List<TraceExtract.Doc> docs = TraceExtract.parse(SUBSCRIBER_TRACE);
        assertEquals(4, docs.size());
        // Channel parsed from the LAST token of "CC PS02 ST".
        assertEquals("ST", docs.get(0).channel);
        // The eDir event is the input; the shim submission is a command, not an event.
        assertEquals("event", docs.get(0).kind());
        assertTrue(docs.get(0).isOperationEvent());
        assertEquals("policy-returned", docs.get(1).kind());
        assertEquals("command", docs.get(2).kind());
        assertEquals("response", docs.get(3).kind());
    }
}
