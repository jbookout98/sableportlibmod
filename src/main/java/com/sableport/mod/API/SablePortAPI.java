package com.sableport.mod.API;

import com.sableport.mod.storage.SubLevelHierarchyPerDimension;
import com.sableport.mod.teleport.SubLevelDimensionTeleport;
import com.sableport.mod.teleport.state.SubLevelTeleportStateAdapter;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import com.sableport.mod.teleport.state.CapturedTeleportState;
import com.sableport.mod.teleport.state.SubLevelTeleportStateRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;


public final class SablePortAPI {

    private SablePortAPI() {
    }


    public static @Nullable ServerSubLevel teleportFamily(
            final ServerSubLevel source,
            final ServerLevel targetLevel,
            final Vector3dc targetPosition,
            final @Nullable Quaterniondc targetOrientation
    ) {
        if (source == null || targetLevel == null || targetPosition == null) {
            return null;
        }

        return SubLevelDimensionTeleport.teleport(
                source,
                targetLevel,
                targetPosition,
                targetOrientation
        );
    }


    public static @Nullable ServerSubLevel getFamilyRoot(
            final ServerSubLevel source
    ) {
        if (source == null) {
            return null;
        }

        final ServerSubLevelContainer container =
                SubLevelContainer.getContainer(source.getLevel());

        if (container == null) {
            return null;
        }

        final UUID rootId =
                SubLevelHierarchyPerDimension.getRootId(source, container);

        final SubLevel possibleRoot = container.getSubLevel(rootId);

        return possibleRoot instanceof ServerSubLevel root
                ? root
                : null;
    }

    public static void registerTeleportStateAdapter(final SubLevelTeleportStateAdapter<?> adapter)
    {
        SubLevelTeleportStateRegistry.register(adapter);
    }

    public static List<ServerSubLevel> getFamily(final ServerSubLevel source)
    {
        final ServerSubLevel root = getFamilyRoot(source);

        if (root == null) {
            return List.of();
        }

        final ServerSubLevelContainer container =
                SubLevelContainer.getContainer(root.getLevel());

        if (container == null) {
            return List.of();
        }

        final List<ServerSubLevel> family = new ArrayList<>();
        final Set<UUID> visited = new HashSet<>();
        final Deque<ServerSubLevel> pending = new ArrayDeque<>();

        pending.push(root);

        while (!pending.isEmpty()) {
            final ServerSubLevel current = pending.pop();

            if (!visited.add(current.getUniqueId())) {
                continue;
            }

            family.add(current);

            final Set<UUID> childIds =
                    SubLevelHierarchyPerDimension.collectDirectChildIds(
                            current,
                            container
                    );

            for (final UUID childId : childIds) {
                if (visited.contains(childId)) {
                    continue;
                }

                final SubLevel possibleChild =
                        container.getSubLevel(childId);

                if (possibleChild instanceof ServerSubLevel child) {
                    pending.push(child);
                }
            }
        }

        return List.copyOf(family);
    }


    public static @Nullable AABB getFamilyBoundingBox(
            final ServerSubLevel source
    ) {
        final List<ServerSubLevel> family = getFamily(source);

        if (family.isEmpty()) {
            return null;
        }

        AABB combined = toMinecraftAabb(family.getFirst().boundingBox());

        for (int i = 1; i < family.size(); i++) {
            combined = combined.minmax(
                    toMinecraftAabb(family.get(i).boundingBox())
            );
        }

        return combined;
    }

    public static @Nullable Vector3dc getFamilyCenter(
            final ServerSubLevel source
    ) {
        final AABB bounds = getFamilyBoundingBox(source);

        if (bounds == null) {
            return null;
        }

        return new org.joml.Vector3d(
                (bounds.minX + bounds.maxX) * 0.5,
                (bounds.minY + bounds.maxY) * 0.5,
                (bounds.minZ + bounds.maxZ) * 0.5
        );
    }


    public static double getFamilyMinimumY(
            final ServerSubLevel source
    ) {
        final AABB bounds = getFamilyBoundingBox(source);
        return bounds == null ? Double.NaN : bounds.minY;
    }


    public static double getFamilyMaximumY(
            final ServerSubLevel source
    ) {
        final AABB bounds = getFamilyBoundingBox(source);
        return bounds == null ? Double.NaN : bounds.maxY;
    }

    private static AABB toMinecraftAabb(
            final BoundingBox3dc bounds
    ) {
        return new AABB(
                bounds.minX(),
                bounds.minY(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxY(),
                bounds.maxZ()
        );
    }
}