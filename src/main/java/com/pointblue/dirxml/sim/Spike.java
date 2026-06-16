package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.driver.XmlDocument;
import com.novell.nds.dirxml.engine.EngineTrace;
import com.novell.nds.dirxml.engine.XdsCommandProcessor;
import com.novell.nds.dirxml.engine.XdsQueryProcessor;
import com.novell.nds.dirxml.engine.gcv.GCDefinitions;
import com.novell.nds.dirxml.engine.rules.DirXMLScriptProcessor;
import com.novell.nds.dirxml.engine.rules.RuleDynamicContext;
import com.novell.nds.dirxml.engine.rules.RuleStaticContext;
import com.novell.nds.dirxml.util.XdsDN;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Spike: proves the three load-bearing facts for the DirXML policy harness.
 *
 *   1. GCDefinitions can be constructed standalone (empty via new GCDefinitions()).
 *   2. The engine's own policy interpreter (DirXMLScriptProcessor) runs with no
 *      eDirectory / engine boot -- only mocked Xds*Processor seams.
 *   3. We can STEP the chain: by NOT using setNextRule and instead calling each
 *      stage's applyRules() ourselves, we capture the intermediate XDS document
 *      between stages.
 */
public class Spike {

    /** Stage 1 policy: stamp L=stage1-was-here onto the add. */
    private static final String POLICY_1 =
        "<policy>" +
        "  <rule>" +
        "    <description>stage1 stamp L</description>" +
        "    <conditions/>" +
        "    <actions>" +
        "      <do-set-dest-attr-value name='L'>" +
        "        <arg-value type='string'><token-text>stage1-was-here</token-text></arg-value>" +
        "      </do-set-dest-attr-value>" +
        "    </actions>" +
        "  </rule>" +
        "</policy>";

    /** Stage 2 policy: stamp Title=stage2-was-here, proving state carries forward. */
    private static final String POLICY_2 =
        "<policy>" +
        "  <rule>" +
        "    <description>stage2 stamp Title</description>" +
        "    <conditions/>" +
        "    <actions>" +
        "      <do-set-dest-attr-value name='Title'>" +
        "        <arg-value type='string'><token-text>stage2-was-here</token-text></arg-value>" +
        "      </do-set-dest-attr-value>" +
        "    </actions>" +
        "  </rule>" +
        "</policy>";

    private static final String INPUT_XDS =
        "<nds dtdversion='4.0'>" +
        "  <input>" +
        "    <add class-name='User' src-dn='\\ACME\\users\\jdoe'>" +
        "      <add-attr attr-name='Given Name'><value>John</value></add-attr>" +
        "      <add-attr attr-name='Surname'><value>Doe</value></add-attr>" +
        "    </add>" +
        "  </input>" +
        "</nds>";

    public static void main(String[] args) throws Exception {
        System.out.println("=== DirXML policy harness spike ===");

        // The driver-facing Xds*Processor wrappers create a Trace, which requires
        // a registered TraceInterface impl. This is also our agent-readable log.
        com.novell.nds.dirxml.driver.Trace.registerImpl(CaptureTrace.class, 100);
        CaptureTrace.reset();

        // --- (1) static context: no live engine, no eDirectory ---
        char[] delims = XdsDN.getDelims("slash").toCharArray();

        // Engine policy trace captured into a buffer (no native DSTrace/file deps).
        CaptureEngineTrace tracer = new CaptureEngineTrace();
        tracer.setLevel(4);
        GCDefinitions gcv = new GCDefinitions(); // empty GCV set -- the engine's own fallback
        System.out.println("GCDefinitions constructed: " + gcv);

        RuleStaticContext staticCtx = new RuleStaticContext(
            "\\ACME\\system\\DriverSet\\TestDriver", // driverDN
            delims,                                   // srcDelims
            delims,                                   // destDelims
            true,                                     // fromNDS
            Spike.class.getClassLoader(),             // loader
            tracer,                                   // tracer
            gcv,                                      // gcvDefs
            null);                                    // driver (null -> exercise the no-Driver path)

        // --- (2) dynamic context: mocked query/command seams ---
        XdsQueryProcessor mockQuery = doc -> emptyResult();   // policies here issue no queries
        XdsCommandProcessor mockCmd = doc -> emptyResult();
        RuleDynamicContext dynCtx = new RuleDynamicContext(
            mockQuery, mockQuery, mockCmd, mockCmd, null /* JCContext */);

        // --- build the two stage processors (do NOT chain them) ---
        DirXMLScriptProcessor stage1 = new DirXMLScriptProcessor(parseElement(POLICY_1), staticCtx);
        DirXMLScriptProcessor stage2 = new DirXMLScriptProcessor(parseElement(POLICY_2), staticCtx);

        // --- (3) STEP through the chain, capturing the doc between stages ---
        Document doc = new XmlDocument(INPUT_XDS).getDocument();
        System.out.println("\n--- input ---\n" + serialize(doc));

        doc = stage1.applyRules(doc, dynCtx);
        System.out.println("\n--- after stage 1 ---\n" + serialize(doc));

        doc = stage2.applyRules(doc, dynCtx);
        System.out.println("\n--- after stage 2 ---\n" + serialize(doc));

        // --- verdict ---
        String out = serialize(doc);
        boolean s1 = out.contains("stage1-was-here");
        boolean s2 = out.contains("stage2-was-here");
        System.out.println("\n=== RESULT ===");
        System.out.println("stage1 effect present: " + s1);
        System.out.println("stage2 effect present: " + s2);
        System.out.println((s1 && s2) ? "SPIKE PASSED" : "SPIKE FAILED");

        String trace = tracer.dump();
        System.out.println("\n=== captured engine trace (" + trace.length() + " chars) ===");
        System.out.println(trace.isEmpty() ? "(empty)" : trace);
    }

    private static Element parseElement(String xml) throws Exception {
        return new XmlDocument(xml).getDocument().getDocumentElement();
    }

    private static Document emptyResult() {
        try {
            return new XmlDocument("<nds dtdversion='4.0'><output><status level='success'/></output></nds>")
                .getDocument();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String serialize(Document doc) {
        return new XmlDocument(doc).getDocumentString();
    }
}
