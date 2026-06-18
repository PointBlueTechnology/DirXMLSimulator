package com.pointblue.dirxml.sim;

import org.w3c.dom.Element;

/**
 * The shim init parameters as they are already defined in a driver export or a
 * Designer project — the driver class, DN, authentication endpoint/id, and the
 * three option blocks ({@code <driver-options>}/{@code <subscriber-options>}/
 * {@code <publisher-options>}, each a {@code <configuration-values><definitions>}
 * tree of resolved values).
 *
 * <p>The init doc is <em>defined in the source</em>; callers should obtain this
 * via {@link DriverExport#shimConfig()} or {@link DesignerProject#shimConfig}
 * rather than hand-populating options, then hand it to {@link InitDocBuilder}.
 * The password is never stored in the source — it is supplied separately from
 * the named-password channel.
 *
 * <p>Any field may be null/empty when the source omits it; {@link InitDocBuilder}
 * tolerates that (an absent block is simply not emitted).
 */
public final class ShimConfig {

    /** Driver shim class: export {@code <java-module value>}, project {@code DirXML-JavaModule}. */
    public final String shimClass;
    /** Driver DN for {@code init-params/@src-dn}: export {@code @dn}, project best-effort. */
    public final String driverDn;
    /** Auth endpoint: export shim-config, project {@code DirXML-ShimAuthServer}. */
    public final String authServer;
    /** Auth id/user: project {@code DirXML-ShimAuthID}. */
    public final String authId;
    /** {@code <driver-options>} (or its {@code <configuration-values>}); may be null. */
    public final Element driverOptions;
    /** {@code <subscriber-options>}; may be null. */
    public final Element subscriberOptions;
    /** {@code <publisher-options>}; may be null. */
    public final Element publisherOptions;

    public ShimConfig(String shimClass, String driverDn, String authServer, String authId,
                      Element driverOptions, Element subscriberOptions, Element publisherOptions) {
        this.shimClass = shimClass;
        this.driverDn = driverDn;
        this.authServer = authServer;
        this.authId = authId;
        this.driverOptions = driverOptions;
        this.subscriberOptions = subscriberOptions;
        this.publisherOptions = publisherOptions;
    }
}
