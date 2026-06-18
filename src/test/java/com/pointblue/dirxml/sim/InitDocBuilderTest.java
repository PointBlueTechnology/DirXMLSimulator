package com.pointblue.dirxml.sim;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static org.junit.Assert.*;

/** Synthesis of the shim {@code <init-params>} document from driver config. */
public class InitDocBuilderTest {

    private static Element initParams(Document doc) {
        return Xds.firstByName(doc.getDocumentElement(), "init-params");
    }

    @Test
    public void buildsTheStandardSkeleton() {
        Document doc = new InitDocBuilder().build();
        Element ip = initParams(doc);
        assertNotNull("init-params present", ip);
        assertNotNull("authentication-info present", Xds.firstByName(ip, "authentication-info"));
        assertNotNull("driver-options present", Xds.firstByName(ip, "driver-options"));
        assertNotNull("publisher-options present", Xds.firstByName(ip, "publisher-options"));
        assertNotNull("subscriber-options present", Xds.firstByName(ip, "subscriber-options"));
        assertNotNull("subscriber-state present (shims expect it)",
                Xds.firstByName(ip, "subscriber-state"));
    }

    @Test
    public void setsDriverDnAsSrcDn() {
        Document doc = new InitDocBuilder()
                .driverDn("\\TREE\\system\\driverset\\CC")
                .build();
        assertEquals("\\TREE\\system\\driverset\\CC", initParams(doc).getAttribute("src-dn"));
    }

    @Test
    public void emptyDriverDnLeavesSrcDnUnset() {
        Document doc = new InitDocBuilder().build();
        assertEquals("", initParams(doc).getAttribute("src-dn"));
    }

    @Test
    public void populatesAuthentication() {
        Document doc = new InitDocBuilder()
                .authentication("https://host/token", "client-id", "s3cret")
                .build();
        Element auth = Xds.firstByName(initParams(doc), "authentication-info");
        assertEquals("https://host/token", Xds.text(Xds.firstByName(auth, "server")));
        assertEquals("client-id", Xds.text(Xds.firstByName(auth, "user")));
        assertEquals("s3cret", Xds.text(Xds.firstByName(auth, "password")));
    }

    @Test
    public void nullAuthFieldsAreOmitted() {
        Document doc = new InitDocBuilder()
                .authentication("https://host/token", null, null)
                .build();
        Element auth = Xds.firstByName(initParams(doc), "authentication-info");
        assertNotNull(Xds.firstByName(auth, "server"));
        assertNull("null user omitted", Xds.firstByName(auth, "user"));
        assertNull("null password omitted", Xds.firstByName(auth, "password"));
    }

    @Test
    public void writesOptionElements() {
        Document doc = new InitDocBuilder()
                .driverOption("apiEndpoint", "https://api/rest")
                .driverOption("trustAll", "false")
                .subscriberOption("passwordFlow", "never")
                .publisherOption("polling-interval", "60")
                .build();
        Element ip = initParams(doc);
        Element drv = Xds.firstByName(ip, "driver-options");
        assertEquals("https://api/rest", Xds.text(Xds.firstByName(drv, "apiEndpoint")));
        assertEquals("false", Xds.text(Xds.firstByName(drv, "trustAll")));
        assertEquals("never", Xds.text(Xds.firstByName(
                Xds.firstByName(ip, "subscriber-options"), "passwordFlow")));
        assertEquals("60", Xds.text(Xds.firstByName(
                Xds.firstByName(ip, "publisher-options"), "polling-interval")));
    }

    @Test
    public void embedsConfigurationValuesAsIs() {
        Document gcv = Xds.parse(
            "<configuration-values><definitions>"
            + "<definition display-name='Flag' name='gcv.flag' type='boolean'><value>true</value></definition>"
            + "</definitions></configuration-values>");
        Document doc = new InitDocBuilder()
                .gcvDefinitions(gcv.getDocumentElement())
                .build();
        Element drv = Xds.firstByName(initParams(doc), "driver-options");
        Element cv = Xds.firstByName(drv, "configuration-values");
        assertNotNull("GCVs embedded under driver-options", cv);
        assertNotNull("definition carried through", Xds.firstByName(cv, "definition"));
    }

    @Test
    public void wrapsBareDefinitionsInConfigurationValues() {
        Document defs = Xds.parse(
            "<definitions>"
            + "<definition display-name='Flag' name='gcv.flag' type='boolean'><value>true</value></definition>"
            + "</definitions>");
        Document doc = new InitDocBuilder()
                .gcvDefinitions(defs.getDocumentElement())
                .build();
        Element drv = Xds.firstByName(initParams(doc), "driver-options");
        Element cv = Xds.firstByName(drv, "configuration-values");
        assertNotNull("bare <definitions> wrapped in <configuration-values>", cv);
        assertNotNull(Xds.firstByName(cv, "definitions"));
    }

    @Test
    public void importingGcvDoesNotMutateSource() {
        Document gcv = Xds.parse("<configuration-values><definitions/></configuration-values>");
        Element src = gcv.getDocumentElement();
        Document doc = new InitDocBuilder().gcvDefinitions(src).build();
        // importNode copies: the source stays in its own document, and the embedded
        // node is a distinct instance owned by the init doc.
        assertSame("source still owned by its original document", gcv, src.getOwnerDocument());
        Element embedded = Xds.firstByName(
                Xds.firstByName(initParams(doc), "driver-options"), "configuration-values");
        assertNotSame("embedded GCV is a copy, not the original", src, embedded);
        assertSame("embedded GCV owned by the init doc", doc, embedded.getOwnerDocument());
    }

    @Test
    public void buildStringRoundTrips() {
        String xml = new InitDocBuilder()
                .driverDn("\\T\\ds\\D")
                .authentication("s", "u", "p")
                .driverOption("apiEndpoint", "x")
                .buildString();
        assertTrue(xml.contains("<init-params"));
        assertTrue(xml.contains("apiEndpoint"));
        // re-parse to prove it's well-formed
        Document reparsed = Xds.parse(xml);
        assertEquals("\\T\\ds\\D", initParams(reparsed).getAttribute("src-dn"));
    }
}
