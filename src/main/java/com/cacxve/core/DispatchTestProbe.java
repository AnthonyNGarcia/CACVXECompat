package com.cacxve.core;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DispatchTestProbe
{
    public static volatile int dispatchCount;
    private static final ConcurrentHashMap<UUID, Integer> DISPATCHES_BY_WAGON = new ConcurrentHashMap<>();

    private DispatchTestProbe()
    {
    }

    public static void record(UUID wagonId)
    {
        dispatchCount++;
        DISPATCHES_BY_WAGON.merge(wagonId, 1, Integer::sum);
    }

    public static int dispatchesFor(UUID wagonId)
    {
        return DISPATCHES_BY_WAGON.getOrDefault(wagonId, 0);
    }
}
