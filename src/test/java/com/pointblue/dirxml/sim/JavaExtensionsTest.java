package com.pointblue.dirxml.sim;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * DirXML Java extension functions: a namespace bound to a Java class, calling its
 * static methods. They work when the class is on the classpath; when not, the
 * harness reports the missing class and still fails gracefully.
 */
public class JavaExtensionsTest {

    private static final String INPUT =
        "<nds dtdversion='4.0'><input><add class-name='User' src-dn='\\x\\y'/></input></nds>";

    private static String javaPolicy(String fqcn, String call) {
        return "<policy xmlns:j='" + JavaExtensions.JAVA_NS + fqcn + "'>" +
            "<rule><description>java ext</description><conditions/><actions>" +
            "<do-set-dest-attr-value name='R'><arg-value type='string'>" +
            "<token-xpath expression=\"" + call + "\"/></arg-value></do-set-dest-attr-value>" +
            "</actions></rule></policy>";
    }

    @Test
    public void detectsReferencedAndMissingClasses() {
        org.w3c.dom.Element present = PolicyLoader.load(javaPolicy("java.lang.Math", "j:max(3,7)"));
        assertEquals(java.util.List.of("java.lang.Math"), JavaExtensions.referencedClasses(present));
        assertTrue("java.lang.Math is on the classpath", JavaExtensions.missingClasses(present).isEmpty());

        org.w3c.dom.Element missing = PolicyLoader.load(javaPolicy("com.acme.DoesNotExist", "j:bar('z')"));
        assertEquals(java.util.List.of("com.acme.DoesNotExist"), JavaExtensions.missingClasses(missing));
    }

    @Test
    public void javaExtensionRunsWhenClassPresent() {
        EngineContext ctx = EngineContext.create("\\T\\sys\\DS\\D");
        ChannelSimulator.Result r = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("t", PolicyLoader.load(javaPolicy("java.lang.Math", "j:max(3,7)")), ctx))
            .run(INPUT);
        assertNull(r.stages.get(0).error);
        assertTrue("Math.max(3,7)=7 in output: " + r.finalXds, r.finalXds.contains("7"));
    }

    @Test
    public void missingJavaClassFailsGracefullyAndIsReported() {
        EngineContext ctx = EngineContext.create("\\T\\sys\\DS\\D");
        ChannelSimulator sim = new ChannelSimulator(ctx, new FakeDirectory())
            .add(PolicyStage.fromElement("t", PolicyLoader.load(javaPolicy("com.acme.DoesNotExist", "j:bar('z')")), ctx));
        // Reported up front…
        assertEquals(java.util.List.of("com.acme.DoesNotExist"), sim.missingJavaClasses());
        // …and the run doesn't crash — it's a captured stage error.
        ChannelSimulator.Result r = sim.run(INPUT);
        assertNotNull(r.stages.get(0).error);
    }
}
