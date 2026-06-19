package com.pointblue.dirxml.sim;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects the mapping tables available to a case — keyed by table name, value =
 * the {@code <mapping-table>} XML the engine parses — from two places:
 *
 * <ul>
 *   <li>a case-local {@code mapping-tables/} directory (one {@code <name>.xml} per
 *       table; the filename is the table name), and</li>
 *   <li>the config source: any {@code <resource content-type="…mapping-table+xml…">}
 *       in a driver / driver-set export (the Library resources), as confirmed
 *       against {@code RFI-DriverSet.xml}.</li>
 * </ul>
 *
 * The case-local directory overrides the config-source tables. See
 * {@code docs/mapping-tables-design.md}.
 */
public final class MappingTableSource {

    private MappingTableSource() {
    }

    private static final String MAPPING_TABLE_CT = "mapping-table";

    /** Tables from a case's {@code mapping-tables/} dir: filename stem → file XML. */
    public static Map<String, String> fromCaseDir(Path caseDir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Path dir = caseDir.resolve("mapping-tables");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                String name = f.getFileName().toString().replaceFirst("\\.xml$", "");
                out.put(name, Files.readString(f));
            }
        }
        return out;
    }

    /**
     * Tables embedded as resources anywhere under {@code root} (a driver or
     * driver-set export / project / LDIF tree): name = the resource's {@code name=},
     * value = its inner {@code <mapping-table>} serialized.
     */
    public static Map<String, String> fromElement(Element root) {
        Map<String, String> out = new LinkedHashMap<>();
        if (root == null) {
            return out;
        }
        for (Element res : Xds.descendantsByName(root, "resource")) {
            String ct = res.getAttribute("content-type");
            if (ct == null || !ct.contains(MAPPING_TABLE_CT)) {
                continue;
            }
            String name = res.getAttribute("name");
            if (name == null || name.isBlank()) {
                continue;
            }
            var tables = Xds.descendantsByName(res, "mapping-table");
            if (!tables.isEmpty()) {
                out.put(name, Xds.serializeElement(tables.get(0)));
            }
        }
        return out;
    }
}
