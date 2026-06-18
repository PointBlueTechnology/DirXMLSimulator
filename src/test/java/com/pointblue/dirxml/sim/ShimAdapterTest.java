package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.driver.DriverShim;
import com.novell.nds.dirxml.driver.PublicationShim;
import com.novell.nds.dirxml.driver.SubscriptionShim;
import com.novell.nds.dirxml.driver.XmlDocument;
import com.novell.nds.dirxml.driver.XmlQueryProcessor;
import com.novell.nds.dirxml.engine.XdsQueryProcessor;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Drives {@link ShimAdapter} against a stub {@link DriverShim} — the driver-side
 * interfaces ship in the engine jar, so the lifecycle and back-channel bridge are
 * testable without any proprietary connector.
 */
public class ShimAdapterTest {

    /** Captures of what the stub shim saw, for assertions. */
    static String capturedInit;
    static String capturedCommand;
    static String capturedBackChannelReply;

    @Before
    public void reset() {
        capturedInit = null;
        capturedCommand = null;
        capturedBackChannelReply = null;
    }

    private static XmlDocument success() {
        return new XmlDocument(Xds.parse(
            "<nds dtdversion=\"4.0\"><output><status level=\"success\"/></output></nds>"));
    }

    /** A no-op shim that records init/command and pings the back-channel. */
    public static final class StubDriverShim implements DriverShim {
        public XmlDocument init(XmlDocument p) {
            capturedInit = p.getDocumentString();
            return success();
        }
        public XmlDocument shutdown(XmlDocument p) { return success(); }
        public SubscriptionShim getSubscriptionShim() { return new StubSub(); }
        public PublicationShim getPublicationShim() { return null; }
        public XmlDocument getSchema(XmlDocument p) {
            return new XmlDocument(Xds.parse(
                "<nds dtdversion=\"4.0\"><output><schema-def/></output></nds>"));
        }
    }

    public static final class StubSub implements SubscriptionShim {
        public XmlDocument init(XmlDocument p) { return success(); }
        public XmlDocument execute(XmlDocument command, XmlQueryProcessor reply) {
            capturedCommand = command.getDocumentString();
            // exercise the back-channel the adapter wired in
            XmlDocument r = reply.query(new XmlDocument(Xds.parse(
                "<nds dtdversion=\"4.0\"><input><query class-name=\"User\"/></input></nds>")));
            capturedBackChannelReply = r.getDocumentString();
            // echo a status the test can recognize
            return new XmlDocument(Xds.parse(
                "<nds dtdversion=\"4.0\"><output><status level=\"success\" event-id=\"shim-ran\"/></output></nds>"));
        }
    }

    private static final String SHIM = StubDriverShim.class.getName();

    private static Document initDoc() {
        return new InitDocBuilder().driverDn("\\T\\ds\\D")
                .driverOption("marker", "init-here").build();
    }

    @Test
    public void runsInitLifecycleWithTheInitDoc() {
        ShimAdapter.create(SHIM, ShimAdapterTest.class.getClassLoader(), initDoc(), null);
        assertNotNull("driver.init was called", capturedInit);
        assertTrue("init doc carried through", capturedInit.contains("init-here"));
    }

    @Test
    public void executeRoutesCommandToShimAndReturnsItsResponse() {
        ShimAdapter adapter = ShimAdapter.create(
            SHIM, ShimAdapterTest.class.getClassLoader(), initDoc(), null);

        Document command = Xds.parse(
            "<nds dtdversion=\"4.0\"><input>"
            + "<add class-name=\"User\" dest-dn=\"cn=jdoe\"/></input></nds>");
        Document response = adapter.execute(command);

        assertTrue("shim saw the command", capturedCommand.contains("cn=jdoe"));
        Element status = Xds.firstByName(response.getDocumentElement(), "status");
        assertEquals("shim-ran", status.getAttribute("event-id"));
    }

    @Test
    public void backChannelBridgesToTheEngineQuerySource() {
        // the adapter must bridge the shim's driver-side query to this engine-side source
        XdsQueryProcessor source = q -> Xds.parse(
            "<nds dtdversion=\"4.0\"><output>"
            + "<instance class-name=\"User\" src-dn=\"cn=from-backchannel\"/>"
            + "<status level=\"success\"/></output></nds>");

        ShimAdapter adapter = ShimAdapter.create(
            SHIM, ShimAdapterTest.class.getClassLoader(), initDoc(), source);
        adapter.execute(Xds.parse(
            "<nds dtdversion=\"4.0\"><input><add class-name=\"User\"/></input></nds>"));

        assertNotNull("shim issued a back-channel query", capturedBackChannelReply);
        assertTrue("back-channel answer came from the engine source",
            capturedBackChannelReply.contains("from-backchannel"));
    }

    @Test
    public void nullBackChannelGivesEmptySuccess() {
        ShimAdapter adapter = ShimAdapter.create(
            SHIM, ShimAdapterTest.class.getClassLoader(), initDoc(), null);
        adapter.execute(Xds.parse(
            "<nds dtdversion=\"4.0\"><input><add class-name=\"User\"/></input></nds>"));
        // shim still got a well-formed success reply, not a crash
        assertTrue(capturedBackChannelReply.contains("success"));
    }

    @Test
    public void missingShimClassFailsWithClearError() {
        try {
            ShimAdapter.create("com.example.NoSuchShim",
                ShimAdapterTest.class.getClassLoader(), initDoc(), null);
            fail("expected a load failure");
        } catch (RuntimeException e) {
            assertTrue("diagnostic names the class", e.getMessage().contains("NoSuchShim"));
        }
    }

    @Test
    public void classLoaderForEmptyReturnsParent() {
        assertSame(ShimAdapter.class.getClassLoader(), ShimAdapter.classLoaderFor(List.of()));
    }

    @Test
    public void classLoaderForJarsIsChildLoader() {
        ClassLoader cl = ShimAdapter.classLoaderFor(List.of(Path.of("/tmp/whatever-shim.jar")));
        assertNotSame(ShimAdapter.class.getClassLoader(), cl);
        assertSame("parented so engine jars stay visible",
            ShimAdapter.class.getClassLoader(), cl.getParent());
    }
}
