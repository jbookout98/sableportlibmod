package com.sableport.mod.teleport.state;

import com.sableport.mod.teleport.SubLevelDimensionTeleport;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SubLevelTeleportStateRegistry {

    private static final Map<
            String,
            SubLevelTeleportStateAdapter<?>
            > ADAPTERS =
            new LinkedHashMap<>();

    private SubLevelTeleportStateRegistry() {
    }

    public static synchronized void register(
            final SubLevelTeleportStateAdapter<?> adapter
    ) {
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "Teleport state adapter cannot be null"
            );
        }

        final String id = adapter.id();

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Teleport state adapter ID cannot be blank"
            );
        }

        if (ADAPTERS.containsKey(id)) {
            throw new IllegalStateException(
                    "Duplicate teleport state adapter: " + id
            );
        }

        ADAPTERS.put(id, adapter);

        Sable.LOGGER.info(
                "Registered sublevel teleport state adapter: {}",
                id
        );
    }

    public static synchronized List<
            CapturedTeleportState<?>
            > captureAll(
            final ServerLevel sourceLevel,
            final List<ServerSubLevel> family,
            final ServerSubLevelContainer sourceContainer
    ) {
        final List<CapturedTeleportState<?>> result =
                new ArrayList<>();

        for (final SubLevelTeleportStateAdapter<?> adapter
                : ADAPTERS.values()) {

            if (!adapter.isAvailable()) {
                continue;
            }

            try {
                captureUnchecked(
                        adapter,
                        sourceLevel,
                        family,
                        sourceContainer,
                        result
                );
            } catch (final Exception exception) {
                /*
                 * Runtime-state transfer should not silently corrupt a ship.
                 * Abort the teleport by propagating this exception.
                 */
                throw new IllegalStateException(
                        "Failed to capture teleport state for adapter "
                                + adapter.id(),
                        exception
                );
            }
        }

        return List.copyOf(result);
    }

    public static void restoreAll(
            final ServerLevel targetLevel,
            final List<CapturedTeleportState<?>> capturedStates,
            final Map<
                    UUID,
                    SubLevelDimensionTeleport.PlotTranslation
                    > translationsByMember
    ) {
        for (final CapturedTeleportState<?> captured
                : capturedStates) {

            try {
                restoreUnchecked(
                        targetLevel,
                        captured,
                        translationsByMember
                );
            } catch (final Exception exception) {
                throw new IllegalStateException(
                        "Failed to restore teleport state for adapter "
                                + captured.adapter().id(),
                        exception
                );
            }
        }
    }

    private static <T> void captureUnchecked(
            final SubLevelTeleportStateAdapter<T> adapter,
            final ServerLevel sourceLevel,
            final List<ServerSubLevel> family,
            final ServerSubLevelContainer sourceContainer,
            final List<CapturedTeleportState<?>> output
    ) {
        final T value =
                adapter.capture(
                        sourceLevel,
                        family,
                        sourceContainer
                );

        if (adapter.isEmpty(value)) {
            return;
        }

        output.add(
                new CapturedTeleportState<>(
                        adapter,
                        value
                )
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> void restoreUnchecked(
            final ServerLevel targetLevel,
            final CapturedTeleportState<?> untypedState,
            final Map<
                    UUID,
                    SubLevelDimensionTeleport.PlotTranslation
                    > translationsByMember
    ) {
        final CapturedTeleportState<T> state =
                (CapturedTeleportState<T>) untypedState;

        state.adapter().restore(
                targetLevel,
                state.value(),
                translationsByMember
        );
    }
}