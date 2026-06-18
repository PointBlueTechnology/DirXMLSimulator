package com.pointblue.dirxml.sim;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Extracting the shim init parameters from where they are already defined — a
 * driver export and a Designer project — and feeding them to {@link InitDocBuilder}.
 */
public class ShimConfigTest {

    private static Element initParams(Document doc) {
        return Xds.firstByName(doc.getDocumentElement(), "init-params");
    }

    private static String childText(Element parent, String name) {
        Element c = Xds.firstByName(parent, name);
        return c == null ? null : Xds.text(c);
    }

    // ---- driver export -------------------------------------------------

    private static final String EXPORT =
        "<driver-configuration dn='cn=Okta1,cn=ds,o=system' name='Okta1'>"
        + "<java-module value='com.example.RestDriverShim'/>"
        + "<authentication-info><server>https://host/token</server><user>client-id</user></authentication-info>"
        // an empty placeholder block the engine also carries — must NOT be picked
        + "<shim-config-info-xml><driver-config name='x'><driver-options/></driver-config></shim-config-info-xml>"
        + "<driver-options><configuration-values><definitions>"
        + "<definition name='endPointSecret' type='string'><value>tok123</value></definition>"
        + "</definitions></configuration-values></driver-options>"
        + "<subscriber-options><configuration-values><definitions/></configuration-values></subscriber-options>"
        + "<publisher-options><configuration-values><definitions>"
        + "<definition name='polling-interval' type='integer'><value>60</value></definition>"
        + "</definitions></configuration-values></publisher-options>"
        + "</driver-configuration>";

    @Test
    public void exportExposesShimClassAndDn() {
        DriverExport ex = DriverExport.load(Xds.parse(EXPORT));
        assertEquals("com.example.RestDriverShim", ex.shimClass());
        assertEquals("cn=Okta1,cn=ds,o=system", ex.driverDn());
    }

    @Test
    public void exportPicksTheOptionBlockWithDefinitions() {
        ShimConfig cfg = DriverExport.load(Xds.parse(EXPORT)).shimConfig();
        assertNotNull(cfg.driverOptions);
        // the real block (with endPointSecret), not the empty placeholder in shim-config-info-xml
        Element cv = Xds.firstByName(cfg.driverOptions, "configuration-values");
        assertNotNull("picked a block that actually defines values", Xds.firstByName(cv, "definition"));
    }

    @Test
    public void exportInitDocCarriesOptionsAndAuth() {
        ShimConfig cfg = DriverExport.load(Xds.parse(EXPORT)).shimConfig();
        Document doc = new InitDocBuilder().shimConfig(cfg, "the-secret").build();
        Element ip = initParams(doc);

        assertEquals("cn=Okta1,cn=ds,o=system", ip.getAttribute("src-dn"));

        Element auth = Xds.firstByName(ip, "authentication-info");
        assertEquals("https://host/token", childText(auth, "server"));
        assertEquals("client-id", childText(auth, "user"));
        assertEquals("the-secret", childText(auth, "password"));

        Element drv = Xds.firstByName(ip, "driver-options");
        // flattened from the definition…
        assertEquals("tok123", childText(drv, "endPointSecret"));
        // …and the configuration-values block embedded for shims that read it
        assertNotNull(Xds.firstByName(drv, "configuration-values"));

        Element pub = Xds.firstByName(ip, "publisher-options");
        assertEquals("60", childText(pub, "polling-interval"));
    }

    // ---- Designer project ----------------------------------------------

    private static final String COBJECT =
        "<com.novell.designer.model:CObject xmlns:com.novell.designer.model="
        + "'http://com.novell.designer.model' name='%s' type='%s'>%s</com.novell.designer.model:CObject>";

    /** Driver-config XML stored (escaped) in the DirXML-ShimConfigInfo attribute. */
    private static final String DRIVER_CONFIG =
        "<driver-config name='cc'>"
        + "<driver-options><configuration-values><definitions>"
        + "<definition name='apiEndpoint' type='string'><value>https://api/rest</value></definition>"
        + "</definitions></configuration-values></driver-options>"
        + "<subscriber-options><configuration-values><definitions/></configuration-values></subscriber-options>"
        + "<publisher-options><configuration-values><definitions>"
        + "<definition name='polling-interval' type='integer'><value>90</value></definition>"
        + "</definitions></configuration-values></publisher-options>"
        + "</driver-config>";

    private static String xmlAttrEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&apos;").replace("\"", "&quot;");
    }

    @Test
    public void projectExposesShimParamsFromDriverObject() throws Exception {
        Path proj = Files.createTempDirectory("dxshim");
        Path ds = proj.resolve("Model/EdirOrphan/DSID");
        String attrs =
            "<attributes attrName='DirXML-JavaModule' value='com.example.CCShim'/>"
            + "<attributes attrName='DirXML-ShimAuthServer' value='https://cc/host'/>"
            + "<attributes attrName='DirXML-ShimAuthID' value='svc-account'/>"
            + "<attributes attrName='DirXML-ShimConfigInfo' value='" + xmlAttrEscape(DRIVER_CONFIG) + "'/>";
        Files.createDirectories(ds);
        Files.write(ds.resolve("DRV.Driver_"),
            String.format(COBJECT, "CC", "Driver", attrs).getBytes("UTF-8"));

        DesignerProject p = DesignerProject.load(proj);
        ShimConfig cfg = p.shimConfig("CC");

        assertEquals("com.example.CCShim", cfg.shimClass);
        assertEquals("https://cc/host", cfg.authServer);
        assertEquals("svc-account", cfg.authId);
        assertNotNull("driver-options parsed out of ShimConfigInfo", cfg.driverOptions);

        Document doc = new InitDocBuilder().shimConfig(cfg, "vault-secret").build();
        Element ip = initParams(doc);
        assertEquals("https://api/rest", childText(Xds.firstByName(ip, "driver-options"), "apiEndpoint"));
        assertEquals("90", childText(Xds.firstByName(ip, "publisher-options"), "polling-interval"));
        assertEquals("vault-secret", childText(Xds.firstByName(ip, "authentication-info"), "password"));
    }
}
