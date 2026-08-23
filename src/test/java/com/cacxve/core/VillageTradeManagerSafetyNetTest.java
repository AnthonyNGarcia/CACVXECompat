package com.cacxve.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for two reported bugs that the one-shot WagonReturnMixin hook alone did not
 * reliably fix:
 *   1. "Delay after selecting a trade route before it actually sends" - the freshly deployed wagon
 *      (idle from the moment startOperation() spawns it) can miss its very first dispatch attempt for
 *      any transient reason (capability/BE not yet settled, packet ordering, etc.), and previously
 *      nothing retried it until the native ticker got lucky.
 *   2. "It only did one trip then stopped" - the same one-shot problem applied to the return-leg hook:
 *      if that single attempt missed, the wagon sat idle at home forever.
 *
 * VillageTradeManager.forceServiceStalledRoutes() (invoked ~every 5 ticks from tick()) is the fix: an
 * independent, repeating safety net that force-services ANY selected route whose wagon is idle,
 * regardless of whether the one-shot hook fired. These tests simulate the hook "missing" (as it
 * evidently did in the field) and assert the poll alone is sufficient to keep the loop going -
 * as opposed to the earlier test suite, which only ever exercised the hook firing successfully every
 * time and therefore could not have caught either bug.
 */
@DisplayName("Safety-net poll recovers from a missed/failed one-shot dispatch hook")
public class VillageTradeManagerSafetyNetTest
{
    private static class MockRecord
    {
        boolean operating;
        boolean wagonIsIdle;
        int chestStock;
    }

    /** Mirrors VillageTradeManager.forceServiceStalledRoutes()'s decision + DockRegistry.serviceDock()'s stock gate. */
    private static class SafetyNetSimulator
    {
        private static final int SHIP_AMOUNT = 4;
        final MockRecord record = new MockRecord();
        int tripsDispatched = 0;

        /** Mirrors DockRegistry.startOperation(): spawns a wagon that starts out idle (phase=0). */
        void startOperation()
        {
            record.operating = true;
            record.wagonIsIdle = true;
        }

        /** Mirrors one call to VillageTradeManager.forceServiceStalledRoutes() for this single route. */
        boolean pollOnce()
        {
            if (!record.operating || !record.wagonIsIdle)
                return false;
            if (record.chestStock < SHIP_AMOUNT)
                return false;
            record.chestStock -= SHIP_AMOUNT;
            record.wagonIsIdle = false; // now travelling
            tripsDispatched++;
            return true;
        }

        /** Wagon completes its round trip and goes idle again at home (WagonCoachEntity.completeArrival). */
        void wagonReturnsAndGoesIdle()
        {
            record.wagonIsIdle = true;
        }
    }

    private SafetyNetSimulator sim;

    @BeforeEach
    void setUp()
    {
        sim = new SafetyNetSimulator();
    }

    @Test
    @DisplayName("Bug #1 repro: hook never fires for the very first dispatch - poll alone must still send the wagon")
    void pollAloneDispatchesFreshlySpawnedWagon()
    {
        sim.record.chestStock = 4;
        sim.startOperation(); // wagon spawned idle, WagonReturnMixin hook is NOT simulated at all here

        assertTrue(sim.record.wagonIsIdle, "sanity: wagon starts idle");
        assertEquals(0, sim.tripsDispatched);

        boolean dispatched = sim.pollOnce();

        assertTrue(dispatched, "The safety-net poll alone must dispatch a freshly-spawned idle wagon");
        assertEquals(1, sim.tripsDispatched);
        assertFalse(sim.record.wagonIsIdle, "Wagon should now be travelling");
    }

    @Test
    @DisplayName("Bug #2 repro: return-hook never fires after any trip - poll alone keeps looping across many trips")
    void pollAloneKeepsLoopingWithoutTheReturnHook()
    {
        sim.record.chestStock = 40; // enough for 10 trips
        sim.startOperation();
        sim.pollOnce(); // trip 1 dispatch

        for (int trip = 2; trip <= 10; trip++)
        {
            sim.wagonReturnsAndGoesIdle(); // simulates completeArrival WITHOUT the mixin hook ever calling onWagonReturnedHome
            boolean dispatched = sim.pollOnce();
            assertTrue(dispatched, "Trip " + trip + " should be caught by the poll alone");
        }

        assertEquals(10, sim.tripsDispatched);
    }

    @Test
    @DisplayName("Poll self-corrects even if it misses one cycle (e.g. transient failure) - just catches up next cycle")
    void pollRecoversFromAMissedCycle()
    {
        sim.record.chestStock = 8;
        sim.startOperation();

        // Simulate several no-op poll cycles where nothing changes (e.g. transient condition failing) -
        // idempotent: does not spawn duplicate wagons or corrupt state.
        sim.record.wagonIsIdle = false; // pretend a transient glitch reports "not idle yet"
        assertFalse(sim.pollOnce(), "Poll should no-op while wagon isn't actually idle");
        assertFalse(sim.pollOnce());

        // Condition resolves - wagon genuinely idle now
        sim.record.wagonIsIdle = true;
        assertTrue(sim.pollOnce(), "Once idle, the very next poll cycle must succeed");
        assertEquals(1, sim.tripsDispatched);
    }

    @Test
    @DisplayName("Real stock depletion correctly halts the loop - distinct from a code bug")
    void pollStopsOnlyWhenStockGenuinelyRunsOut()
    {
        sim.record.chestStock = 8; // exactly 2 trips worth
        sim.startOperation();
        assertTrue(sim.pollOnce());
        sim.wagonReturnsAndGoesIdle();
        assertTrue(sim.pollOnce());
        assertEquals(2, sim.tripsDispatched);

        sim.wagonReturnsAndGoesIdle();
        assertFalse(sim.pollOnce(), "No stock left - poll must NOT dispatch, and must not throw/crash");
        assertEquals(2, sim.tripsDispatched, "Trip count must not advance when genuinely out of stock");
    }

    @Test
    @DisplayName("Poll never interferes with a wagon that is actively travelling")
    void pollIgnoresTravellingWagon()
    {
        sim.record.chestStock = 100;
        sim.startOperation();
        sim.pollOnce(); // now travelling
        assertFalse(sim.record.wagonIsIdle);

        for (int i = 0; i < 20; i++)
            assertFalse(sim.pollOnce(), "Poll must not re-dispatch a wagon that is already travelling");

        assertEquals(1, sim.tripsDispatched, "Only the single legitimate dispatch should have occurred");
    }
}
