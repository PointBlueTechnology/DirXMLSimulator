package com.pointblue.dirxml.sim;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

/** Extraction of mapping tables from a case dir and from export resources. */
public class MappingTableSourceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The real embed shape from RFI-DriverSet.xml. */
    private static final String EXPORT =
        "<driver-set-configuration><children>"
        + "<policy-library base-dn='cn=Library,cn=DS'>"
        + "  <resource content-type='application/vnd.novell.dirxml.mapping-table+xml ;charset=UTF-8'"
        + "            name='DeptCodeMap'>"
        + "    <content contains='xml'>"
        + "      <mapping-table><col-def name='DeptCode'/><col-def name='Domain-Placement'/>"
        + "        <row><col>840116</col><col>OU=stuttgart,DC=x</col></row></mapping-table>"
        + "    </content>"
        + "  </resource>"
        + "  <resource content-type='text/ecmascript;charset=UTF-8' name='es-misc'>"
        + "    <content contains='xml'>function f(){}</content></resource>"
        + "</policy-library></children></driver-set-configuration>";

    @Test
    public void extractsMappingTablesFromExportResourcesOnly() {
        Element root = Xds.parse(EXPORT).getDocumentElement();
        Map<String, String> tables = MappingTableSource.fromElement(root);

        assertEquals("only the mapping-table resource, not the ecmascript one", 1, tables.size());
        assertTrue(tables.containsKey("DeptCodeMap"));
        String xml = tables.get("DeptCodeMap");
        assertTrue(xml.contains("<mapping-table"));
        assertTrue(xml.contains("840116"));
        assertFalse("inner table only, no <resource> wrapper", xml.contains("<resource"));
    }

    @Test
    public void readsCaseLocalMappingTablesDir() throws Exception {
        Path caseDir = tmp.newFolder("case").toPath();
        Path mt = Files.createDirectories(caseDir.resolve("mapping-tables"));
        Files.writeString(mt.resolve("RegionMap.xml"),
            "<mapping-table><col-def name='in'/><col-def name='out'/>"
            + "<row><col>CA</col><col>West</col></row></mapping-table>");

        Map<String, String> tables = MappingTableSource.fromCaseDir(caseDir);
        assertEquals(1, tables.size());
        assertTrue(tables.containsKey("RegionMap"));         // filename stem = name
        assertTrue(tables.get("RegionMap").contains("West"));
    }

    @Test
    public void noMappingTablesDirIsEmptyNotError() throws Exception {
        Path caseDir = tmp.newFolder("bare").toPath();
        assertTrue(MappingTableSource.fromCaseDir(caseDir).isEmpty());
    }
}
