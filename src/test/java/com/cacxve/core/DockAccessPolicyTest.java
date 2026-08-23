package com.cacxve.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DockAccessPolicyTest
{
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void ownerCanAccessWhenPublicAccessIsDisabled()
    {
        assertTrue(Config.canAccessCaravanDock(OWNER, OWNER, false, false));
    }

    @Test
    void strangerCannotAccessWhenPublicAccessIsDisabled()
    {
        assertFalse(Config.canAccessCaravanDock(OWNER, OTHER, false, false));
    }

    @Test
    void strangerCanAccessWhenPublicAccessIsEnabled()
    {
        assertTrue(Config.canAccessCaravanDock(OWNER, OTHER, false, true));
    }

    @Test
    void unclaimedDockCanBeAccessedByAnyPlayer()
    {
        assertTrue(Config.canAccessCaravanDock(null, OTHER, false, false));
    }

    @Test
    void villageDockRemainsProtectedWhenPublicAccessIsEnabled()
    {
        assertFalse(Config.canAccessCaravanDock(OWNER, OTHER, true, true));
    }

    @Test
    void nullPlayerCannotUseOwnedDock()
    {
        assertFalse(Config.canAccessCaravanDock(OWNER, null, false, true));
    }
}
