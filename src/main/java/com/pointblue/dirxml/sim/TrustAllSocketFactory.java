package com.pointblue.dirxml.sim;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;

/**
 * An {@link SSLSocketFactory} that trusts all certificates, for {@code ldaps://}
 * connections to an internal eDirectory with a self-signed or private-CA cert in
 * a test environment. JNDI loads it by class name via the
 * {@code java.naming.ldap.factory.socket} property (it requires a static
 * {@code getDefault()}).
 *
 * <p>Test-harness use only — it disables certificate validation, so it must never
 * be used for anything but pointing the simulator at a disposable test directory.
 */
public final class TrustAllSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public TrustAllSocketFactory() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ TRUST_ALL }, new java.security.SecureRandom());
            this.delegate = ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("could not init trust-all SSL context", e);
        }
    }

    /** JNDI entry point. */
    public static SocketFactory getDefault() {
        return new TrustAllSocketFactory();
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(s, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return delegate.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return delegate.createSocket(address, port, localAddress, localPort);
    }
}
