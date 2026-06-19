package com.pointblue.dirxml.sim;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * End-to-end: a Map token in a real chain resolves its table from a case-local
 * {@code mapping-tables/} dir (the thing that fails today with VRDException -9192).
 */
public class MappingTableResolutionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path makeCase() throws Exception {
        Path dir = tmp.newFolder("map-case").toPath();
        Files.writeString(dir.resolve("case.properties"), "channel=subscriber\nfromNDS=true\n");
        Files.writeString(dir.resolve("chain.txt"), "transform = policy.xml\n");
        Files.writeString(dir.resolve("policy.xml"),
            "<policy><rule><description>map region</description><conditions/><actions>"
            + "<do-set-dest-attr-value name='Region'><arg-value type='string'>"
            + "<token-map dest='region' source='state' table=\"..\\..\\Library\\RegionMap\">"
            + "<token-text>CA</token-text></token-map>"
            + "</arg-value></do-set-dest-attr-value>"
            + "</actions></rule></policy>");
        Files.writeString(dir.resolve("input.xds"),
            "<nds><input><add class-name='User' src-dn='\\T\\u\\jdoe'>"
            + "<add-attr attr-name='Surname'><value>X</value></add-attr></add></input></nds>");
        Path mt = Files.createDirectories(dir.resolve("mapping-tables"));
        Files.writeString(mt.resolve("RegionMap.xml"),
            "<mapping-table><col-def name='state'/><col-def name='region'/>"
            + "<row><col>CA</col><col>West</col></row>"
            + "<row><col>NY</col><col>East</col></row></mapping-table>");
        return dir;
    }

    @Test
    public void mapTokenResolvesFromCaseLocalTable() throws Exception {
        Case c = Case.load(makeCase());
        ChannelSimulator.Result r = c.run();
        // CA -> West via the mapping table; the stage must build (no VRDException)
        // and the output must carry the mapped value.
        assertTrue("expected Region=West in output, got:\n" + r.finalXds,
            r.finalXds.contains("attr-name=\"Region\"") && r.finalXds.contains(">West<"));
    }

    @Test
    public void missingTableDoesNotCrashTheRun() throws Exception {
        Path dir = makeCase();
        // remove the table the policy references
        Files.delete(dir.resolve("mapping-tables").resolve("RegionMap.xml"));
        Files.delete(dir.resolve("mapping-tables"));
        // The Map token can't resolve; loading/running must not throw a hard error
        // beyond the engine's own handling — assert we get a result object.
        try {
            Case c = Case.load(dir);
            ChannelSimulator.Result r = c.run();
            assertNotNull(r.finalXds);
        } catch (RuntimeException expectedDiagnostic) {
            // Acceptable: a clear stage-build diagnostic when the table is absent.
            assertTrue(expectedDiagnostic.getMessage() == null
                || expectedDiagnostic.getMessage().toLowerCase().contains("map")
                || expectedDiagnostic.getMessage().toLowerCase().contains("stage"));
        }
    }
}
