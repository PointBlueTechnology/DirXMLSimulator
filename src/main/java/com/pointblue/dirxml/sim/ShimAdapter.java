package com.pointblue.dirxml.sim;

import com.novell.nds.dirxml.driver.DriverShim;
import com.novell.nds.dirxml.driver.SubscriptionShim;
import com.novell.nds.dirxml.driver.XmlDocument;
import com.novell.nds.dirxml.driver.XmlQueryProcessor;
import com.novell.nds.dirxml.engine.XdsCommandProcessor;
import com.novell.nds.dirxml.engine.XdsQueryProcessor;

import org.w3c.dom.Document;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Drives a real {@link DriverShim} as the command sink for a channel: after the
 * policy chain produces its final (post–Output-Transform) command, that document
 * is handed to the shim's {@link SubscriptionShim#execute}, exactly as the engine
 * would — validating that the connector can consume what the policies built.
 *
 * <p>Implements the engine {@link XdsCommandProcessor} seam, so it is a drop-in
 * command sink in place of {@link FakeDirectory}. The shim's back-channel
 * {@link XmlQueryProcessor} (the queries it makes to the Identity Vault during
 * command processing) is bridged to an engine-side {@link XdsQueryProcessor} —
 * either {@link FakeDirectory} (author state) or {@link LdapQueryProcessor} (live
 * eDir). Both back-channels are optional; with neither, the shim gets an
 * empty-success responder.
 *
 * <p>Optional collaborator — only created when a case configures {@code shim=}.
 * If the shim class or jar cannot be loaded/initialized the factory throws, and
 * the case fails with a clear diagnostic (never a silent fallback). Subscriber
 * direction only; see {@code docs/shim-testing-design.md}.
 */
public final class ShimAdapter implements XdsCommandProcessor {

    private final DriverShim driver;
    private final SubscriptionShim subscriber;
    private final XmlQueryProcessor backChannel;

    private ShimAdapter(DriverShim driver, SubscriptionShim subscriber, XmlQueryProcessor backChannel) {
        this.driver = driver;
        this.subscriber = subscriber;
        this.backChannel = backChannel;
    }

    /**
     * Load {@code shimClass}, run the {@code init} lifecycle with {@code initDoc},
     * and return an adapter ready to take commands.
     *
     * @param backChannelSource answers the shim's IVault queries; may be null
     *                          (the shim then gets empty-success replies)
     * @param loader            classloader to load the shim from; null ⇒ this class's loader
     */
    public static ShimAdapter create(String shimClass, ClassLoader loader, Document initDoc,
                                     XdsQueryProcessor backChannelSource) {
        if (shimClass == null || shimClass.isEmpty()) {
            throw new IllegalArgumentException("no shim class configured (shimClass=)");
        }
        ClassLoader cl = loader != null ? loader : ShimAdapter.class.getClassLoader();
        DriverShim driver;
        try {
            Class<?> c = Class.forName(shimClass, true, cl);
            driver = (DriverShim) c.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            throw new RuntimeException("could not load driver shim '" + shimClass
                + "' (check shimJar= and that its dependencies are on the classpath): " + t, t);
        }

        XmlDocument init = new XmlDocument(initDoc);
        try {
            driver.init(init);
            SubscriptionShim sub = driver.getSubscriptionShim();
            if (sub == null) {
                throw new IllegalStateException("shim returned no SubscriptionShim");
            }
            sub.init(init);
            return new ShimAdapter(driver, sub, bridge(backChannelSource));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("driver shim '" + shimClass + "' failed to initialize: " + t, t);
        }
    }

    /** Bridge an engine-side query processor to the driver-side seam the shim calls. */
    private static XmlQueryProcessor bridge(XdsQueryProcessor source) {
        if (source == null) {
            return q -> new XmlDocument(
                Xds.parse("<nds dtdversion=\"4.0\"><output><status level=\"success\"/></output></nds>"));
        }
        return q -> new XmlDocument(source.query(q.getDocument()));
    }

    /** Send the channel's final command to the shim; returns the shim's real response XDS. */
    @Override
    public Document execute(Document commandDoc) {
        XmlDocument result = subscriber.execute(new XmlDocument(commandDoc), backChannel);
        return result == null ? null : result.getDocument();
    }

    /** The shim's own schema ({@code DriverShim.getSchema}), or null on failure. */
    public Document schema() {
        try {
            XmlDocument s = driver.getSchema(new XmlDocument(
                Xds.parse("<nds dtdversion=\"4.0\"><input><schema-def/></input></nds>")));
            return s == null ? null : s.getDocument();
        } catch (Throwable t) {
            return null;
        }
    }

    public void shutdown() {
        try {
            driver.shutdown(new XmlDocument(Xds.parse("<nds dtdversion=\"4.0\"><input/></nds>")));
        } catch (Throwable ignore) {
            // best effort
        }
    }

    /**
     * Build a classloader that can see the shim jar(s), parented to this class's
     * loader so the engine/support jars remain visible. Returns the parent loader
     * unchanged when no extra jars are given.
     */
    public static ClassLoader classLoaderFor(List<Path> shimJars) {
        ClassLoader parent = ShimAdapter.class.getClassLoader();
        if (shimJars == null || shimJars.isEmpty()) {
            return parent;
        }
        URL[] urls = shimJars.stream().map(ShimAdapter::toUrl).toArray(URL[]::new);
        return new URLClassLoader(urls, parent);
    }

    private static URL toUrl(Path p) {
        try {
            return p.toUri().toURL();
        } catch (Exception e) {
            throw new RuntimeException("bad shim jar path: " + p, e);
        }
    }
}
