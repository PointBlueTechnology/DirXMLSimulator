# Runtime libraries (not committed)

These are proprietary NetIQ / OpenText Identity Manager jars, gitignored. To
build, copy them here from an IDM install or your IDM Driver Dependencies set:

    dirxml.jar  dirxml_misc.jar  nxsl.jar  xp.jar
    CommonDriverShim.jar  jclient.jar  dhutil.jar  XDS.jar
    js.jar      (repackaged Rhino — needed for ECMAScript es: functions)

Optional, for the live event sources:

    ldap.jar       (Novell/OpenText JLDAP SDK, com.novell.ldap — ships with IDM/
                    Designer) — only for the DxCMD features (`bin/sim dxcache`).
                    Supply manually like the jars above.

    postgresql.jar (PostgreSQL JDBC) — only for `bin/sim dbevents`. You do NOT
                    stage this by hand: Maven downloads it from Central and stages
                    it here automatically during the build, and releases bundle it
                    (it is open-source/BSD, unlike the jars above).

The engine jars (dirxml*, 4.10.1) are Java 21 bytecode — build and run on JDK 21.
