package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** Assembles a driver's channel chain from a Designer project on disk. */
public class DesignerProjectTest {

    private static final String COBJECT =
        "<com.novell.designer.model:CObject xmlns:com.novell.designer.model="
        + "'http://com.novell.designer.model' name='%s' type='%s'>%s</com.novell.designer.model:CObject>";

    private static void write(Path f, String content) throws Exception {
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes("UTF-8"));
    }

    @Test
    public void buildsSubscriberChainFromProject() throws Exception {
        Path proj = Files.createTempDirectory("dxproj");
        Path ds = proj.resolve("Model/EdirOrphan/DSID");
        // Driver -> Subscriber (child); Subscriber -> EventPolicies (ordered reference).
        write(ds.resolve("DRV.Driver_"), String.format(COBJECT, "MyDriver", "Driver",
            "<relations name='Idm:Subscriber' type='Child' key='#SUB.Subscriber_'/>"));
        write(ds.resolve("DRV/SUB.Subscriber_"), String.format(COBJECT, "Subscriber", "Subscriber",
            "<relations name='Idm:EventPolicies' type='Reference' key='#P1.ScriptPolicy_'/>"
            + "<relations name='Idm:Policies' type='Child' key='#P1.ScriptPolicy_'/>"));
        write(ds.resolve("DRV/P1.ScriptPolicy_"), String.format(COBJECT, "stamp", "ScriptPolicy", ""));
        write(ds.resolve("DRV/P1_contents.xml"),
            "<policy><rule><description>stamp</description><conditions/><actions>"
            + "<do-set-dest-attr-value name='Stamped'><arg-value type='string'>"
            + "<token-text>here</token-text></arg-value></do-set-dest-attr-value></actions></rule></policy>");

        DesignerProject p = DesignerProject.load(proj);
        assertTrue(p.driverNames().contains("MyDriver"));

        EngineContext ctx = EngineContext.create("\\T\\sys\\DS\\MyDriver");
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .addAll(p.subscriberChain("MyDriver", ctx))
            .run("<nds dtdversion='4.0'><input><add class-name='User' src-dn='\\x\\y'/></input></nds>");

        assertEquals(1, r.stages.size());
        assertEquals("subscriber-event:P1", r.stages.get(0).stageName);
        assertTrue("policy ran from the project: " + r.finalXds, r.finalXds.contains("Stamped"));
    }

    @Test
    public void loadsGcvsFromProject() throws Exception {
        Path proj = Files.createTempDirectory("dxgcv");
        Path edir = proj.resolve("Model/EdirOrphan");
        write(edir.resolve("DSID/DRV.Driver_"), String.format(COBJECT, "Drv", "Driver", ""));
        // DriverSet-scope config values: <DriverSetID>_<ServerID>_DirXML-ConfigValues.xml in EdirOrphan.
        write(edir.resolve("DSID_S1_DirXML-ConfigValues.xml"),
            "<configuration-values><definitions>"
            + "<definition name='test.gcv' display-name='T' type='string'><value>hello-gcv</value></definition>"
            + "</definitions></configuration-values>");

        DesignerProject p = DesignerProject.load(proj);
        com.novell.nds.dirxml.engine.gcv.GCDefinitions g = p.gcvDefinitions("Drv");
        com.novell.nds.dirxml.engine.gcv.GCValue v = g.getValue("test.gcv");
        assertNotNull("GCV resolved from project", v);
        assertEquals("hello-gcv", v.getValue());
    }
}
