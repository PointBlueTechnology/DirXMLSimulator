package com.pointblue.dirxml.sim;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** The XPath-based assertion DSL: parse + evaluate against an XDS string. */
public class AssertionsTest {

    private static final String OUT =
        "<nds><input>"
        + "<modify class-name='User' dest-dn='\\X\\users\\jdoe'>"
        + "  <modify-attr attr-name='Email'><add-value><value>j@x.com</value></add-value></modify-attr>"
        + "  <add-attr attr-name='Surname'><value>Smith</value></add-attr>"
        + "</modify>"
        + "</input></nds>";

    private static final String VETOED = "<nds><input></input></nds>";

    private static List<Assertions.Check> run(String dsl, String xml) {
        return Assertions.evaluate(Assertions.parse(dsl), xml);
    }

    @Test
    public void parseSkipsCommentsAndBlankLinesAndSplitsArrow() {
        List<Assertions.Assertion> a = Assertions.parse(
            "# a comment\n\nequals //value => Smith\nexists //modify-attr\n");
        assertEquals(2, a.size());
        assertEquals("equals", a.get(0).verb());
        assertEquals("//value", a.get(0).xpath());
        assertEquals("Smith", a.get(0).arg());
        assertNull(a.get(1).arg());
    }

    @Test
    public void existsAndAbsent() {
        assertTrue(Assertions.allPass(run("exists //modify-attr[@attr-name='Email']", OUT)));
        assertTrue(Assertions.allPass(run("absent //remove-value", OUT)));
        assertFalse(Assertions.allPass(run("absent //modify-attr[@attr-name='Email']", OUT)));
    }

    @Test
    public void equalsAndMatches() {
        assertTrue(Assertions.allPass(run("equals //add-attr[@attr-name='Surname']/value => Smith", OUT)));
        assertFalse(Assertions.allPass(run("equals //add-attr[@attr-name='Surname']/value => Jones", OUT)));
        assertTrue(Assertions.allPass(run("matches //add-attr[@attr-name='Surname']/value => S\\w+", OUT)));
    }

    @Test
    public void countAndVetoSugar() {
        assertTrue(Assertions.allPass(run("count //modify-attr => 1", OUT)));
        assertTrue(Assertions.allPass(run("not-vetoed", OUT)));
        assertFalse(Assertions.allPass(run("vetoed", OUT)));
        assertTrue(Assertions.allPass(run("vetoed", VETOED)));
        assertFalse(Assertions.allPass(run("not-vetoed", VETOED)));
    }

    @Test
    public void failureCarriesAReadableDetail() {
        Assertions.Check c = run("equals //add-attr[@attr-name='Surname']/value => Jones", OUT).get(0);
        assertFalse(c.pass());
        assertTrue(c.detail().contains("Jones"));
        assertTrue(c.detail().contains("Smith"));
    }

    @Test
    public void unknownVerbAndBadXpathFailGracefully() {
        assertFalse(run("frobnicate //x", OUT).get(0).pass());
        assertFalse(run("exists //[bad(", OUT).get(0).pass());
    }
}
