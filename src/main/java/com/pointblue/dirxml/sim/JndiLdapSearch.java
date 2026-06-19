package com.pointblue.dirxml.sim;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The live {@link LdapQueryProcessor.Search} — a JNDI search against eDirectory.
 * Generalized from ClaimsCenter's hand-rolled LDAP code: it asks the directory
 * for the attributes the query requested and returns them LDAP-named, leaving all
 * value normalization to {@link LdapValueNormalizer} (driven by the schema).
 *
 * <p>One schema-driven touch matters for correctness: octet/stream syntaxes must
 * come back as {@code byte[]}, so this marks those LDAP attributes
 * <em>binary</em> ({@code java.naming.ldap.attributes.binary}) before searching;
 * everything else returns as {@code String}.
 *
 * <p>Exercised only when a case configures {@code ldap=} — there is no offline
 * test for it (it needs a reachable directory). If the host is unreachable the
 * search throws, and {@link LdapQueryProcessor} turns that into an XDS
 * {@code <status level="error">} rather than degrading silently.
 */
public final class JndiLdapSearch implements LdapQueryProcessor.Search {

    /** Connection settings; the password comes from the named-password channel. */
    public static final class Config {
        public String url;            // ldap://host:389 or ldaps://host:636
        public String bindDn;
        public String bindPassword;
        public boolean trustAllCerts; // for ldaps with a self-signed/internal CA
    }

    private final Config config;
    private final SchemaModel schema;

    public JndiLdapSearch(Config config, SchemaModel schema) {
        this.config = config;
        this.schema = schema == null ? SchemaModel.empty() : schema;
    }

    @Override
    public List<LdapQueryProcessor.Entry> search(LdapQueryProcessor.Request req) {
        LdapContext ctx = null;
        try {
            ctx = connect(binaryAttrs(req.attrs));
            SearchControls controls = new SearchControls();
            controls.setSearchScope(jndiScope(req.scope));
            if (!req.attrs.isEmpty()) {
                controls.setReturningAttributes(req.attrs.toArray(new String[0]));
            }
            NamingEnumeration<SearchResult> results = ctx.search(req.base, req.filter, controls);
            List<LdapQueryProcessor.Entry> entries = new ArrayList<>();
            while (results.hasMore()) {
                entries.add(toEntry(results.next()));
            }
            return entries;
        } catch (Exception e) {
            throw new RuntimeException("LDAP search failed: " + e.getMessage(), e);
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignore) {
                    // best effort
                }
            }
        }
    }

    /**
     * Read the eDirectory schema directly from the LDAP subschema: follow the root
     * DSE's {@code subschemaSubentry} to {@code cn=schema}, read its
     * {@code attributeTypes}/{@code objectClasses}, and parse via
     * {@link LdapSchemaReader}. Lets a live LDAP connection populate the schema with
     * no Designer project. Throws if the host/subschema can't be read.
     */
    public SchemaModel readSchema() {
        LdapContext ctx = null;
        try {
            ctx = connect(Set.of());
            String schemaDn = readSubschemaDn(ctx);
            SearchControls c = new SearchControls();
            c.setSearchScope(SearchControls.OBJECT_SCOPE);
            c.setReturningAttributes(new String[]{"attributeTypes", "objectClasses"});
            NamingEnumeration<SearchResult> r = ctx.search(schemaDn, "(objectClass=*)", c);
            List<String> attrTypes = new ArrayList<>();
            List<String> objClasses = new ArrayList<>();
            while (r.hasMore()) {
                Attributes a = r.next().getAttributes();
                collect(a.get("attributeTypes"), attrTypes);
                collect(a.get("objectClasses"), objClasses);
            }
            return LdapSchemaReader.parse(attrTypes, objClasses);
        } catch (Exception e) {
            throw new RuntimeException("could not read LDAP schema: " + e.getMessage(), e);
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignore) {
                    // best effort
                }
            }
        }
    }

    /**
     * Read a driver's config from the live Identity Vault: a subtree search under
     * the DriverSet DN, returning the {@code DirXML-Driver}/{@code -Rule}/etc.
     * objects with their {@code XmlData}/{@code DirXML-Policies}/shim attributes,
     * fed to {@link LdifDriverSource}. The XML-blob attributes are requested binary
     * and decoded as UTF-8 text. Throws if the subtree can't be read.
     */
    public LdifDriverSource readDriverConfig(String driverSetDn) {
        Set<String> binary = Set.of("xmldata", "dirxml-data", "dirxml-shimconfiginfo",
            "dirxml-configvalues", "dirxml-driverfilter", "dirxml-enginecontrolvalues");
        String[] want = {"objectClass", "cn", "XmlData", "DirXML-Policies",
            "DirXML-ShimConfigInfo", "DirXML-ConfigValues", "DirXML-JavaModule",
            "DirXML-DriverFilter", "DirXML-ShimAuthServer", "DirXML-ShimAuthID",
            "DirXML-EngineControlValues",
            // DirXML-Resource content (mapping tables) lives in DirXML-Data, not XmlData
            "DirXML-Data", "DirXML-ContentType"};
        LdapContext ctx = null;
        try {
            ctx = connect(binary);
            SearchControls c = new SearchControls();
            c.setSearchScope(SearchControls.SUBTREE_SCOPE);
            c.setReturningAttributes(want);
            NamingEnumeration<SearchResult> r = ctx.search(driverSetDn, "(objectClass=*)", c);
            List<LdifDriverSource.Entry> entries = new ArrayList<>();
            while (r.hasMore()) {
                SearchResult sr = r.next();
                Map<String, List<String>> m = new LinkedHashMap<>();
                NamingEnumeration<? extends Attribute> all = sr.getAttributes().getAll();
                while (all.hasMore()) {
                    Attribute at = all.next();
                    List<String> vals = new ArrayList<>();
                    NamingEnumeration<?> vs = at.getAll();
                    while (vs.hasMore()) {
                        Object v = vs.next();
                        vals.add(v instanceof byte[]
                            ? new String((byte[]) v, java.nio.charset.StandardCharsets.UTF_8)
                            : String.valueOf(v));
                    }
                    m.put(at.getID().toLowerCase(), vals);
                }
                entries.add(new LdifDriverSource.Entry(sr.getNameInNamespace(), m));
            }
            return LdifDriverSource.fromEntries(entries);
        } catch (Exception e) {
            throw new RuntimeException("could not read driver config from LDAP: " + e.getMessage(), e);
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignore) {
                    // best effort
                }
            }
        }
    }

    private static String readSubschemaDn(LdapContext ctx) throws Exception {
        SearchControls c = new SearchControls();
        c.setSearchScope(SearchControls.OBJECT_SCOPE);
        c.setReturningAttributes(new String[]{"subschemaSubentry"});
        NamingEnumeration<SearchResult> r = ctx.search("", "(objectClass=*)", c);
        if (r.hasMore()) {
            Attribute sse = r.next().getAttributes().get("subschemaSubentry");
            if (sse != null && sse.size() > 0) {
                return String.valueOf(sse.get(0));
            }
        }
        return "cn=schema";   // eDir default
    }

    private static void collect(Attribute attr, List<String> into) throws Exception {
        if (attr == null) {
            return;
        }
        NamingEnumeration<?> vals = attr.getAll();
        while (vals.hasMore()) {
            into.add(String.valueOf(vals.next()));
        }
    }

    private LdapContext connect(Set<String> binaryAttrs) throws Exception {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, config.url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        if (config.bindDn != null) {
            env.put(Context.SECURITY_PRINCIPAL, config.bindDn);
        }
        if (config.bindPassword != null) {
            env.put(Context.SECURITY_CREDENTIALS, config.bindPassword);
        }
        if (config.url != null && config.url.toLowerCase().startsWith("ldaps")) {
            env.put(Context.SECURITY_PROTOCOL, "ssl");
            if (config.trustAllCerts) {
                env.put("java.naming.ldap.factory.socket", TrustAllSocketFactory.class.getName());
            }
        }
        if (!binaryAttrs.isEmpty()) {
            env.put("java.naming.ldap.attributes.binary", String.join(" ", binaryAttrs));
        }
        return new InitialLdapContext(env, null);
    }

    /** LDAP attribute names whose schema syntax requires byte[] (octet/stream). */
    private Set<String> binaryAttrs(List<String> ldapAttrs) {
        Set<String> binary = new HashSet<>();
        for (String ldapName : ldapAttrs) {
            SchemaModel.Attr a = schema.attr(ldapName);
            if (a != null && isBinarySyntax(a.syntax)) {
                binary.add(a.ldap != null && !a.ldap.isEmpty() ? a.ldap : ldapName);
            }
        }
        return binary;
    }

    private static boolean isBinarySyntax(String syntax) {
        if (syntax == null) {
            return false;
        }
        switch (syntax.toLowerCase()) {
            case "octet-string":
            case "octet-list":
            case "net-address":
            case "stream":
                return true;
            default:
                return false;
        }
    }

    private static int jndiScope(int scope) {
        switch (scope) {
            case LdapQueryProcessor.SCOPE_BASE:
                return SearchControls.OBJECT_SCOPE;
            case LdapQueryProcessor.SCOPE_ONE_LEVEL:
                return SearchControls.ONELEVEL_SCOPE;
            default:
                return SearchControls.SUBTREE_SCOPE;
        }
    }

    private static LdapQueryProcessor.Entry toEntry(SearchResult result) throws Exception {
        Map<String, List<Object>> attrs = new LinkedHashMap<>();
        Attributes a = result.getAttributes();
        NamingEnumeration<? extends Attribute> all = a.getAll();
        while (all.hasMore()) {
            Attribute attr = all.next();
            List<Object> values = new ArrayList<>();
            NamingEnumeration<?> vals = attr.getAll();
            while (vals.hasMore()) {
                values.add(vals.next());   // String or byte[] (binary attrs)
            }
            attrs.put(attr.getID(), values);
        }
        return new LdapQueryProcessor.Entry(result.getNameInNamespace(), attrs);
    }
}
