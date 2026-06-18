package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.driver.DriverShim;
import com.novell.nds.dirxml.driver.PublicationShim;
import com.novell.nds.dirxml.driver.SubscriptionShim;
import com.novell.nds.dirxml.driver.XmlDocument;
import com.novell.nds.dirxml.driver.XmlQueryProcessor;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * {@code case.properties} wiring of an optional shim command sink — proves a case
 * runs the chain and then hands the final command to the configured shim, while
 * leaving cases without {@code shim=} untouched.
 */
public class CaseShimWiringTest {

    /** Minimal shim that echoes a recognizable status when it receives a command. */
    public static final class EchoShim implements DriverShim {
        private static XmlDocument ok() {
            return new XmlDocument(Xds.parse(
                "<nds dtdversion=\"4.0\"><output><status level=\"success\"/></output></nds>"));
        }
        public XmlDocument init(XmlDocument p) { return ok(); }
        public XmlDocument shutdown(XmlDocument p) { return ok(); }
        public PublicationShim getPublicationShim() { return null; }
        public XmlDocument getSchema(XmlDocument p) { return ok(); }
        public SubscriptionShim getSubscriptionShim() {
            return new SubscriptionShim() {
                public XmlDocument init(XmlDocument p) { return ok(); }
                public XmlDocument execute(XmlDocument command, XmlQueryProcessor reply) {
                    return new XmlDocument(Xds.parse(
                        "<nds dtdversion=\"4.0\"><output>"
                        + "<status level=\"success\" event-id=\"sink-ran\"/></output></nds>"));
                }
            };
        }
    }

    private static void write(Path f, String content) throws Exception {
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes("UTF-8"));
    }

    private static Path baseCase() throws Exception {
        Path dir = Files.createTempDirectory("dxshimcase");
        write(dir.resolve("input.xds"),
            "<nds dtdversion='4.0'><input><add class-name='User' src-dn='\\x\\y'>"
            + "<add-attr attr-name='Surname'><value>Doe</value></add-attr></add></input></nds>");
        write(dir.resolve("copy.xml"),
            "<policy><rule><description>stamp</description><conditions/><actions>"
            + "<do-set-dest-attr-value name='Stamped'><arg-value type='string'>"
            + "<token-text>yes</token-text></arg-value></do-set-dest-attr-value></actions></rule></policy>");
        write(dir.resolve("chain.txt"), "stamp = copy.xml\n");
        return dir;
    }

    @Test
    public void caseWithoutShimEndsAtChainOutput() throws Exception {
        Path dir = baseCase();
        // no case.properties at all
        ChannelSimulator.Result r = Case.load(dir).run();
        assertNull("no shim snapshot", r.stage("shim"));
        assertTrue("chain ran", r.finalXds.contains("Stamped"));
    }

    @Test
    public void caseWithShimAppendsShimSnapshot() throws Exception {
        Path dir = baseCase();
        write(dir.resolve("case.properties"),
            "shim=true\nshimClass=" + EchoShim.class.getName() + "\n");

        ChannelSimulator.Result r = Case.load(dir).run();

        // the chain still produced its output…
        assertTrue("chain ran", r.finalXds.contains("Stamped"));
        // …and the shim ran as a terminal sink
        StageSnapshot shim = r.stage("shim");
        assertNotNull("shim snapshot appended", shim);
        assertTrue("shim received the chain's command", shim.inputXds.contains("Stamped"));
        assertTrue("shim's response captured", shim.outputXds.contains("sink-ran"));
    }

    @Test
    public void shimTrueWithoutAClassFailsClearly() throws Exception {
        Path dir = baseCase();
        write(dir.resolve("case.properties"), "shim=true\n");  // no shimClass, no export/project
        try {
            Case.load(dir);
            fail("expected a configuration error");
        } catch (RuntimeException e) {
            assertTrue(String.valueOf(e.getMessage()).toLowerCase().contains("shim class")
                || String.valueOf(e.getCause()).toLowerCase().contains("shim class"));
        }
    }
}
