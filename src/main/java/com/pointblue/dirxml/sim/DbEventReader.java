package com.pointblue.dirxml.sim;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads captured events from a <b>DirXML Event Logger</b> PostgreSQL database — a
 * persistent, queryable history of real subscriber-channel events. The logger
 * stores each event's original XDS in {@code dxmlevent.xmlevent} alongside
 * queryable metadata ({@code srcdn}, {@code srcdriver}, {@code eventtype},
 * {@code classname}, {@code cachedtime}).
 *
 * <p>Each row is a <b>distinct transaction</b>; this returns them as separate
 * {@link Event}s (never coalesced), so a caller can list candidates and pick the
 * one(s) to run as input. Read-only ({@code SELECT}). Uses the open-source
 * PostgreSQL JDBC driver ({@code lib/postgresql.jar}); only the {@code dbevents}
 * feature needs it.
 */
public final class DbEventReader {

    public static final class Config {
        public String url;       // jdbc:postgresql://host:port/db
        public String user;
        public String password;
        public String table = "public.dxmlevent";
    }

    /** Filters on the event log; all optional (null/blank = no filter). */
    public static final class Query {
        public String srcDn;      // exact dxmlevent.srcdn
        public String srcDnLike;  // dxmlevent.srcdn LIKE … (e.g. %test2 — avoids escaping a full DN)
        public String driver;     // dxmlevent.srcdriver
        public String eventType;  // add | modify | delete | sync | rename | move
        public String className;  // dxmlevent.classname
        public String since;      // cachedtime >= (a Timestamp.valueOf-parseable string)
        public String until;      // cachedtime <=
        public String rawWhere;   // extra SQL predicate (power-user escape hatch)
        public int limit = 100;
        public boolean newestFirst = true;
    }

    /** One logged event — a complete, standalone XDS transaction plus its metadata. */
    public static final class Event {
        public final String eventId;
        public final String eventType;
        public final String className;
        public final String srcDn;
        public final String srcDriver;
        public final String cachedTime;
        public final String xds;     // the original <nds><input>…</input></nds> for this event
        Event(String eventId, String eventType, String className, String srcDn,
              String srcDriver, String cachedTime, String xds) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.className = className;
            this.srcDn = srcDn;
            this.srcDriver = srcDriver;
            this.cachedTime = cachedTime;
            this.xds = xds;
        }
    }

    private final Config config;

    public DbEventReader(Config config) {
        this.config = config;
    }

    /** Run the query; each matched row is returned as its own {@link Event} (no merging). */
    public List<Event> query(Query q) {
        List<Object> params = new ArrayList<>();
        String sql = buildSql(config.table, q, params);

        List<Event> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(config.url, config.user, config.password);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Event(rs.getString("eventid"), rs.getString("eventtype"),
                        rs.getString("classname"), rs.getString("srcdn"),
                        rs.getString("srcdriver"), String.valueOf(rs.getObject("cachedtime")),
                        rs.getString("xmlevent")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("event-log query failed (" + config.url + "): "
                + e.getMessage(), e);
        }
        return out;
    }

    /** Build the parameterized SELECT and fill {@code params}. Package-private for tests. */
    static String buildSql(String table, Query q, List<Object> params) {
        StringBuilder sql = new StringBuilder(
            "SELECT \"eventid\", \"eventtype\", \"classname\", \"srcdn\", \"srcdriver\", "
            + "\"cachedtime\", \"xmlevent\" FROM ")
            .append(table).append(" WHERE \"xmlevent\" IS NOT NULL");
        addEq(sql, params, "srcdn", q.srcDn);
        if (notBlank(q.srcDnLike)) {
            sql.append(" AND \"srcdn\" LIKE ?");
            params.add(q.srcDnLike.trim());
        }
        addEq(sql, params, "srcdriver", q.driver);
        addEq(sql, params, "eventtype", q.eventType);
        addEq(sql, params, "classname", q.className);
        if (notBlank(q.since)) {
            sql.append(" AND \"cachedtime\" >= ?");
            params.add(Timestamp.valueOf(normalizeTs(q.since)));
        }
        if (notBlank(q.until)) {
            sql.append(" AND \"cachedtime\" <= ?");
            params.add(Timestamp.valueOf(normalizeTs(q.until)));
        }
        if (notBlank(q.rawWhere)) {
            sql.append(" AND (").append(q.rawWhere).append(')');
        }
        sql.append(" ORDER BY \"cachedtime\" ").append(q.newestFirst ? "DESC" : "ASC")
           .append(" LIMIT ?");
        params.add(Math.max(1, q.limit));
        return sql.toString();
    }

    // ---- helpers --------------------------------------------------------

    private static void addEq(StringBuilder sql, List<Object> params, String col, String val) {
        if (notBlank(val)) {
            sql.append(" AND \"").append(col).append("\" = ?");
            params.add(val.trim());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Accept a bare date ({@code 2026-06-01}) by padding to a full timestamp. */
    private static String normalizeTs(String s) {
        String t = s.trim();
        return t.length() <= 10 ? t + " 00:00:00" : t;
    }
}
