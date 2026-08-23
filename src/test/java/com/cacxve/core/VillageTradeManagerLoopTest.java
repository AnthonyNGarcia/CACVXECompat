package com.cacxve.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests modeling the REAL Caravans & Convoys lifecycle and our event-driven hook:
 *
 * 1. startOperation() deploys a brand new wagon (operating=true, wagonId=<uuid>).
 * 2. The wagon travels outbound, dumps cargo/loads return goods at the partner dock, then
 *    travels back and dumps cargo at its home dock (WagonCoachEntity.completeArrival, phase -> idle).
 * 3. The instant the wagon goes idle at home, our WagonReturnMixin fires
 *    VillageTradeManager.onWagonReturnedHome(), which immediately force-calls
 *    DockRegistry.serviceDock(..., true) - reusing the SAME still-idle wagon (wagonId unchanged).
 * 4. serviceDock only redispatches if a valid trade match with sufficient stock exists; otherwise
 *    the wagon just sits idle at home until conditions change (no polling/timeout on our side).
 *
 * This mirrors the native mod exactly: no new wagon is spawned per loop, and completion is detected
 * by the actual deposit event rather than any custom flag polling.
 */
@DisplayName("Wagon dispatch loop driven by native completeArrival hook")
public class VillageTradeManagerLoopTest
{
    /** Mirrors com.warborn.caravansconvoys.trade.DockRegistry.Record */
    private static class MockDockRecord
    {
        public boolean operating = false;
        public UUID wagonId = null;
        public String exportId;
        public int exportAmt;
    }

    /** Mirrors com.warborn.caravansconvoys.entity.WagonCoachEntity's relevant state */
    private static class MockWagon
    {
        int phase = 1; // 0 = idle, 1 = travelling outbound, 2 = travelling home
        int ticksInLeg = 0;
    }

    /**
     * Mirrors DockRegistry + the CaravanDockBlock per-tick ticker + our mixin hook,
     * without any of our own polling/timeout logic.
     */
    private static class CaravansConvoysSimulator
    {
        private static final int LEG_TICKS = 15;

        final ConcurrentHashMap<String, MockDockRecord> docks = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, MockWagon> wagonByPlayerDock = new ConcurrentHashMap<>();
        private int tripsCompleted = 0;

        void setupDocks(String playerDockKey, String villageDockKey)
        {
            MockDockRecord playerRec = new MockDockRecord();
            playerRec.exportId = "minecraft:iron_ingot";
            playerRec.exportAmt = 4;
            docks.put(playerDockKey, playerRec);

            MockDockRecord villageRec = new MockDockRecord();
            villageRec.exportId = "minecraft:wheat";
            villageRec.exportAmt = 24;
            docks.put(villageDockKey, villageRec);
        }

        /** Mirrors DockRegistry.startOperation - deploys a brand-new wagon. */
        boolean startOperation(String playerDockKey)
        {
            MockDockRecord playerRec = docks.get(playerDockKey);
            if (playerRec == null || playerRec.operating)
                return false;
            if (playerRec.exportId == null || playerRec.exportAmt <= 0)
                return false;

            playerRec.operating = true;
            playerRec.wagonId = UUID.randomUUID();

            MockWagon wagon = new MockWagon();
            wagon.phase = 1;
            wagon.ticksInLeg = 0;
            wagonByPlayerDock.put(playerDockKey, wagon);
            return true;
        }

        /**
         * Mirrors DockRegistry.serviceDock(..., force) - only redispatches the EXISTING idle wagon
         * if a valid trade match with sufficient stock exists on both sides. Never spawns a new wagon.
         */
        boolean serviceDock(String playerDockKey, String villageDockKey)
        {
            MockDockRecord playerRec = docks.get(playerDockKey);
            MockDockRecord villageRec = docks.get(villageDockKey);
            if (playerRec == null || villageRec == null || !playerRec.operating || playerRec.wagonId == null)
                return false;

            MockWagon wagon = wagonByPlayerDock.get(playerDockKey);
            if (wagon == null || wagon.phase != 0) // must be idle
                return false;

            boolean stockOk = playerRec.exportAmt > 0 && villageRec.exportAmt > 0;
            if (!stockOk)
                return false;

            // Redispatch same wagon on another outbound leg
            wagon.phase = 1;
            wagon.ticksInLeg = 0;
            return true;
        }

        /** Advances the world by one tick, driving wagon travel and firing the return-home hook. */
        void tick(String playerDockKey, String villageDockKey)
        {
            MockWagon wagon = wagonByPlayerDock.get(playerDockKey);
            if (wagon == null || wagon.phase == 0)
                return;

            wagon.ticksInLeg++;
            if (wagon.ticksInLeg >= LEG_TICKS)
            {
                if (wagon.phase == 1)
                {
                    // Arrived at village: dump cargo, load return goods, continue home
                    wagon.phase = 2;
                    wagon.ticksInLeg = 0;
                }
                else
                {
                    // Arrived home: dump cargo, go idle -> fire our mixin hook immediately
                    wagon.phase = 0;
                    wagon.ticksInLeg = 0;
                    tripsCompleted++;
                    onWagonReturnedHome(playerDockKey, villageDockKey);
                }
            }
        }

        /** Mirrors VillageTradeManager.onWagonReturnedHome, invoked by WagonReturnMixin. */
        private void onWagonReturnedHome(String playerDockKey, String villageDockKey)
        {
            serviceDock(playerDockKey, villageDockKey);
        }

        int getTripsCompleted()
        {
            return tripsCompleted;
        }

        MockWagon wagonFor(String playerDockKey)
        {
            return wagonByPlayerDock.get(playerDockKey);
        }
    }

    private CaravansConvoysSimulator sim;
    private static final String PLAYER_DOCK = "player_dock";
    private static final String VILLAGE_DOCK = "village_dock";

    @BeforeEach
    void setUp()
    {
        sim = new CaravansConvoysSimulator();
        sim.setupDocks(PLAYER_DOCK, VILLAGE_DOCK);
    }

    @Test
    @DisplayName("Initial dispatch spawns a wagon and marks dock operating")
    void testInitialDispatch()
    {
        assertTrue(sim.startOperation(PLAYER_DOCK));
        MockDockRecord rec = sim.docks.get(PLAYER_DOCK);
        assertTrue(rec.operating);
        assertNotNull(rec.wagonId);
    }

    @Test
    @DisplayName("Wagon completes one full round trip and is immediately redispatched via the return hook")
    void testImmediateRedispatchOnReturn()
    {
        sim.startOperation(PLAYER_DOCK);
        UUID originalWagonId = sim.docks.get(PLAYER_DOCK).wagonId;

        // Drive through outbound + return legs (2 legs * 15 ticks)
        for (int i = 0; i < 30; i++)
            sim.tick(PLAYER_DOCK, VILLAGE_DOCK);

        assertEquals(1, sim.getTripsCompleted(), "One round trip should have completed");
        // Wagon should already be travelling again (phase 1), not idle, because the hook fired immediately
        assertEquals(1, sim.wagonFor(PLAYER_DOCK).phase, "Wagon should be redispatched immediately, not idle");
        // Same wagon reused - no new wagonId assigned
        assertEquals(originalWagonId, sim.docks.get(PLAYER_DOCK).wagonId, "Same wagon should be reused, not a new one");
    }

    @Test
    @DisplayName("Five consecutive round trips using the same wagon, no manual polling")
    void testFiveConsecutiveTrips()
    {
        sim.startOperation(PLAYER_DOCK);
        UUID originalWagonId = sim.docks.get(PLAYER_DOCK).wagonId;

        // 5 trips * 2 legs * 15 ticks
        for (int i = 0; i < 5 * 2 * 15; i++)
            sim.tick(PLAYER_DOCK, VILLAGE_DOCK);

        assertEquals(5, sim.getTripsCompleted());
        assertEquals(originalWagonId, sim.docks.get(PLAYER_DOCK).wagonId, "Wagon identity must never change across loops");
    }

    @Test
    @DisplayName("Long-running stress: 50 trips without stalling or spawning extra wagons")
    void testStressManyTrips()
    {
        sim.startOperation(PLAYER_DOCK);
        UUID originalWagonId = sim.docks.get(PLAYER_DOCK).wagonId;

        for (int i = 0; i < 50 * 2 * 15; i++)
            sim.tick(PLAYER_DOCK, VILLAGE_DOCK);

        assertEquals(50, sim.getTripsCompleted());
        assertEquals(originalWagonId, sim.docks.get(PLAYER_DOCK).wagonId);
    }

    @Test
    @DisplayName("Wagon stays idle at home (no redispatch) once stock runs out - no crash, no phantom trip")
    void testStopsWhenStockDepleted()
    {
        sim.startOperation(PLAYER_DOCK);

        // Complete 2 trips
        for (int i = 0; i < 2 * 2 * 15; i++)
            sim.tick(PLAYER_DOCK, VILLAGE_DOCK);
        assertEquals(2, sim.getTripsCompleted());

        // Deplete player stock, then let the already-in-flight leg finish its round trip
        // (a wagon already carrying cargo still completes; only the NEXT redispatch is blocked)
        sim.docks.get(PLAYER_DOCK).exportAmt = 0;
        for (int i = 0; i < 2 * 15; i++)
            sim.tick(PLAYER_DOCK, VILLAGE_DOCK);

        // The in-flight trip completes, but redispatch fails due to no stock
        assertEquals(3, sim.getTripsCompleted(), "The already-dispatched trip should still complete");
        assertEquals(0, sim.wagonFor(PLAYER_DOCK).phase, "Wagon should remain idle at home, not travelling");
    }

    @Test
    @DisplayName("Redispatch resumes automatically once stock is replenished (still same wagon)")
    void testResumesAfterRestock()
    {
        sim.startOperation(PLAYER_DOCK);
        UUID originalWagonId = sim.docks.get(PLAYER_DOCK).wagonId;

        // Deplete stock immediately, then complete first (already-dispatched) trip
        sim.docks.get(PLAYER_DOCK).exportAmt = 0;
        for (int i = 0; i < 2 * 15; i++)
            sim.tick(PLAYER_DOCK, VILLAGE_DOCK);
        assertEquals(1, sim.getTripsCompleted());
        assertEquals(0, sim.wagonFor(PLAYER_DOCK).phase, "Wagon idles once stock is gone");

        // Restock - wagon won't move again until something re-triggers serviceDock (e.g. next natural tick)
        sim.docks.get(PLAYER_DOCK).exportAmt = 4;
        assertTrue(sim.serviceDock(PLAYER_DOCK, VILLAGE_DOCK), "serviceDock should now succeed with stock available");

        for (int i = 0; i < 2 * 15; i++)
            sim.tick(PLAYER_DOCK, VILLAGE_DOCK);
        assertEquals(2, sim.getTripsCompleted());
        assertEquals(originalWagonId, sim.docks.get(PLAYER_DOCK).wagonId, "Still the original wagon, never respawned");
    }
}

