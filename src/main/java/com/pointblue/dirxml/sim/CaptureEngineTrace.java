package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.driver.XmlDocument;
import com.novell.nds.dirxml.engine.EngineTrace;

import org.w3c.dom.Document;

/**
 * An {@link EngineTrace} that captures the engine's policy trace into an
 * in-memory buffer instead of routing it to DSTrace / a trace file.
 *
 * <p>The stock {@code EngineTrace} file path calls a native {@code DxPermSetter}
 * that throws off-platform (e.g. macOS) and silently drops the trace. Rather
 * than fight that plumbing, we subclass and override the three protected
 * {@code traceAlways(...)} funnels that every public {@code trace(...)} overload
 * ultimately routes through. Level gating, suppression, and indent tracking in
 * the superclass are preserved; only the final sink changes.
 *
 * <p>This is the agent-readable, rule-by-rule execution log.
 */
public class CaptureEngineTrace extends EngineTrace {

    private final StringBuilder buf = new StringBuilder();

    public CaptureEngineTrace() {
        super(1L, "harness");
        setLevel(5); // capture everything; callers can lower it
    }

    /** Final text sink: all message and msgID/MessageSource traces funnel here. */
    @Override
    protected void traceAlways(String message) {
        int indent = getIndent();
        for (int i = 0; i < indent; i++) {
            buf.append("  ");
        }
        buf.append(message).append('\n');
    }

    /** Document traces (e.g. the result doc after each rule). */
    @Override
    protected void traceAlways(Document document) {
        if (document == null) {
            traceAlways("<null document>");
        } else {
            traceAlways(new XmlDocument(document).getDocumentString());
        }
    }

    /** The trace captured so far. */
    public String dump() {
        return buf.toString();
    }

    /** Clear the buffer (call at the start of each run). */
    public void resetBuffer() {
        buf.setLength(0);
    }
}
