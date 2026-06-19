package com.pointblue.dirxml.sim;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.nio.charset.StandardCharsets;

/**
 * Installs a JVM URL handler for the engine's {@code vnd.nds.stream:} scheme so a
 * {@code Map} token's table lookup — which the engine performs by opening a
 * {@code vnd.nds.stream:} URL — is served from the local {@link MappingTableStore}
 * instead of requiring a live eDirectory connection.
 *
 * <p>Offline the engine does not initialize its NDS stack, so the scheme is
 * unclaimed and we register the single per-JVM {@link URLStreamHandlerFactory}.
 * Installation is idempotent and best-effort: if a factory is already set we leave
 * it (a live engine that registered its own handler is itself the table source).
 */
final class NdsStreamProtocol {

    static final String SCHEME = "vnd.nds.stream";

    private NdsStreamProtocol() {
    }

    private static volatile boolean installed;

    /** Register the handler once. Safe to call repeatedly. */
    static synchronized void ensureInstalled() {
        if (installed) {
            return;
        }
        installed = true;   // set first so a failure doesn't retry every call
        try {
            URL.setURLStreamHandlerFactory(new Factory());
        } catch (Error alreadySet) {
            // A factory is already installed (e.g. a live engine). Our table store
            // simply won't be consulted; that's an acceptable no-op offline.
        }
    }

    private static final class Factory implements URLStreamHandlerFactory {
        @Override
        public URLStreamHandler createURLStreamHandler(String protocol) {
            return SCHEME.equals(protocol) ? new Handler() : null;
        }
    }

    private static final class Handler extends URLStreamHandler {
        @Override
        protected URLConnection openConnection(URL u) {
            return new Connection(u);
        }
    }

    private static final class Connection extends URLConnection {
        Connection(URL url) {
            super(url);
        }

        @Override
        public void connect() {
            // nothing to connect — data is served from the in-process store
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (System.getProperty("sim.mapdebug") != null) {
                System.err.println("[nds-stream] requested URL: " + url);
            }
            String xml = MappingTableStore.resolve(url.toString());
            if (xml == null) {
                // Let the engine apply the Map token's not-found/default behavior,
                // exactly as a missing table would in production.
                throw new FileNotFoundException("no local mapping table for " + url);
            }
            return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        }
    }
}
