package com.pointblue.dirxml.sim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.novell.ldap.events.edir.EdirEventConstant;
import org.junit.Test;

/** The DSTrace lines the event system delivers, parsed the way a trace file reader would see them. */
public class EdirTraceStreamTest {

    private static final int DRV = EdirEventConstant.EVT_DB_DIRXML_DRIVERS;

    @Test
    public void driverThreadLineLosesColourAndGainsDriverAndChannel() {
        EdirTraceStream.Line l = EdirTraceStream.parse(DRV, 581,
            "%10CRole and Resource driver ST: DirXML Log Event -------------------\n", 1L);
        assertEquals("Role and Resource driver", l.driver);
        assertEquals("ST", l.channel);
        assertEquals("DirXML Log Event -------------------", l.text);
        assertEquals("Role and Resource driver ST: DirXML Log Event -------------------", l.format());
        assertEquals(581, l.millis);
    }

    @Test
    public void driverLineWithoutAThreadAndTheUnknownDriver() {
        EdirTraceStream.Line l = EdirTraceStream.parse(DRV, 1, "%10CActive Directory Driver : Updating DirXML-DriverStorage attributes", 1L);
        assertEquals("Active Directory Driver", l.driver);
        assertNull(l.channel);
        assertEquals("Updating DirXML-DriverStorage attributes", l.text);
        EdirTraceStream.Line u = EdirTraceStream.parse(DRV, 1, "%10CUNKNOWN : nrf:identity: O=data\\OU=users\\CN=x", 1L);
        assertNull(u.driver);
        assertEquals("nrf:identity: O=data\\OU=users\\CN=x", u.text);
    }

    @Test
    public void continuationLinesKeepTheirIndentAndNoDriver() {
        EdirTraceStream.Line l = EdirTraceStream.parse(DRV, 76, "%12C\tat com.sssw.b2b.ee.httpclient.HTTPConnection.sendRequest(HTTPConnection.java:2981)", 1L);
        assertNull(l.driver);
        assertEquals("\tat com.sssw.b2b.ee.httpclient.HTTPConnection.sendRequest(HTTPConnection.java:2981)", l.text);
        assertEquals(l.text, l.format());
    }

    @Test
    public void engineLinesAreNotSplitOnADriverName() {
        EdirTraceStream.Line l = EdirTraceStream.parse(EdirEventConstant.EVT_DB_DIRXML, 3, "%10CDirXML: [12:00:01.000]: Loading driver set", 1L);
        assertNull(l.driver);
        assertEquals("DirXML: [12:00:01.000]: Loading driver set", l.text);
    }

    @Test
    public void configFromUrl() {
        EdirTraceStream.Config c = EdirTraceStream.Config.fromUrl("ldaps://idm.example.com:1636/", "cn=admin,o=system", "x");
        assertEquals("idm.example.com", c.host);
        assertEquals(1636, c.port);
        assertEquals(true, c.ssl);
        EdirTraceStream.Config p = EdirTraceStream.Config.fromUrl("ldap://10.0.0.5", "cn=a", null);
        assertEquals(389, p.port);
        assertEquals(false, p.ssl);
        assertEquals("trusting every certificate is opt-in", false, c.trustAllCerts);
        assertEquals(false, new EdirTraceStream.Config().trustAllCerts);
    }
}
