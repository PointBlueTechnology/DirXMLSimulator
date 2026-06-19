package com.pointblue.dirxml.sim;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.*;

/** GCV-reference scanning and the gcv.<name>= override. */
public class GcvReferencesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Element policy(String body) {
        return Xds.parse("<policy><rule><actions>" + body + "</actions></rule></policy>")
            .getDocumentElement();
    }

    @Test
    public void findsGlobalVariableAndConfigValueTokens() {
        Set<String> refs = GcvReferences.referenced(policy(
            "<do-set-dest-attr-value name='A'><arg-value type='string'>"
            + "<token-global-variable name='drv.name'/></arg-value></do-set-dest-attr-value>"
            + "<do-trace-message><arg-string><token-global-config-value name='idv.dit.data'/>"
            + "</arg-string></do-trace-message>"));
        assertTrue(refs.contains("drv.name"));
        assertTrue(refs.contains("idv.dit.data"));
        assertEquals(2, refs.size());
    }

    @Test
    public void doesNotTreatTildeTextAsAReference() {
        // ~name~ in a token-text is literal, not a GCV reference.
        Set<String> refs = GcvReferences.referenced(policy(
            "<do-trace-message><arg-string><token-text>~drv.name~</token-text></arg-string>"
            + "</do-trace-message>"));
        assertTrue(refs.isEmpty());
    }

    @Test
    public void engineProvidedAutoGcvsAreRecognized() {
        assertTrue(GcvReferences.isEngineProvided("dirxml.auto.driverdn"));
        assertFalse(GcvReferences.isEngineProvided("drv.name"));
    }

    @Test
    public void gcvOverrideFromCasePropertiesDefinesTheValue() throws Exception {
        Path dir = tmp.newFolder("gcv").toPath();
        Files.writeString(dir.resolve("case.properties"),
            "channel=subscriber\ngcv.drv.dept=Engineering\n");
        Files.writeString(dir.resolve("chain.txt"), "transform = policy.xml\n");
        Files.writeString(dir.resolve("policy.xml"),
            "<policy><rule><conditions/><actions><do-set-dest-attr-value name='Dept'>"
            + "<arg-value type='string'><token-global-variable name='drv.dept'/></arg-value>"
            + "</do-set-dest-attr-value></actions></rule></policy>");
        Files.writeString(dir.resolve("input.xds"),
            "<nds><input><add class-name='User' src-dn='\\T\\u\\j'>"
            + "<add-attr attr-name='Surname'><value>X</value></add-attr></add></input></nds>");

        Case c = Case.load(dir);
        assertTrue("gcv.drv.dept= should define the GCV", c.ctx.isGcvDefined("drv.dept"));
        assertTrue("the referenced GCV is now defined",
            c.sim.referencedGcvs().contains("drv.dept") && c.ctx.isGcvDefined("drv.dept"));
        assertTrue("output carries the resolved value", c.run().finalXds.contains(">Engineering<"));
    }
}
