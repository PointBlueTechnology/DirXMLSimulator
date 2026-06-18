package com.pointblue.dirxml.sim;

import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Assembles a driver's chain from an LDIF/LDAP export of the live Identity Vault:
 * DirXML-Policies linkage ({@code DN#order#setId}), XmlData policy content, and
 * the shim/GCV/filter attributes on the driver object.
 */
public class LdifDriverSourceTest {

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String tracePolicy(String desc) {
        return "<?xml version='1.0'?><policy><rule><description>" + desc + "</description>"
            + "<conditions/><actions><do-trace-message><arg-string>"
            + "<token-text>" + desc + "</token-text></arg-string></do-trace-message></actions></rule></policy>";
    }

    /** A driver subtree: driverset + driver + two subscriber-event policies + a schema map. */
    private static String ldif() {
        String driverConfig =
            "<driver-config name='x'><driver-options><configuration-values><definitions>"
            + "<definition name='apiEndpoint' display-name='E' type='string'><value>http://api</value></definition>"
            + "</definitions></configuration-values></driver-options></driver-config>";
        String filter = "<filter><filter-class class-name='User' publisher='sync' subscriber='sync'/></filter>";
        String driverGcv = "<configuration-values><definitions>"
            + "<definition name='gcv.drv' display-name='D' type='string'><value>driver-val</value></definition>"
            + "</definitions></configuration-values>";
        String setGcv = "<configuration-values><definitions>"
            + "<definition name='gcv.set' display-name='S' type='string'><value>set-val</value></definition>"
            + "</definitions></configuration-values>";

        return "version: 1\n\n"
            + "dn: cn=ds,o=system\n"
            + "objectClass: DirXML-DriverSet\n"
            + "DirXML-ConfigValues:: " + b64(setGcv) + "\n\n"

            + "dn: cn=Drv,cn=ds,o=system\n"
            + "objectClass: DirXML-Driver\n"
            + "cn: Drv\n"
            + "DirXML-JavaModule: com.example.MyShim\n"
            + "DirXML-ShimAuthServer: https://host\n"
            + "DirXML-ShimAuthID: svc\n"
            + "DirXML-ShimConfigInfo:: " + b64(driverConfig) + "\n"
            + "DirXML-ConfigValues:: " + b64(driverGcv) + "\n"
            + "DirXML-DriverFilter:: " + b64(filter) + "\n"
            // linkage: second event policy (order 1), first (order 0), schema map (set 0)
            + "DirXML-Policies: cn=B,cn=Subscriber,cn=Drv,cn=ds,o=system#1#4\n"
            + "DirXML-Policies: cn=A,cn=Subscriber,cn=Drv,cn=ds,o=system#0#4\n"
            + "DirXML-Policies: cn=Map,cn=Drv,cn=ds,o=system#0#0\n\n"

            + "dn: cn=A,cn=Subscriber,cn=Drv,cn=ds,o=system\n"
            + "objectClass: DirXML-Rule\n"
            + "XmlData:: " + b64(tracePolicy("PolicyA")) + "\n\n"

            + "dn: cn=B,cn=Subscriber,cn=Drv,cn=ds,o=system\n"
            + "objectClass: DirXML-Rule\n"
            + "XmlData:: " + b64(tracePolicy("PolicyB")) + "\n\n"

            + "dn: cn=Map,cn=Drv,cn=ds,o=system\n"
            + "objectClass: DirXML-Rule\n"
            + "XmlData:: " + b64("<?xml version='1.0'?><attr-name-map/>") + "\n\n";
    }

    private static LdifDriverSource source() {
        return LdifDriverSource.parse(ldif());
    }

    @Test
    public void findsTheDriver() {
        assertEquals(List.of("Drv"), source().driverNames());
    }

    @Test
    public void assemblesChainInSetThenOrder() {
        EngineContext ctx = EngineContext.create("\\T\\sys\\ds\\Drv");
        List<PolicyStage> sub = source().subscriberChain("Drv", ctx);
        // subscriber-event runs before schema-mapping; within the event set, order 0 (A) then 1 (B)
        assertEquals(3, sub.size());
        assertEquals("subscriber-event:A", sub.get(0).name());
        assertEquals("subscriber-event:B", sub.get(1).name());
        assertEquals("schema-mapping:Map", sub.get(2).name());
    }

    @Test
    public void chainActuallyRuns() {
        EngineContext ctx = EngineContext.create("\\T\\sys\\ds\\Drv");
        ChannelSimulator sim = new ChannelSimulator(ctx, new FakeDirectory())
            .addAll(source().subscriberChain("Drv", ctx));
        ChannelSimulator.Result r = sim.run(
            "<nds dtdversion='4.0'><input><add class-name='User' src-dn='\\x\\y'/></input></nds>");
        assertEquals(3, r.stages.size());
        assertTrue("policy A traced", r.fullTrace.contains("PolicyA"));
    }

    @Test
    public void extractsShimConfig() {
        ShimConfig cfg = source().shimConfig("Drv");
        assertEquals("com.example.MyShim", cfg.shimClass);
        assertEquals("cn=Drv,cn=ds,o=system", cfg.driverDn);
        assertEquals("https://host", cfg.authServer);
        assertEquals("svc", cfg.authId);
        assertNotNull("driver-options parsed from ShimConfigInfo", cfg.driverOptions);
    }

    @Test
    public void readsFilter() {
        Element f = source().filter("Drv");
        assertNotNull(f);
        assertEquals("filter", f.getLocalName());
        assertNotNull(Xds.firstByName(f, "filter-class"));
    }

    @Test
    public void mergesDriverSetAndDriverGcvs() {
        // construct() doesn't throw and both scopes are present (no exception ⇒ merged)
        source().gcvDefinitions("Drv");
    }

    @Test
    public void unknownDriverIsAClearError() {
        try {
            source().subscriberChain("Nope", EngineContext.create("\\T"));
            fail("expected an error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Nope"));
        }
    }
}
