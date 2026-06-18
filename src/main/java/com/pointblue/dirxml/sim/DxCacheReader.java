package com.pointblue.dirxml.sim;

import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPException;
import com.novell.ldap.LDAPJSSESecureSocketFactory;
import com.novell.nds.dirxml.ldap.CloseChunkedResultRequest;
import com.novell.nds.dirxml.ldap.GetChunkedResultRequest;
import com.novell.nds.dirxml.ldap.GetChunkedResultResponse;
import com.novell.nds.dirxml.ldap.ViewCacheEntriesRequest;
import com.novell.nds.dirxml.ldap.ViewCacheEntriesResponse;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

/**
 * Reads a driver's <b>event cache</b> from a live IDM server via the same LDAP
 * extended operations the {@code dxcmd} (DxCommand) utility uses — giving a source
 * of real <em>subscriber-channel</em> input events (the transactions queued for a
 * stopped/idle driver), as an alternative to mining a trace.
 *
 * <p>Protocol (mirrors {@code DxCommand.readCache}/{@code getChunkedResult}):
 * {@code ViewCacheEntriesRequest} returns a chunked-result handle + size; the data
 * is pulled with {@code GetChunkedResultRequest} until drained and released with
 * {@code CloseChunkedResultRequest}. A {@code handle==0 || size==0} response means
 * the cache is empty.
 *
 * <p>Uses the Novell JLDAP SDK ({@code com.novell.ldap}, the {@code lib/ldap.jar}
 * dependency) because the request classes are {@code LDAPExtendedOperation}s.
 * Optional — only used by the DxCMD features.
 */
public final class DxCacheReader {

    /** Connection settings; password from the named-password channel. Trust-all by default. */
    public static final class Config {
        public String host;
        public int port = 636;
        public boolean ssl = true;
        public String bindDn;
        public String password;
        public boolean trustAllCerts = true;
    }

    private static final int MAX_CHUNK = 64512;   // dxcmd's chunk size

    private final Config config;

    public DxCacheReader(Config config) {
        this.config = config;
    }

    /**
     * Read up to {@code count} cache entries for a driver starting at
     * {@code startToken} (0 = from the beginning). Returns the entries as XDS text,
     * or an empty string if the cache is empty.
     */
    public Result readCache(String driverDN, int startToken, int count) {
        LDAPConnection conn = connect();
        try {
            // Register the typed extended responses so the SDK returns the subclasses.
            ViewCacheEntriesResponse.register();
            GetChunkedResultResponse.register();

            ViewCacheEntriesResponse resp = (ViewCacheEntriesResponse) conn.extendedOperation(
                new ViewCacheEntriesRequest(driverDN, 1, startToken, count, 0));
            int handle = resp.getDataHandle();
            int size = resp.getDataSize();
            int nextToken = resp.getPositionToken();
            if (handle == 0 || size == 0) {
                return new Result("", nextToken, true);   // empty cache (or no more entries)
            }
            byte[] data = getChunkedResult(conn, handle, size);
            return new Result(new String(data, StandardCharsets.UTF_8), nextToken, false);
        } catch (LDAPException e) {
            throw new RuntimeException("DxCMD cache read failed for '" + driverDN + "': "
                + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("DxCMD cache read failed: " + e, e);
        } finally {
            disconnect(conn);
        }
    }

    /** The cache contents plus the next position token (for paging) and an empty flag. */
    public static final class Result {
        public final String xds;
        public final int nextToken;
        public final boolean empty;
        Result(String xds, int nextToken, boolean empty) {
            this.xds = xds;
            this.nextToken = nextToken;
            this.empty = empty;
        }
    }

    // ---- protocol -------------------------------------------------------

    private byte[] getChunkedResult(LDAPConnection conn, int handle, int size) throws LDAPException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(size);
        int remaining = size;
        int chunkSize = Math.min(size, MAX_CHUNK);
        while (remaining > 0) {
            byte[] reply = ((GetChunkedResultResponse) conn.extendedOperation(
                new GetChunkedResultRequest(handle, chunkSize, 0))).getData();
            if (reply == null || reply.length == 0) {
                break;
            }
            baos.write(reply, 0, reply.length);
            remaining -= reply.length;
        }
        conn.extendedOperation(new CloseChunkedResultRequest(handle));
        return baos.toByteArray();
    }

    private LDAPConnection connect() {
        try {
            LDAPConnection conn;
            if (config.ssl) {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, config.trustAllCerts ? new TrustManager[]{TRUST_ALL} : null,
                    new java.security.SecureRandom());
                conn = new LDAPConnection(new LDAPJSSESecureSocketFactory(ctx.getSocketFactory()));
            } else {
                conn = new LDAPConnection();
            }
            conn.connect(config.host, config.port);
            conn.bind(LDAPConnection.LDAP_V3, config.bindDn,
                config.password == null ? new byte[0] : config.password.getBytes(StandardCharsets.UTF_8));
            return conn;
        } catch (Exception e) {
            throw new RuntimeException("DxCMD LDAP connect/bind failed ("
                + config.host + ":" + config.port + "): " + e.getMessage(), e);
        }
    }

    private static void disconnect(LDAPConnection conn) {
        if (conn != null && conn.isConnected()) {
            try {
                conn.disconnect();
            } catch (LDAPException ignore) {
                // best effort
            }
        }
    }

    /** Test-harness trust-all manager (no cert validation — internal test directories). */
    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
}
