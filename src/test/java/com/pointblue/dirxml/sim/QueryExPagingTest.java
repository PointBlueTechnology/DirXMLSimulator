package com.pointblue.dirxml.sim;

import org.junit.Before;
import org.junit.Test;

import org.w3c.dom.Document;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** The fake directory pages query-ex results via max-result-count + query-token. */
public class QueryExPagingTest {

    private FakeDirectory dir;

    @Before
    public void seed() {
        StringBuilder sb = new StringBuilder("<nds dtdversion='4.0'><input>");
        for (int i = 1; i <= 5; i++) {
            sb.append("<instance class-name='User' src-dn='\\o\\u").append(i).append("'>")
              .append("<association>a").append(i).append("</association>")
              .append("<attr attr-name='Surname'><value>S").append(i).append("</value></attr>")
              .append("</instance>");
        }
        sb.append("</input></nds>");
        dir = new FakeDirectory().loadState(Xds.parse(sb.toString()));
        assertEquals(5, dir.size());
    }

    private static int countInstances(Document d) {
        Matcher m = Pattern.compile("<instance\\b").matcher(Xds.serialize(d));
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String token(Document d) {
        Matcher m = Pattern.compile("<query-token>([^<]+)</query-token>").matcher(Xds.serialize(d));
        return m.find() ? m.group(1) : null;
    }

    @Test
    public void pagesThroughResultsWithTokens() {
        // Page 1: 2 of 5, expect a token.
        Document p1 = dir.query(Xds.parse(
            "<nds dtdversion='4.0'><input><query-ex class-name='User' max-result-count='2' scope='subtree'>" +
            "<read-attr attr-name='Surname'/></query-ex></input></nds>"));
        assertEquals(2, countInstances(p1));
        String t1 = token(p1);
        assertNotNull("more remain -> token", t1);

        // Page 2: next 2, using the token.
        Document p2 = dir.query(Xds.parse(
            "<nds dtdversion='4.0'><input><query-ex class-name='User' max-result-count='2'>" +
            "<query-token>" + t1 + "</query-token></query-ex></input></nds>"));
        assertEquals(2, countInstances(p2));
        String t2 = token(p2);
        assertNotNull(t2);

        // Page 3: last 1, no further token.
        Document p3 = dir.query(Xds.parse(
            "<nds dtdversion='4.0'><input><query-ex class-name='User' max-result-count='2'>" +
            "<query-token>" + t2 + "</query-token></query-ex></input></nds>"));
        assertEquals(1, countInstances(p3));
        assertNull("no more results -> no token", token(p3));
    }

    @Test
    public void plainQueryReturnsAll() {
        Document r = dir.query(Xds.parse(
            "<nds dtdversion='4.0'><input><query class-name='User' scope='subtree'/></input></nds>"));
        assertEquals(5, countInstances(r));
        assertNull(token(r));
    }
}
