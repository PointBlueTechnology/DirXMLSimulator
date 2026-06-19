package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Mapping-table extraction from the LDIF/live (DirXML-Resource) and project sources. */
public class MappingTableExtractionTest {

    private static final String TABLE =
        "<?xml version='1.0'?><mapping-table><col-def name='k'/><col-def name='v'/>"
        + "<row><col>CA</col><col>West</col></row></mapping-table>";

    private static LdifDriverSource.Entry resource(String dn, String contentAttr, String xml) {
        return new LdifDriverSource.Entry(dn, Map.of(
            "objectclass", List.of("Top", "DirXML-Resource"),
            contentAttr.toLowerCase(), List.of(xml)));
    }

    @Test
    public void extractsFromDirXmlDataLiveAttr() {
        // Live eDir / LDIF dump: content is in DirXML-Data.
        LdifDriverSource src = LdifDriverSource.fromEntries(List.of(
            resource("cn=LocCodeMap,cn=Library,cn=DS", "DirXML-Data", TABLE),
            resource("cn=es-misc,cn=Library,cn=DS", "DirXML-Data", "<content>function(){}</content>")));
        Map<String, String> t = src.mappingTables();
        assertEquals(1, t.size());
        assertTrue(t.containsKey("LocCodeMap"));      // keyed by cn
        assertTrue(t.get("LocCodeMap").contains("West"));
    }

    @Test
    public void alsoAcceptsXmlDataVariant() {
        LdifDriverSource src = LdifDriverSource.fromEntries(List.of(
            resource("cn=DeptMap,cn=Library,cn=DS", "XmlData", TABLE)));
        assertEquals(1, src.mappingTables().size());
        assertTrue(src.mappingTables().containsKey("DeptMap"));
    }

    @Test
    public void designerProjectExtractsMappingTables() throws Exception {
        // Validates against a real workspace when present; skipped otherwise.
        Path ws = Path.of(System.getProperty("user.home"), "designer_workspace", "test11");
        assumeTrue("needs a local Designer workspace with mapping tables",
            java.nio.file.Files.isDirectory(ws));

        Map<String, String> tables = DesignerProject.load(ws).mappingTables();
        assertFalse("expected mapping tables in the project", tables.isEmpty());
        assertTrue(tables.values().stream().allMatch(x -> x.contains("<mapping-table")));
    }
}
