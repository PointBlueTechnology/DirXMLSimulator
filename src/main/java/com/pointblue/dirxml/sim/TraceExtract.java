package com.pointblue.dirxml.sim;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mines a production DirXML / DSTrace log for the XDS documents that flow through
 * a driver, so an agent can bootstrap a realistic test case from real traffic.
 *
 * <p>DSTrace lines look like {@code [03/13/23 02:48:59.630]:UKG PT:<message>}
 * (PT = Publisher thread, ST = Subscriber thread). Documents are logged as a
 * message line followed by a raw multi-line {@code <nds>…</nds>} body. Each
 * extracted document is labeled by the nearest preceding non-empty message:
 *
 * <ul>
 *   <li><b>Receiving DOM document from application</b> / from eDirectory — the
 *       input <b>event</b> entering a channel → seeds {@code input.xds};</li>
 *   <li><b>Query from policy</b> — a lookup the policy issued;</li>
 *   <li><b>Query from policy result</b> — the directory's answer (real
 *       instances) → seeds {@code directory.xds};</li>
 *   <li><b>Policy returned</b> — the document after a policy stage.</li>
 * </ul>
 */
public final class TraceExtract {

    // [MM/dd/yy HH:mm:ss.SSS]:<thread>:<message>  — the thread (e.g. "CC PS02 ST",
    // "UKG PT", "DirXML") is colon-free; its last token is the channel. The
    // message may itself contain colons (and an inline <nds> document).
    private static final Pattern HEADER = Pattern.compile(
        "^\\[\\d{2}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\]:([^:]*):(.*)$");

    /** Cap on per-document sample files written (full counts are still reported). */
    private static final int MAX_SAMPLES = 300;

    private static String channelOf(String thread) {
        String[] toks = thread.trim().split("\\s+");
        if (toks.length > 0) {
            String last = toks[toks.length - 1];
            if (last.equals("PT") || last.equals("ST") || last.equals("DOMT")) {
                return last;
            }
        }
        return "?";
    }

    /** One document found in the trace. */
    public static final class Doc {
        public final String channel;   // PT | ST | DOMT | ?
        public final String label;     // nearest preceding message
        public final String xds;       // the <nds>…</nds> text
        Doc(String channel, String label, String xds) {
            this.channel = channel;
            this.label = label;
            this.xds = xds;
        }
        public String kind() {
            String l = label.toLowerCase();
            if (l.contains("query from policy result") || l.contains("query result")) return "query-result";
            if (l.contains("query from policy") || l.startsWith("query")) return "query";
            if (l.contains("policy returned")) return "policy-returned";
            // Document leaving the channel toward the application / Identity Vault.
            if (l.contains("submitting document") || l.contains("subscriber shim")
                || l.contains("publisher shim") || l.contains("input doc")
                || l.contains("sending") || l.contains("pumping") || l.contains("direct command")) return "command";
            // Shim's reply to a command.
            if (l.contains("returned")) return "response";
            // Document entering the channel.
            if (l.contains("receiving") && (l.contains("application") || l.contains("edirectory"))) return "event";
            if (l.contains("processing events") || l.contains("from application")
                || l.contains("from edirectory")) return "event";
            return "other";
        }

        /** True if this is an actual operation event (not a query/status). */
        public boolean isOperationEvent() {
            return xds.contains("<input")
                && (xds.contains("<add ") || xds.contains("<modify ") || xds.contains("<modify>")
                    || xds.contains("<delete ") || xds.contains("<rename ") || xds.contains("<move "))
                && !xds.contains("<query");
        }
    }

    public static List<Doc> parse(List<String> lines) {
        List<Doc> docs = new ArrayList<>();
        String lastMessage = "";
        String lastChannel = "?";
        int i = 0;
        while (i < lines.size()) {
            Matcher m = HEADER.matcher(lines.get(i));
            if (!m.matches()) {
                i++;
                continue;
            }
            String channel = channelOf(m.group(1));
            String message = m.group(2);

            // Collect the body up to the next header line.
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            while (j < lines.size() && !HEADER.matcher(lines.get(j)).matches()) {
                body.append(lines.get(j)).append('\n');
                j++;
            }

            // A document may sit inline on the message line, or in the body.
            String combined = message + "\n" + body;
            int start = combined.indexOf("<nds");
            int end = combined.lastIndexOf("</nds>");
            if (start >= 0 && end > start) {
                String xds = combined.substring(start, end + "</nds>".length());
                // label = message text preceding the doc, else the last non-empty message.
                String pre = start <= message.length()
                    ? message.substring(0, Math.min(start, message.length())).trim()
                    : message.trim();
                String label = !pre.isEmpty() ? pre : lastMessage;
                String ch = !"?".equals(channel) ? channel : lastChannel;
                docs.add(new Doc(ch, label, xds));
            }

            // Update "last message" from the non-document part of this line.
            String msgClean = message.contains("<nds")
                ? message.substring(0, message.indexOf("<nds")).trim()
                : message.trim();
            if (!msgClean.isEmpty()) {
                lastMessage = msgClean;
                lastChannel = channel;
            }
            i = j;
        }
        return docs;
    }

    /** Extract a trace into a case-bootstrap directory; returns a summary. */
    public static String extractToDir(Path traceFile, Path outDir) throws Exception {
        List<String> lines = Files.readAllLines(traceFile);
        List<Doc> docs = parse(lines);
        Files.createDirectories(outDir);
        Path samples = outDir.resolve("trace-samples");
        Files.createDirectories(samples);

        Map<String, Integer> counts = new LinkedHashMap<>();
        Doc inputEvent = null;
        List<Doc> results = new ArrayList<>();
        int eventKinds = 0;
        int n = 0;
        for (Doc d : docs) {
            counts.merge(d.kind(), 1, Integer::sum);
            if (d.kind().equals("event")) {
                eventKinds++;
            }
            // Cap sample files so a multi-transaction firehose doesn't write 10k+ files.
            if (n < MAX_SAMPLES) {
                String fname = String.format("%04d-%s-%s.xds", n + 1, d.channel, d.kind());
                Files.write(samples.resolve(fname), pretty(d.xds).getBytes("UTF-8"));
            }
            n++;
            // The channel input is the first real operation event in trace order.
            if (inputEvent == null && d.isOperationEvent()) {
                inputEvent = d;
            }
            if (d.kind().equals("query-result")) results.add(d);
        }

        StringBuilder sum = new StringBuilder();
        sum.append("parsed ").append(docs.size()).append(" XDS documents from ")
           .append(traceFile.getFileName()).append('\n');
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            sum.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }

        // input.xds <- first real operation event
        if (inputEvent != null) {
            Doc ev = inputEvent;
            Files.write(outDir.resolve("input.xds"), pretty(ev.xds).getBytes("UTF-8"));
            boolean publisher = "PT".equals(ev.channel);
            sum.append("wrote input.xds  (channel=").append(channelName(ev.channel)).append(")\n");
            // case.properties stub (agent fills in export=)
            Path props = outDir.resolve("case.properties");
            if (!Files.exists(props)) {
                String stub = "# Add your driver export to derive the real chain:\n"
                    + "# export=../../YourDriver.xml\n"
                    + "channel=" + (publisher ? "publisher" : "subscriber") + "\n"
                    + "fromNDS=" + (publisher ? "false" : "true") + "\n"
                    + "traceLevel=5\n";
                Files.write(props, stub.getBytes("UTF-8"));
                sum.append("wrote case.properties stub (channel=")
                   .append(publisher ? "publisher" : "subscriber").append(")\n");
            }
        } else if (eventKinds > 0) {
            sum.append("no operation event auto-picked; see trace-samples/*-event.xds\n");
        } else {
            sum.append("no sync events in this trace — looks like a startup/init or "
                + "query-only trace (init/schema/response docs only)\n");
        }

        // directory.xds <- merged instances from all query-result documents
        String dir = mergeInstances(results);
        if (dir != null) {
            Files.write(outDir.resolve("directory.xds"), pretty(dir).getBytes("UTF-8"));
            sum.append("wrote directory.xds  (").append(results.size()).append(" query results merged)\n");
        } else {
            sum.append("no query results with instances; seed directory.xds by hand\n");
        }
        sum.append("samples in ").append(outDir.resolve("trace-samples"));
        if (docs.size() > MAX_SAMPLES) {
            sum.append("  (first ").append(MAX_SAMPLES).append(" of ").append(docs.size())
               .append(" docs; this looks like a multi-transaction log — input.xds is the "
                   + "first event, directory.xds merges all query results)");
        }
        sum.append('\n');
        return sum.toString();
    }

    private static String mergeInstances(List<Doc> results) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (Doc d : results) {
            try {
                Document doc = Xds.parse(d.xds);
                for (Element inst : allInstances(doc)) {
                    String key = inst.getAttribute("src-dn") + "|" + inst.getAttribute("dest-dn")
                        + "|" + inst.getAttribute("class-name");
                    byKey.putIfAbsent(key, Xds.serialize(inst.getOwnerDocument())
                        .replaceAll("(?s).*?(<instance.*?</instance>).*", "$1"));
                }
            } catch (Exception ignore) {
                // skip malformed result docs
            }
        }
        if (byKey.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<nds dtdversion=\"4.0\"><input>");
        for (String inst : byKey.values()) {
            sb.append(inst);
        }
        sb.append("</input></nds>");
        return sb.toString();
    }

    private static List<Element> allInstances(Document doc) {
        List<Element> out = new ArrayList<>();
        collectInstances(doc.getDocumentElement(), out);
        return out;
    }

    private static void collectInstances(Element el, List<Element> out) {
        for (Element c : Xds.childElements(el)) {
            if (c.getLocalName().equals("instance")) {
                out.add(c);
            } else {
                collectInstances(c, out);
            }
        }
    }

    private static String channelName(String ch) {
        if ("PT".equals(ch)) return "Publisher";
        if ("ST".equals(ch)) return "Subscriber";
        return ch;
    }

    private static String pretty(String xml) {
        try {
            return Xds.serialize(Xds.parse(xml)).replaceAll(">\\s*<", ">\n<");
        } catch (Exception e) {
            return xml;
        }
    }
}
