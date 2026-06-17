package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.engine.gcv.GCDefinitions;

import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads a NetIQ / OpenText IDM <b>Designer project</b> on disk and assembles a
 * driver's channel chain — the same result as {@link DriverExport}, but sourced
 * from the project (which, unlike an export, also carries the full schema and
 * ECMAScript resources).
 *
 * <p>Designer stores each object as {@code <ID>.<Type>_} metadata (a
 * {@code CObject} with ordered {@code <relations>}) plus {@code <ID>_contents.xml}
 * payload. A driver's channels ({@code .Subscriber_}/{@code .Publisher_}) declare
 * their policy sets as {@code <relations name="Idm:…Policies" type="Reference"
 * key="#ID.ScriptPolicy_"/>} in execution order; the driver itself declares
 * schema-mapping, input/output, and the filter. Resolving those keys to their
 * {@code _contents.xml} and ordering them by the standard channel sequence yields
 * the chain.
 *
 * <p>(The on-disk format is documented by the {@code dirxml-designer-workspace}
 * skill — github.com/jcombs-pointblue/dirxml-designer-workspace.)
 */
public final class DesignerProject {

    private static final class DriverRef {
        final String name;
        final Element meta;   // parsed .Driver_ CObject
        final Path dir;       // sibling <DriverID>/ (unused directly; index is global)
        DriverRef(String name, Element meta, Path dir) {
            this.name = name;
            this.meta = meta;
            this.dir = dir;
        }
    }

    private final Map<String, Path> contentsById = new LinkedHashMap<>();
    private final Map<String, Path> metaById = new LinkedHashMap<>();
    private final Map<String, DriverRef> driversByName = new LinkedHashMap<>();
    private final List<Path> schemaFiles = new ArrayList<>();
    private final List<Path> configValueFiles = new ArrayList<>();

    private DesignerProject() {}

    public static DesignerProject load(Path projectDir) {
        DesignerProject p = new DesignerProject();
        try (Stream<Path> walk = Files.walk(projectDir)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                String fn = f.getFileName().toString();
                if (fn.endsWith("_DirXML-ConfigValues.xml")) {
                    p.configValueFiles.add(f);
                } else if (fn.endsWith("_schema.xml")) {
                    p.schemaFiles.add(f);
                } else if (fn.endsWith("_contents.xml")) {
                    p.contentsById.put(fn.substring(0, fn.length() - "_contents.xml".length()), f);
                } else if (fn.endsWith(".Driver_")) {
                    p.indexDriver(f);
                } else {
                    int dot = fn.lastIndexOf('.');
                    if (dot > 0 && fn.endsWith("_")) {   // <ID>.<Type>_
                        p.metaById.putIfAbsent(fn.substring(0, dot), f);
                    }
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to read Designer project " + projectDir + ": " + e, e);
        }
        return p;
    }

    private void indexDriver(Path driverFile) {
        try {
            String fn = driverFile.getFileName().toString();
            String id = fn.substring(0, fn.length() - ".Driver_".length());
            Element meta = parseMeta(driverFile);
            String name = meta.getAttribute("name");
            driversByName.put(name, new DriverRef(name, meta,
                driverFile.resolveSibling(id)));
        } catch (Exception e) {
            System.err.println("warning: could not index driver " + driverFile + ": " + e);
        }
    }

    public List<String> driverNames() {
        return new ArrayList<>(driversByName.keySet());
    }

    /** The project's eDirectory schema ({@code *_schema.xml}); empty if none found. */
    public SchemaModel schema() {
        return schemaFiles.isEmpty() ? SchemaModel.empty() : SchemaModel.parseFile(schemaFiles.get(0));
    }

    /**
     * GCVs for a driver: the resolved values from the DriverSet- and Driver-scope
     * {@code *_DirXML-ConfigValues.xml} files (driverset first, driver overrides).
     */
    public GCDefinitions gcvDefinitions(String driver) {
        GCDefinitions merged = new GCDefinitions();
        DriverRef d = driver(driver);
        String driverId = d.dir.getFileName().toString();
        String driverSetId = d.dir.getParent().getFileName().toString();
        mergeConfigValues(merged, driverSetId + "_");   // DriverSet-scope GCVs (base)
        mergeConfigValues(merged, driverId + "_");      // Driver-scope GCVs (override)
        return merged;
    }

    private void mergeConfigValues(GCDefinitions merged, String prefix) {
        for (Path f : configValueFiles) {
            if (f.getFileName().toString().startsWith(prefix)) {
                try {
                    // File root is <configuration-values>; construct(Document) finds it.
                    merged.merge(GCDefinitions.construct(Xds.parseFile(f)));
                } catch (Throwable t) {
                    System.err.println("warning: skipping GCV file " + f.getFileName() + ": " + t);
                }
            }
        }
    }

    // ---- chain assembly ------------------------------------------------------

    public List<PolicyStage> subscriberChain(String driver, EngineContext ctx) {
        DriverRef d = driver(driver);
        List<PolicyStage> stages = new ArrayList<>();
        Element sub = channelMeta(d, "Idm:Subscriber");
        // eDir -> app: event, matching, create, placement, command, schema-map, output
        addSet(stages, sub, "Idm:EventPolicies", "subscriber-event", ctx);
        addSet(stages, sub, "Idm:MatchingPolicies", "subscriber-matching", ctx);
        addSet(stages, sub, "Idm:CreatePolicies", "subscriber-create", ctx);
        addSet(stages, sub, "Idm:PlacementPolicies", "subscriber-placement", ctx);
        addSet(stages, sub, "Idm:CommandPolicies", "subscriber-command", ctx);
        addSet(stages, d.meta, "Idm:MappingPolicies", "schema-mapping", ctx);
        addSet(stages, d.meta, "Idm:OutputPolicies", "output-transform", ctx);
        return stages;
    }

    public List<PolicyStage> publisherChain(String driver, EngineContext ctx) {
        DriverRef d = driver(driver);
        List<PolicyStage> stages = new ArrayList<>();
        Element pub = channelMeta(d, "Idm:Publisher");
        // app -> eDir: input, schema-map, event, matching, create, placement, command
        addSet(stages, d.meta, "Idm:InputPolicies", "input-transform", ctx);
        addSet(stages, d.meta, "Idm:MappingPolicies", "schema-mapping", ctx);
        addSet(stages, pub, "Idm:EventPolicies", "publisher-event", ctx);
        addSet(stages, pub, "Idm:MatchingPolicies", "publisher-matching", ctx);
        addSet(stages, pub, "Idm:CreatePolicies", "publisher-create", ctx);
        addSet(stages, pub, "Idm:PlacementPolicies", "publisher-placement", ctx);
        addSet(stages, pub, "Idm:CommandPolicies", "publisher-command", ctx);
        return stages;
    }

    /** The driver's filter element, or null. */
    public Element filter(String driver) {
        for (String key : relationKeys(driver(driver).meta, "Idm:Filter")) {
            Path c = contentsById.get(idOf(key));
            if (c != null) {
                Element root = Xds.parseFile(c).getDocumentElement();
                if (root != null && "filter".equals(root.getLocalName())) {
                    return root;
                }
            }
        }
        return null;
    }

    /**
     * ECMAScript resource sources in the project (paired with an
     * {@code .ECMAScriptResource_} metadata object). Project-wide — extra function
     * definitions in scope are harmless.
     */
    public List<String> ecmaScriptSources() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Path> e : contentsById.entrySet()) {
            Path meta = metaById.get(e.getKey());
            if (meta != null && meta.getFileName().toString().endsWith(".ECMAScriptResource_")) {
                try {
                    out.add(new String(Files.readAllBytes(e.getValue()), "UTF-8"));
                } catch (Exception ignore) {
                    // skip
                }
            }
        }
        return out;
    }

    // ---- helpers -------------------------------------------------------------

    private DriverRef driver(String name) {
        DriverRef d = driversByName.get(name);
        if (d == null) {
            throw new IllegalArgumentException("No driver '" + name + "' in project; have: " + driverNames());
        }
        return d;
    }

    /** Resolve a driver's Subscriber/Publisher child to its parsed metadata element. */
    private Element channelMeta(DriverRef d, String relation) {
        for (String key : relationKeys(d.meta, relation)) {
            Path meta = metaById.get(idOf(key));
            if (meta != null) {
                return parseMeta(meta);
            }
        }
        return null;
    }

    private void addSet(List<PolicyStage> stages, Element owner, String relation, String setName, EngineContext ctx) {
        if (owner == null) {
            return;
        }
        for (String key : relationKeys(owner, relation)) {
            Path contents = contentsById.get(idOf(key));
            if (contents == null) {
                continue;
            }
            try {
                Element policy = PolicyLoader.load(contents);
                stages.add(PolicyStage.fromElement(setName + ":" + idOf(key), policy, ctx));
            } catch (Exception e) {
                System.err.println("warning: skipping policy " + key + " (" + setName + "): " + e);
            }
        }
    }

    /** Ordered relation keys of a given name with type="Reference" (channel attachments). */
    private static List<String> relationKeys(Element meta, String relationName) {
        List<String> keys = new ArrayList<>();
        if (meta == null) {
            return keys;
        }
        for (Element rel : Xds.childrenByName(meta, "relations")) {
            if (relationName.equals(rel.getAttribute("name"))
                    && !"BackReference".equals(rel.getAttribute("type"))) {
                String key = rel.getAttribute("key");
                if (key != null && !key.isEmpty() && !keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /** key "#OES3U2HZ.ScriptPolicy_" -> "OES3U2HZ". */
    private static String idOf(String key) {
        String s = key.startsWith("#") ? key.substring(1) : key;
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static Element parseMeta(Path metaFile) {
        try {
            return Xds.parse(new String(Files.readAllBytes(metaFile), "UTF-8")).getDocumentElement();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse " + metaFile + ": " + e, e);
        }
    }
}
