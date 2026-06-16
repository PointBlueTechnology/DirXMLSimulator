package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.driver.TraceInterface;
import com.novell.nds.dirxml.driver.XmlDocument;

/**
 * A {@link TraceInterface} implementation that captures the engine's policy
 * trace into an in-memory buffer so the harness (and an agent driving it) can
 * read the full rule-by-rule execution log.
 *
 * The NetIQ {@code Trace} framework instantiates the registered impl class
 * reflectively via its no-arg constructor, so the captured text lives in a
 * static buffer. Register once per JVM with
 * {@code Trace.registerImpl(CaptureTrace.class, 100)}.
 */
public class CaptureTrace implements TraceInterface {

    private static final StringBuilder BUFFER = new StringBuilder();
    private static volatile int level = 5;
    private static volatile boolean echo = false;

    /** Clear the captured trace (call at the start of each run). */
    public static synchronized void reset() {
        BUFFER.setLength(0);
    }

    /** The trace captured since the last {@link #reset()}. */
    public static synchronized String dump() {
        return BUFFER.toString();
    }

    /** Max trace level reported to the engine (5 = everything). */
    public static void setLevel(int newLevel) {
        level = newLevel;
    }

    /** Also echo trace to stdout as it happens. */
    public static void setEcho(boolean on) {
        echo = on;
    }

    private static synchronized void append(String s) {
        BUFFER.append(s).append('\n');
        if (echo) {
            System.out.println(s);
        }
    }

    @Override
    public void trace(int traceLevel, String message) {
        append(message);
    }

    @Override
    public void trace(int color, int traceLevel, String message) {
        append(message);
    }

    @Override
    public void trace(int traceLevel, XmlDocument document) {
        append(document == null ? "<null document>" : document.getDocumentString());
    }

    @Override
    public int getLevel() {
        return level;
    }
}
