package com.pointblue.dirxml.sim;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** Name-keyed resolution of a vnd.nds.stream URL to a registered table. */
public class MappingTableStoreTest {

    @After
    public void tearDown() {
        MappingTableStore.clear();
    }

    @Test
    public void resolvesByTableNameAppearingAsADnComponent() {
        MappingTableStore.register("LocCodeMap", "<mapping-table/>LOC");
        // The engine's URL is opaque; the name appears as a path/DN component.
        assertNotNull(MappingTableStore.resolve("vnd.nds.stream://x/../../Library/LocCodeMap"));
        assertNotNull(MappingTableStore.resolve("vnd.nds.stream://x/cn=LocCodeMap,cn=Library"));
        assertEquals("<mapping-table/>LOC",
            MappingTableStore.resolve("vnd.nds.stream://x/cn=LocCodeMap,cn=Library"));
    }

    @Test
    public void doesNotMatchAPartialName() {
        MappingTableStore.register("Loc", "L");
        // "Loc" is a substring of "LocCodeMap" but not a whole component there.
        assertNull(MappingTableStore.resolve("vnd.nds.stream://x/cn=LocCodeMap,cn=Library"));
        assertNotNull(MappingTableStore.resolve("vnd.nds.stream://x/cn=Loc,cn=Library"));
    }

    @Test
    public void unknownUrlResolvesToNull() {
        MappingTableStore.register("A", "a");
        assertNull(MappingTableStore.resolve("vnd.nds.stream://x/cn=Nope,cn=Library"));
        assertNull(MappingTableStore.resolve(null));
    }
}
