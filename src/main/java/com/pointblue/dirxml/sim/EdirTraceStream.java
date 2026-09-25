package com.pointblue.dirxml.sim;

import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPException;
import com.novell.ldap.LDAPJSSESecureSocketFactory;
import com.novell.ldap.LDAPMessage;
import com.novell.ldap.events.LDAPEvent;
import com.novell.ldap.events.LDAPEventListener;
import com.novell.ldap.events.LDAPExceptionEvent;
import com.novell.ldap.events.edir.EdirEventConstant;
import com.novell.ldap.events.edir.EdirEventIntermediateResponse;
import com.novell.ldap.events.edir.EdirEventSource;
import com.novell.ldap.events.edir.EdirEventSpecifier;
import com.novell.ldap.events.edir.EventResponseData;
import com.novell.ldap.events.edir.eventdata.DebugEventData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The engine's DirXML trace, streamed over LDAP instead of read from a file over SSH.
 *
 * <p>eDirectory's event system exposes its DSTrace debug channels as LDAP events; the
 * {@code DirXML} channel ({@link EdirEventConstant#EVT_DB_DIRXML}, engine-level) and the
 * {@code DirXML Drivers} channel ({@link EdirEventConstant#EVT_DB_DIRXML_DRIVERS}, one line per
 * driver thread) carry exactly the lines a trace file holds, each prefixed with the driver's name
 * and the thread ({@code ST} subscriber, {@code PT} publisher) and a DSTrace colour code
 * ({@code %10C}). Registering for them needs the Monitor Entry right eDirectory grants an
 * administrator; nothing is written and no trace level is changed — what flows is what the
 * driver's own trace level produces, plus the engine's log events, which arrive at level 0 too.
 *
 * <p>Proved on a 4.10.2 engine (2026-09-25): 70 lines in 45 seconds from three running drivers,
 * including a driver's Java stack trace line by line.
 */
public final class EdirTraceStream implements AutoCloseable {

    /**
     * Connection settings. TLS verifies the server against the JDK's trust store unless
     * {@link #trustAllCerts} is set on purpose (a lab with a private CA, an SSH tunnel whose
     * certificate names another host): trusting every certificate is opt-in, never the default.
     */
    public static final class Config {
        public String host;
        public int port = 636;
        public boolean ssl = true;
        public String bindDn;
        public String password;
        /** Opt in to accepting any server certificate ({@link TrustAllSocketFactory}); off by default. */
        public boolean trustAllCerts = false;

        /** From an LDAP URL ({@code ldaps://host:636}, {@code ldap://host}); the port defaults per scheme. */
        public static Config fromUrl(String url, String bindDn, String password) {
            Config c = new Config();
            String u = url.trim();
            c.ssl = u.toLowerCase().startsWith("ldaps");
            String hostPort = u.replaceFirst("(?i)^ldaps?://", "").replaceAll("/.*$", "");
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0 && hostPort.indexOf(']') < colon) {
                c.host = hostPort.substring(0, colon);
                c.port = Integer.parseInt(hostPort.substring(colon + 1));
            } else {
                c.host = hostPort;
                c.port = c.ssl ? 636 : 389;
            }
            c.bindDn = bindDn;
            c.password = password;
            return c;
        }
    }

    /** One trace line as the engine emitted it, its DSTrace markup removed. */
    public static final class Line {
        /** {@link EdirEventConstant#EVT_DB_DIRXML} (engine) or {@link EdirEventConstant#EVT_DB_DIRXML_DRIVERS} (a driver thread). */
        public final int eventType;
        /** The event's millisecond stamp within its second (the engine's own clock). */
        public final int millis;
        /** The driver the line belongs to, null for an engine line or a continuation (a stack trace's next line). */
        public final String driver;
        /** {@code ST}, {@code PT}, or null. */
        public final String channel;
        /** The line without the driver prefix and colour codes. */
        public final String text;
        /** The wall-clock time the line arrived, epoch millis. */
        public final long receivedAt;

        Line(int eventType, int millis, String driver, String channel, String text, long receivedAt) {
            this.eventType = eventType;
            this.millis = millis;
            this.driver = driver;
            this.channel = channel;
            this.text = text;
            this.receivedAt = receivedAt;
        }

        /** {@code <driver> <channel>: <text>} as a trace file would show it. */
        public String format() {
            if (driver == null) {
                return text;
            }
            return driver + (channel == null ? " :" : " " + channel + ":") + " " + text;
        }
    }

    private static final Pattern COLOUR = Pattern.compile("^(?:%\\d+C)+");
    /** {@code <driver> ST: text}, {@code <driver> PT: text}, {@code <driver> : text}. */
    private static final Pattern PREFIX = Pattern.compile("^(.+?) (ST|PT)?:\\s?(.*)$", Pattern.DOTALL);

    /** The line the engine sent, prefix and colour markup parsed off; public for the tests and for readers of a saved stream. */
    public static Line parse(int eventType, int millis, String formatString, long receivedAt) {
        String s = formatString == null ? "" : formatString;
        s = COLOUR.matcher(s).replaceFirst("");
        s = s.replaceAll("%\\d+C", "");
        if (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.startsWith("\t") || s.startsWith(" ")) {
            return new Line(eventType, millis, null, null, s, receivedAt);   // a continuation (stack trace, XML)
        }
        Matcher m = PREFIX.matcher(s);
        if (m.matches() && eventType == EdirEventConstant.EVT_DB_DIRXML_DRIVERS) {
            String driver = m.group(1).trim();
            return new Line(eventType, millis, driver.isEmpty() || driver.equals("UNKNOWN") ? null : driver, m.group(2), m.group(3), receivedAt);
        }
        return new Line(eventType, millis, null, null, s, receivedAt);
    }

    private final Config config;
    private LDAPConnection conn;
    private EdirEventSource source;
    private LDAPEventListener listener;
    private final List<Throwable> errors = new ArrayList<>();

    public EdirTraceStream(Config config) {
        this.config = config;
    }

    /**
     * Connect, bind and register; every line goes to {@code sink} on the event thread until
     * {@link #close()}. {@code engineToo} adds the engine-level channel to the drivers' one.
     */
    public void start(boolean engineToo, Consumer<Line> sink) {
        try {
            conn = connect();
            List<EdirEventSpecifier> spec = new ArrayList<>();
            spec.add(new EdirEventSpecifier(EdirEventConstant.EVT_DB_DIRXML_DRIVERS, EdirEventConstant.EVT_STATUS_ALL));
            if (engineToo) {
                spec.add(new EdirEventSpecifier(EdirEventConstant.EVT_DB_DIRXML, EdirEventConstant.EVT_STATUS_ALL));
            }
            listener = new LDAPEventListener() {
                @Override
                public void ldapEventNotification(LDAPEvent e) {
                    long now = System.currentTimeMillis();
                    LDAPMessage m = e.getContainedEventInformation();
                    if (!(m instanceof EdirEventIntermediateResponse)) {
                        return;
                    }
                    EdirEventIntermediateResponse r = (EdirEventIntermediateResponse) m;
                    EventResponseData d = r.getResponsedata();
                    if (d instanceof DebugEventData) {
                        DebugEventData dd = (DebugEventData) d;
                        sink.accept(parse(r.getEventtype(), dd.getMilliSeconds(), dd.getFormatString(), now));
                    }
                }

                @Override
                public void ldapExceptionNotification(LDAPExceptionEvent e) {
                    synchronized (errors) {
                        errors.add(e.getLDAPException());
                    }
                }
            };
            source = new EdirEventSource();
            source.registerforEvent(spec.toArray(new EdirEventSpecifier[0]), conn, listener);
        } catch (LDAPException e) {
            close();
            throw new RuntimeException("event registration failed (" + config.host + ":" + config.port + "): " + e.getMessage()
                + (e.getResultCode() == LDAPException.INSUFFICIENT_ACCESS_RIGHTS ? " — the bind identity needs eDirectory's Monitor Entry right on the server" : ""), e);
        }
    }

    /** Errors the event source reported since {@link #start}; empty when the stream is healthy. */
    public List<Throwable> errors() {
        synchronized (errors) {
            return new ArrayList<>(errors);
        }
    }

    @Override
    public void close() {
        try {
            if (source != null && listener != null) {
                source.removeListener(listener);
            }
        } catch (LDAPException ignore) {
            // the connection is going away anyway
        }
        if (conn != null && conn.isConnected()) {
            try {
                conn.disconnect();
            } catch (LDAPException ignore) {
                // best effort
            }
        }
        source = null;
        listener = null;
        conn = null;
    }

    private LDAPConnection connect() {
        try {
            LDAPConnection c;
            if (config.ssl) {
                // the JDK's trust store by default; the trust-all factory only when asked for
                c = new LDAPConnection(config.trustAllCerts
                    ? new LDAPJSSESecureSocketFactory((javax.net.ssl.SSLSocketFactory) TrustAllSocketFactory.getDefault())
                    : new LDAPJSSESecureSocketFactory());
            } else {
                c = new LDAPConnection();
            }
            c.connect(config.host, config.port);
            c.bind(LDAPConnection.LDAP_V3, config.bindDn,
                config.password == null ? new byte[0] : config.password.getBytes(StandardCharsets.UTF_8));
            return c;
        } catch (Exception e) {
            throw new RuntimeException("LDAP connect/bind failed (" + config.host + ":" + config.port + "): " + e.getMessage(), e);
        }
    }

}
