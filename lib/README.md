# Runtime libraries (not committed)

These are proprietary NetIQ / OpenText Identity Manager jars, gitignored. To
build, copy them here from an IDM install or your IDM Driver Dependencies set:

    dirxml.jar  dirxml_misc.jar  nxsl.jar  xp.jar
    CommonDriverShim.jar  jclient.jar  dhutil.jar  XDS.jar
    js.jar      (repackaged Rhino — needed for ECMAScript es: functions)

The engine jars (dirxml*, 4.10.1) are Java 21 bytecode — build and run on JDK 21.
