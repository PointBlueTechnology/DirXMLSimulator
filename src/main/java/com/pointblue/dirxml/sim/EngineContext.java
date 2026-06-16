package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.driver.Trace;
import com.novell.nds.dirxml.engine.XdsCommandProcessor;
import com.novell.nds.dirxml.engine.XdsQueryProcessor;
import com.novell.nds.dirxml.engine.gcv.GCDefinitions;
import com.novell.nds.dirxml.engine.rules.RuleDynamicContext;
import com.novell.nds.dirxml.engine.rules.RuleStaticContext;
import com.novell.nds.dirxml.util.XdsDN;

import org.w3c.dom.Document;

/**
 * Holds the engine-side context needed to run policies headlessly: the
 * {@link RuleStaticContext} (driver-level) and a captured {@link CaptureEngineTrace}.
 * No eDirectory, no engine boot, no live Driver.
 *
 * <p>Build one per simulated channel run, supply the query/command seams (e.g. a
 * {@link FakeDirectory}), then create per-operation {@link RuleDynamicContext}s.
 */
public final class EngineContext {

    static {
        // The driver-facing Xds*Processor wrappers require a registered Trace impl.
        Trace.registerImpl(CaptureTrace.class, 100);
    }

    private final RuleStaticContext staticCtx;
    private final CaptureEngineTrace tracer;

    private EngineContext(RuleStaticContext staticCtx, CaptureEngineTrace tracer) {
        this.staticCtx = staticCtx;
        this.tracer = tracer;
    }

    /** Default context: slash DN format, fromNDS=true, empty GCVs, null Driver. */
    public static EngineContext create(String driverDN) {
        return create(driverDN, "slash", true, new GCDefinitions());
    }

    /** The engine's standard regex escape-char class (RuleStaticContext.regExEscChars). */
    private static final String REGEX_ESC_CHARS = "([\\\\\\$\\^\\.\\?\\*\\+\\[\\]\\(\\)\\|])";

    public static EngineContext create(String driverDN, String dnFormat, boolean fromNDS, GCDefinitions gcv) {
        CaptureEngineTrace tracer = new CaptureEngineTrace();
        char[] delims = XdsDN.getDelims(dnFormat).toCharArray();
        RuleStaticContext ctx = new RuleStaticContext(
            driverDN,
            delims,           // srcDelims
            delims,           // destDelims
            fromNDS,
            EngineContext.class.getClassLoader(),
            tracer,
            gcv,
            null,             // Driver -- null works for policies that don't use fanout/driver-filter
            new java.util.TreeMap<>(),   // driver variables (scope="driver")
            REGEX_ESC_CHARS);
        addAutoGcvs(gcv, driverDN);
        return new EngineContext(ctx, tracer);
    }

    /**
     * The engine auto-populates {@code dirxml.auto.driverdn} (and friends) at
     * runtime; exports don't carry them. Many real policies reference it (e.g. a
     * {@code token-query} with {@code arg-dn(token-global-variable("dirxml.auto.driverdn"))}),
     * so seed it from the driver DN when not already defined.
     */
    private static void addAutoGcvs(GCDefinitions gcv, String driverDN) {
        if (gcv.getValue("dirxml.auto.driverdn") != null) {
            return;
        }
        String esc = driverDN.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String doc = "<nds><configuration-values><definitions>"
            + "<definition name=\"dirxml.auto.driverdn\" display-name=\"Driver DN\" type=\"string\"><value>"
            + esc + "</value></definition>"
            + "</definitions></configuration-values></nds>";
        try {
            // construct(Node) looks for a <configuration-values> child of the node,
            // so pass the document element (whose child it is), not the Document.
            gcv.merge(GCDefinitions.construct((org.w3c.dom.Node) Xds.parse(doc).getDocumentElement()));
        } catch (Throwable t) {
            System.err.println("warning: could not set dirxml.auto.driverdn: " + t);
        }
    }

    public RuleStaticContext staticContext() {
        return staticCtx;
    }

    public CaptureEngineTrace tracer() {
        return tracer;
    }

    /** Set the trace verbosity (1=low … 5=everything). */
    public void setTraceLevel(int level) {
        tracer.setLevel(level);
    }

    /**
     * Build a per-operation dynamic context wired to the given query/command
     * seams. Both source and destination channels point at the same processors,
     * which is the common case for a single simulated application.
     */
    public RuleDynamicContext newDynamicContext(XdsQueryProcessor query, XdsCommandProcessor command) {
        return new RuleDynamicContext(query, query, command, command, null /* JCContext */);
    }

    /** Convenience: a dynamic context backed entirely by a {@link FakeDirectory}. */
    public RuleDynamicContext newDynamicContext(FakeDirectory dir) {
        return newDynamicContext(dir, dir);
    }

    /** Reset captured trace (call before each run). */
    public void resetTrace() {
        tracer.resetBuffer();
    }

    public String trace() {
        return tracer.dump();
    }

    /** An empty success response, useful as a default command/query reply. */
    public static Document successStatus() {
        return Xds.parse("<nds dtdversion=\"4.0\"><output><status level=\"success\"/></output></nds>");
    }
}
