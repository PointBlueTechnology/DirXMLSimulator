package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Event-log query building (filters → parameterized SQL). No DB needed. */
public class DbEventReaderTest {

    private static String sql(DbEventReader.Query q, List<Object> params) {
        return DbEventReader.buildSql("public.dxmlevent", q, params);
    }

    @Test
    public void noFiltersSelectsAllOrderedNewestFirstWithLimit() {
        DbEventReader.Query q = new DbEventReader.Query();   // defaults: limit 100, newestFirst
        List<Object> p = new ArrayList<>();
        String s = sql(q, p);
        assertTrue(s.contains("FROM public.dxmlevent WHERE \"xmlevent\" IS NOT NULL"));
        assertTrue(s.contains("ORDER BY \"cachedtime\" DESC"));
        assertTrue(s.trim().endsWith("LIMIT ?"));
        assertEquals(1, p.size());          // just the limit
        assertEquals(100, p.get(0));
    }

    @Test
    public void equalityFiltersBecomeBoundParams() {
        DbEventReader.Query q = new DbEventReader.Query();
        q.srcDn = "\\T\\data\\jdoe";
        q.driver = "cn=CyberArk,cn=ds,o=system";
        q.eventType = "modify";
        q.className = "User";
        q.limit = 5;
        List<Object> p = new ArrayList<>();
        String s = sql(q, p);
        assertTrue(s.contains("\"srcdn\" = ?"));
        assertTrue(s.contains("\"srcdriver\" = ?"));
        assertTrue(s.contains("\"eventtype\" = ?"));
        assertTrue(s.contains("\"classname\" = ?"));
        // params in order: srcdn, srcdriver, eventtype, classname, limit
        assertEquals(List.of("\\T\\data\\jdoe", "cn=CyberArk,cn=ds,o=system", "modify", "User", 5), p);
    }

    @Test
    public void dnLikeUsesLikeNotEquals() {
        DbEventReader.Query q = new DbEventReader.Query();
        q.srcDnLike = "%test2";
        List<Object> p = new ArrayList<>();
        String s = sql(q, p);
        assertTrue(s.contains("\"srcdn\" LIKE ?"));
        assertFalse(s.contains("\"srcdn\" = ?"));
        assertEquals("%test2", p.get(0));
    }

    @Test
    public void timeRangeBindsTimestamps() {
        DbEventReader.Query q = new DbEventReader.Query();
        q.since = "2026-06-01";        // bare date padded to midnight
        q.until = "2026-06-30 23:59:59";
        List<Object> p = new ArrayList<>();
        String s = sql(q, p);
        assertTrue(s.contains("\"cachedtime\" >= ?"));
        assertTrue(s.contains("\"cachedtime\" <= ?"));
        assertEquals(Timestamp.valueOf("2026-06-01 00:00:00"), p.get(0));
        assertEquals(Timestamp.valueOf("2026-06-30 23:59:59"), p.get(1));
    }

    @Test
    public void chronologicalOrderWhenNotNewestFirst() {
        DbEventReader.Query q = new DbEventReader.Query();
        q.newestFirst = false;
        assertTrue(sql(q, new ArrayList<>()).contains("ORDER BY \"cachedtime\" ASC"));
    }

    @Test
    public void rawWhereIsAppendedAsAPredicate() {
        DbEventReader.Query q = new DbEventReader.Query();
        q.rawWhere = "\"eventjson\" -> 'attributes' ? 'manager'";
        assertTrue(sql(q, new ArrayList<>()).contains(
            "AND (\"eventjson\" -> 'attributes' ? 'manager')"));
    }

    @Test
    public void limitIsAlwaysAtLeastOne() {
        DbEventReader.Query q = new DbEventReader.Query();
        q.limit = 0;
        List<Object> p = new ArrayList<>();
        sql(q, p);
        assertEquals(1, p.get(p.size() - 1));
    }
}
