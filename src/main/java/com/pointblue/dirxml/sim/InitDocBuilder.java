package com.pointblue.dirxml.sim;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles the {@code <init-params>} document a driver shim's {@code init()}
 * lifecycle expects — the same shape Designer/the engine hands a connector:
 *
 * <pre>{@code
 * <nds dtdversion="4.0" ndsversion="8.x">
 *   <source>...</source>
 *   <input>
 *     <init-params src-dn="\TREE\system\driverset\Driver">
 *       <authentication-info><server/><user/><password/></authentication-info>
 *       <driver-options>...<configuration-values>...(GCVs)...</configuration-values></driver-options>
 *       <publisher-options>...</publisher-options>
 *       <subscriber-options>...</subscriber-options>
 *       <subscriber-state/>
 *     </init-params>
 *   </input>
 * </nds>
 * }</pre>
 *
 * <p>A pure transform: connection params and option maps come from the loaded
 * driver config (export or Designer project), the password from the existing
 * named-password supply, and the GCV definitions element straight from the
 * export. DOM-only and side-effect-free so it is unit-testable without a shim.
 * The {@link ShimAdapter} wraps the result with {@code new XmlDocument(doc)}.
 *
 * <p>See {@code docs/shim-testing-design.md}. This collaborator is optional: it
 * is only invoked when a case configures a shim.
 */
public final class InitDocBuilder {

    private String driverDn = "";
    private String server;
    private String user;
    private String password;
    private final Map<String, String> driverOptions = new LinkedHashMap<>();
    private final Map<String, String> publisherOptions = new LinkedHashMap<>();
    private final Map<String, String> subscriberOptions = new LinkedHashMap<>();
    private Element driverOptionsSource;
    private Element subscriberOptionsSource;
    private Element publisherOptionsSource;
    private Element gcvDefinitions;

    /** {@code src-dn} on {@code <init-params>} — the driver's DN. */
    public InitDocBuilder driverDn(String dn) {
        this.driverDn = dn == null ? "" : dn;
        return this;
    }

    /** Connection/auth. Any null leaves that element out. The password is the resolved secret. */
    public InitDocBuilder authentication(String server, String user, String password) {
        this.server = server;
        this.user = user;
        this.password = password;
        return this;
    }

    public InitDocBuilder driverOption(String name, String value) {
        put(driverOptions, name, value);
        return this;
    }

    public InitDocBuilder publisherOption(String name, String value) {
        put(publisherOptions, name, value);
        return this;
    }

    public InitDocBuilder subscriberOption(String name, String value) {
        put(subscriberOptions, name, value);
        return this;
    }

    /**
     * Populate from a {@link ShimConfig} extracted from a driver export or
     * Designer project — the faithful path, since the init doc is defined there.
     * The {@code password} comes from the named-password channel (never the source).
     * Any null field in the config is simply skipped.
     */
    public InitDocBuilder shimConfig(ShimConfig cfg, String password) {
        if (cfg == null) {
            return this;
        }
        if (cfg.driverDn != null) {
            driverDn(cfg.driverDn);
        }
        // server/user from the source; password from the secret channel
        authentication(cfg.authServer, cfg.authId, password);
        driverOptionsFrom(cfg.driverOptions);
        subscriberOptionsFrom(cfg.subscriberOptions);
        publisherOptionsFrom(cfg.publisherOptions);
        return this;
    }

    /**
     * Embed a source {@code <driver-options>} block (or its {@code <configuration-values>})
     * verbatim — the resolved {@code <definitions>} the shim reads in production.
     * The element is imported, so the source is untouched.
     */
    public InitDocBuilder driverOptionsFrom(Element optionsBlock) {
        this.driverOptionsSource = optionsBlock;
        return this;
    }

    public InitDocBuilder subscriberOptionsFrom(Element optionsBlock) {
        this.subscriberOptionsSource = optionsBlock;
        return this;
    }

    public InitDocBuilder publisherOptionsFrom(Element optionsBlock) {
        this.publisherOptionsSource = optionsBlock;
        return this;
    }

    /**
     * GCV definitions to embed under {@code <driver-options>}. Accepts either a
     * {@code <configuration-values>} element (embedded as-is) or a
     * {@code <definitions>} element (wrapped in {@code <configuration-values>}).
     * The element is imported, so the source document is untouched.
     */
    public InitDocBuilder gcvDefinitions(Element configValuesOrDefinitions) {
        this.gcvDefinitions = configValuesOrDefinitions;
        return this;
    }

    public Document build() {
        Document doc = Xds.parse(
            "<nds dtdversion=\"4.0\" ndsversion=\"8.x\">"
            + "<source><product>DirXML</product>"
            + "<contact>PointBlue DirXMLSimulator</contact></source>"
            + "<input><init-params>"
            + "<authentication-info/><driver-options/>"
            + "<publisher-options/><subscriber-options/><subscriber-state/>"
            + "</init-params></input></nds>");

        Element initParams = Xds.firstByName(doc.getDocumentElement(), "init-params");
        if (!driverDn.isEmpty()) {
            initParams.setAttribute("src-dn", driverDn);
        }

        Element auth = Xds.firstByName(initParams, "authentication-info");
        appendTextChild(doc, auth, "server", server);
        appendTextChild(doc, auth, "user", user);
        appendTextChild(doc, auth, "password", password);

        Element drvOpts = Xds.firstByName(initParams, "driver-options");
        embedOptionsBlock(doc, drvOpts, driverOptionsSource);
        appendOptions(doc, drvOpts, driverOptions);
        if (gcvDefinitions != null) {
            drvOpts.appendChild(importGcv(doc));
        }

        Element pubOpts = Xds.firstByName(initParams, "publisher-options");
        embedOptionsBlock(doc, pubOpts, publisherOptionsSource);
        appendOptions(doc, pubOpts, publisherOptions);

        Element subOpts = Xds.firstByName(initParams, "subscriber-options");
        embedOptionsBlock(doc, subOpts, subscriberOptionsSource);
        appendOptions(doc, subOpts, subscriberOptions);

        return doc;
    }

    /** Convenience: build and serialize (for {@code shim-init.xml} dumps / inspection). */
    public String buildString() {
        return Xds.serialize(build());
    }

    // ---- helpers --------------------------------------------------------

    private static void put(Map<String, String> m, String name, String value) {
        if (name != null && !name.isEmpty()) {
            m.put(name, value == null ? "" : value);
        }
    }

    private static void appendOptions(Document doc, Element parent, Map<String, String> opts) {
        for (Map.Entry<String, String> e : opts.entrySet()) {
            appendTextChild(doc, parent, e.getKey(), e.getValue());
        }
    }

    /** Append {@code <name>value</name>}; skips when value is null (element omitted entirely). */
    private static void appendTextChild(Document doc, Element parent, String name, String value) {
        if (value == null) {
            return;
        }
        Element e = doc.createElement(name);
        if (!value.isEmpty()) {
            e.appendChild(doc.createTextNode(value));
        }
        parent.appendChild(e);
    }

    /**
     * Embed a source options block into a target {@code <*-options>} element: copy
     * its {@code <configuration-values>} subtree verbatim, and also flatten each
     * {@code <definition name="X"><value>V</value>} to a flat {@code <X>V</X>}
     * child — shims read one form or the other, so emit both (mirrors how the
     * engine hands options to a connector). {@code sourceBlock} may be the
     * {@code <*-options>} element or its {@code <configuration-values>} directly.
     */
    private static void embedOptionsBlock(Document doc, Element target, Element sourceBlock) {
        if (sourceBlock == null) {
            return;
        }
        Element configValues = "configuration-values".equals(sourceBlock.getLocalName())
                ? sourceBlock
                : Xds.firstByName(sourceBlock, "configuration-values");
        if (configValues == null) {
            return;
        }
        // flat name=value children from the resolved definitions
        Element defs = Xds.firstByName(configValues, "definitions");
        if (defs != null) {
            for (Element def : Xds.childrenByName(defs, "definition")) {
                String name = def.getAttribute("name");
                Element value = Xds.firstByName(def, "value");
                if (!name.isEmpty() && value != null) {
                    appendTextChild(doc, target, name, Xds.text(value));
                }
            }
        }
        // and the configuration-values block itself, for shims that read it
        target.appendChild(doc.importNode(configValues, true));
    }

    private Node importGcv(Document doc) {
        if ("configuration-values".equals(gcvDefinitions.getLocalName())) {
            return doc.importNode(gcvDefinitions, true);
        }
        // a bare <definitions> — wrap it
        Element cv = doc.createElement("configuration-values");
        cv.appendChild(doc.importNode(gcvDefinitions, true));
        return cv;
    }
}
