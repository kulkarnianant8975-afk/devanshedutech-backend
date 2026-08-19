package com.devanshedutech.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class RoleTest {

    @Test
    @DisplayName("legacy admin values still resolve to ADMIN")
    void parsesLegacyAdmin() {
        assertEquals(Role.ADMIN, Role.parse("admin"));
        assertEquals(Role.ADMIN, Role.parse("ADMIN"));
        assertEquals(Role.ADMIN, Role.parse(" Admin "));
    }

    @Test
    @DisplayName("the legacy USER value means no access, not read-only staff")
    void legacyUserGetsNoAccess() {
        // Self-registration used to be open. Those rows are members of the public, so mapping
        // them onto VIEWER would hand the whole pipeline to everyone who ever signed up.
        assertEquals(Role.NONE, Role.parse("user"));
        assertEquals(Role.NONE, Role.parse("USER"));
        assertFalse(Role.NONE.isStaff());
    }

    @ParameterizedTest
    @DisplayName("anything unrecognised denies rather than allows")
    @ValueSource(strings = {"", "   ", "root", "owner", "administrator", "SALES_MANAGER", "'; drop table users;--"})
    void unknownValuesDeny(String raw) {
        assertEquals(Role.NONE, Role.parse(raw));
    }

    @Test
    void nullIsNoAccess() {
        assertEquals(Role.NONE, Role.parse(null));
    }

    @Test
    @DisplayName("counsellor spellings map onto the sales executive role")
    void parsesCounsellorAliases() {
        assertEquals(Role.SALES_EXECUTIVE, Role.parse("counsellor"));
        assertEquals(Role.SALES_EXECUTIVE, Role.parse("COUNSELOR"));
        assertEquals(Role.SALES_EXECUTIVE, Role.parse("sales_exec"));
    }

    @Test
    void ranksAreStrictlyOrdered() {
        assertTrue(Role.SUPER_ADMIN.outranks(Role.ADMIN));
        assertTrue(Role.ADMIN.outranks(Role.MANAGER));
        assertTrue(Role.MANAGER.outranks(Role.SALES_EXECUTIVE));
        assertTrue(Role.SALES_EXECUTIVE.outranks(Role.VIEWER));
        assertTrue(Role.VIEWER.outranks(Role.NONE));

        assertFalse(Role.ADMIN.outranks(Role.ADMIN), "equal rank must not outrank itself");
        assertFalse(Role.MANAGER.outranks(Role.SUPER_ADMIN));
    }

    @Test
    void atLeastIsInclusive() {
        assertTrue(Role.ADMIN.atLeast(Role.ADMIN));
        assertTrue(Role.SUPER_ADMIN.atLeast(Role.ADMIN));
        assertFalse(Role.MANAGER.atLeast(Role.ADMIN));
    }
}
