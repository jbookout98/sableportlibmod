package com.sableport.mod.teleport.state;

import com.sableport.mod.teleport.SubLevelDimensionTeleport;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transfers runtime state that is not included in Sable plot NBT.

 * Implementations may support balloons, engines, custom physics attachments,
 * fluid networks, energy networks, or any other external runtime system.

 * @param <T> immutable captured state produced before source removal
 */
public interface SubLevelTeleportStateAdapter<T> {

    /**
     * Stable identifier used for diagnostics and registration.
     */
    String id();

    /**
     * Returns whether this adapter can run in the current mod environment.
   */
    boolean isAvailable();

    /**
     * Captures all relevant state before source sublevels are removed.
     */
    T capture(
            ServerLevel sourceLevel,
            List<ServerSubLevel> family,
            ServerSubLevelContainer sourceContainer
    );


    void restore(
            ServerLevel targetLevel,
            T capturedState,
            Map<
                    UUID,
                    SubLevelDimensionTeleport.PlotTranslation
                    > translationsByMember
    );

    default boolean isEmpty(T capturedState) {
        return capturedState == null;
    }
}