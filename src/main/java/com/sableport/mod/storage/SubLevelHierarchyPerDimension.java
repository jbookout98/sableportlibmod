package com.sableport.mod.storage;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = "sableportlibmod")
public final class SubLevelHierarchyPerDimension {
    private static final Map<UUID, UUID> ROOT_CACHE =
            new ConcurrentHashMap<>();
    private static final Map<UUID, Set<UUID>> CHILDREN_CACHE =
            new ConcurrentHashMap<>();

    private SubLevelHierarchyPerDimension() {
    }

    public static UUID getRootId(
            final ServerSubLevel targetLevel,
            final ServerSubLevelContainer container
    ) {
        return ROOT_CACHE.computeIfAbsent(
                targetLevel.getUniqueId(),
                ignored -> resolveLegacyRoot(targetLevel, container)
        );
    }

    public static Set<UUID> getCachedChildren(
            final ServerSubLevel targetLevel,
            final ServerSubLevelContainer container
    ) {
        return CHILDREN_CACHE.computeIfAbsent(
                targetLevel.getUniqueId(),
                ignored -> Set.copyOf(
                        collectDirectChildIds(targetLevel, container)
                )
        );
    }

    public static void invalidateFamily(final Collection<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return;
        }

        for (final UUID memberId : memberIds) {
            ROOT_CACHE.remove(memberId);
            CHILDREN_CACHE.remove(memberId);
        }

        ROOT_CACHE.entrySet().removeIf(
                entry -> memberIds.contains(entry.getValue())
        );

        CHILDREN_CACHE.entrySet().removeIf(entry -> {
            for (final UUID childId : entry.getValue()) {
                if (memberIds.contains(childId)) {
                    return true;
                }
            }
            return false;
        });
    }

    private static UUID resolveLegacyRoot(
            final ServerSubLevel target,
            final ServerSubLevelContainer container
    ) {
        ServerSubLevel current = target;
        final Set<UUID> climbingHistory = new HashSet<>();
        climbingHistory.add(current.getUniqueId());

        boolean foundParent;
        do {
            foundParent = false;

            for (final SubLevel potentialParentObject
                    : container.getAllSubLevels()) {
                if (!(potentialParentObject
                        instanceof ServerSubLevel potentialParent)) {
                    continue;
                }

                if (potentialParent == current) {
                    continue;
                }

                final Set<UUID> children =
                        getCachedChildren(potentialParent, container);

                if (!children.contains(current.getUniqueId())) {
                    continue;
                }

                if (climbingHistory.add(potentialParent.getUniqueId())) {
                    current = potentialParent;
                    foundParent = true;
                }

                break;
            }
        } while (foundParent);

        return current.getUniqueId();
    }

    @SubscribeEvent
    public static void onLevelUnload(final LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerSubLevel subLevel
                && !subLevel.getLevel().isClientSide()) {
            invalidateFamily(Set.of(subLevel.getUniqueId()));
        }
    }

    public static Set<UUID> collectDirectChildIds(
            final ServerSubLevel subLevel,
            final ServerSubLevelContainer container
    ) {
        final Set<UUID> ids = new HashSet<>();
        collectSubLevelIdsRecursive(subLevel.getPlot().save(), ids, container);

        final CompoundTag userData = subLevel.getUserDataTag();
        if (userData != null) {
            collectSubLevelIdsRecursive(userData, ids, container);
        }

        ids.remove(subLevel.getUniqueId());
        return ids;
    }

    private static void collectSubLevelIdsRecursive(
            final CompoundTag tag,
            final Set<UUID> ids,
            final ServerSubLevelContainer container
    ) {
        for (final String key : tag.getAllKeys()) {
            final Tag child = tag.get(key);
            if (child == null) {
                continue;
            }

            UUID foundId = null;

            if (child instanceof IntArrayTag intArray
                    && intArray.size() == 4) {
                try {
                    foundId = NbtUtils.loadUUID(intArray);
                } catch (final RuntimeException ignored) {
                }
            } else if (child instanceof StringTag stringTag) {
                final String value = stringTag.getAsString();

                if (value.length() == 36) {
                    try {
                        foundId = UUID.fromString(value);
                    } catch (final IllegalArgumentException ignored) {
                    }
                }
            }

            if (foundId != null && container.getSubLevel(foundId) != null) {
                ids.add(foundId);
            }

            if (child instanceof CompoundTag compound) {
                collectSubLevelIdsRecursive(compound, ids, container);
            } else if (child instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    final Tag value = list.get(i);
                    if (value instanceof CompoundTag compoundValue) {
                        collectSubLevelIdsRecursive(
                                compoundValue,
                                ids,
                                container
                        );
                    }
                }
            }
        }
    }
}
