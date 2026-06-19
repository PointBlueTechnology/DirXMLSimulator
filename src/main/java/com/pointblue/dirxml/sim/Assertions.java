package com.pointblue.dirxml.sim;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A lightweight, XPath-based assertion language for a case's final output — a
 * robust alternative to a full-document golden when you want to pin one specific
 * behavior ("it adds Email = X", "it did NOT modify Surname", "it vetoed") without
 * being brittle to incidental changes elsewhere in the document.
 *
 * <p>A case carries assertions in an {@code expected.assertions} file, one per
 * line ({@code #} comments and blank lines ignored). Each line is
 * {@code <verb> <xpath> [=> <arg>]}; the {@code =>} separates the XPath from an
 * expected value so both may contain spaces:
 *
 * <pre>
 *   exists      //add-attr[@attr-name='Email']
 *   absent      //modify-attr[@attr-name='Surname']
 *   equals      //add-attr[@attr-name='Surname']/value =&gt; Smith
 *   count       //modify =&gt; 1
 *   matches     //add-attr[@attr-name='dob']/value =&gt; \d{8}
 *   vetoed                                   # no operation survived the chain
 *   not-vetoed                               # at least one operation present
 * </pre>
 *
 * <p>{@link #parse} and {@link #evaluate} are pure and unit-test offline.
 */
public final class Assertions {

    private Assertions() {
    }

    /** Operation elements (namespace-agnostic) whose presence means "not vetoed". */
    private static final String OP_XPATH =
        "//*[local-name()='add' or local-name()='modify' or local-name()='delete'"
        + " or local-name()='rename' or local-name()='move']";

    public record Assertion(String verb, String xpath, String arg, String raw) {
    }

    public record Check(Assertion assertion, boolean pass, String detail) {
    }

    /** Parse an {@code expected.assertions} file body into assertions. */
    public static List<Assertion> parse(String text) {
        List<Assertion> out = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int sp = firstWhitespace(t);
            String verb = sp < 0 ? t : t.substring(0, sp);
            String rest = sp < 0 ? "" : t.substring(sp).strip();
            String xpath = rest;
            String arg = null;
            int arrow = rest.indexOf("=>");
            if (arrow >= 0) {
                xpath = rest.substring(0, arrow).strip();
                arg = rest.substring(arrow + 2).strip();
            }
            out.add(new Assertion(verb, xpath.isBlank() ? null : xpath, arg, t));
        }
        return out;
    }

    /** Evaluate assertions against an XDS document (the case's final output). */
    public static List<Check> evaluate(List<Assertion> assertions, String xml) {
        List<Check> checks = new ArrayList<>();
        Document doc;
        try {
            doc = parseDom(xml);
        } catch (Exception e) {
            for (Assertion a : assertions) {
                checks.add(new Check(a, false, "could not parse output: " + e.getMessage()));
            }
            return checks;
        }
        XPath xp = XPathFactory.newInstance().newXPath();
        for (Assertion a : assertions) {
            checks.add(evaluateOne(a, doc, xp));
        }
        return checks;
    }

    /** True iff every check passed (or the list is empty). */
    public static boolean allPass(List<Check> checks) {
        return checks.stream().allMatch(Check::pass);
    }

    private static Check evaluateOne(Assertion a, Document doc, XPath xp) {
        try {
            switch (a.verb()) {
                case "vetoed": {
                    int n = count(xp, OP_XPATH, doc);
                    return new Check(a, n == 0, n == 0 ? "" : n + " operation(s) survived; not vetoed");
                }
                case "not-vetoed": {
                    int n = count(xp, OP_XPATH, doc);
                    return new Check(a, n > 0, n > 0 ? "" : "no operation present; was vetoed");
                }
                case "exists": {
                    int n = count(xp, requireXpath(a), doc);
                    return new Check(a, n > 0, n > 0 ? "" : "no node matched " + a.xpath());
                }
                case "absent": {
                    int n = count(xp, requireXpath(a), doc);
                    return new Check(a, n == 0, n == 0 ? "" : n + " node(s) matched " + a.xpath());
                }
                case "count": {
                    int want = Integer.parseInt(requireArg(a));
                    int n = count(xp, requireXpath(a), doc);
                    return new Check(a, n == want, n == want ? "" : "expected " + want + ", found " + n);
                }
                case "equals": {
                    String want = requireArg(a);
                    String got = string(xp, requireXpath(a), doc);
                    boolean ok = want.equals(got);
                    return new Check(a, ok, ok ? "" : "expected '" + want + "', got '" + got + "'");
                }
                case "matches": {
                    String re = requireArg(a);
                    String got = string(xp, requireXpath(a), doc);
                    boolean ok = got != null && got.matches(re);
                    return new Check(a, ok, ok ? "" : "'" + got + "' does not match /" + re + "/");
                }
                default:
                    return new Check(a, false, "unknown verb '" + a.verb() + "'");
            }
        } catch (Exception e) {
            return new Check(a, false, "error: " + e.getMessage());
        }
    }

    private static int count(XPath xp, String expr, Document doc) throws Exception {
        NodeList nl = (NodeList) xp.evaluate(expr, doc, XPathConstants.NODESET);
        return nl.getLength();
    }

    /** String value of the first matching node, or null if none. */
    private static String string(XPath xp, String expr, Document doc) throws Exception {
        NodeList nl = (NodeList) xp.evaluate(expr, doc, XPathConstants.NODESET);
        if (nl.getLength() == 0) {
            return null;
        }
        Node n = nl.item(0);
        return n.getTextContent() == null ? "" : n.getTextContent().strip();
    }

    private static String requireXpath(Assertion a) {
        if (a.xpath() == null) {
            throw new IllegalArgumentException(a.verb() + " needs an XPath");
        }
        return a.xpath();
    }

    private static String requireArg(Assertion a) {
        if (a.arg() == null) {
            throw new IllegalArgumentException(a.verb() + " needs '=> <value>'");
        }
        return a.arg();
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static Document parseDom(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
