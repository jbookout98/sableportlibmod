package com.sableport.mod.storage;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.nbt.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;



@EventBusSubscriber(modid = "sableportlibmod")
public class SubLevelHierarchyPerDimension {

    private static final Map<UUID, UUID> rootCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<UUID>> childrenCache = new ConcurrentHashMap<>(); // NEW!

    public static UUID getRootId(ServerSubLevel targetLevel, ServerSubLevelContainer container) {
        UUID targetId = targetLevel.getUniqueId();

        if (rootCache.containsKey(targetId)) {
            return rootCache.get(targetId);
        }

        UUID rootId = resolveLegacyRoot(targetLevel, container);
        rootCache.put(targetId, rootId);

        return rootId;
    }

    public static Set<UUID> getCachedChildren(ServerSubLevel targetLevel, ServerSubLevelContainer container) {
        UUID targetId = targetLevel.getUniqueId();

        if (!childrenCache.containsKey(targetId)) {
            childrenCache.put(targetId, collectDirectChildIds(targetLevel, container));
        }

        return childrenCache.get(targetId);
    }

    /**
     * Walks up the parent chain for sub-levels.
     * @param target
     * @param container
     * @return
     */
    private static UUID resolveLegacyRoot(final ServerSubLevel target, final ServerSubLevelContainer container) {
        ServerSubLevel current = target;
        boolean foundParent = true;

        final Set<UUID> climbingHistory = new HashSet<>();
        climbingHistory.add(current.getUniqueId());

        while (foundParent) {
            foundParent = false;
            for (final SubLevel potentialParentObj : container.getAllSubLevels()) {

                if (!(potentialParentObj instanceof ServerSubLevel potentialParent)) continue;
                if (potentialParent == current) continue;

                final Set<UUID> children = collectDirectChildIds(potentialParent, container);
                if (children.contains(current.getUniqueId())) {
                    if (climbingHistory.add(potentialParent.getUniqueId())) {
                        current = potentialParent;
                        foundParent = true;
                    }
                    break;
                }
            }
        }
        return current.getUniqueId();
    }

    //clean memory
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerSubLevel subLevel && !subLevel.getLevel().isClientSide()) {
            rootCache.remove(subLevel.getUniqueId());
            childrenCache.remove(subLevel.getUniqueId());
        }
    }

    public static Set<UUID> collectDirectChildIds(final ServerSubLevel subLevel, final ServerSubLevelContainer container) {
        final Set<UUID> ids = new HashSet<>();

        collectSubLevelIdsRecursive(subLevel.getPlot().save(), ids, container);

        if (subLevel.getUserDataTag() != null) {
            collectSubLevelIdsRecursive(subLevel.getUserDataTag(), ids, container);
        }

        return ids;
    }

    private static void collectSubLevelIdsRecursive(final CompoundTag tag, final Set<UUID> ids, final ServerSubLevelContainer container) {
        for (final String key : tag.getAllKeys()) {
            final Tag child = tag.get(key);
            if (child == null) continue;

            UUID foundId = null;

            if (child instanceof IntArrayTag iat && iat.size() == 4) {
                try { foundId = NbtUtils.loadUUID(iat); } catch (Exception ignored) {}
            } else if (child instanceof StringTag strTag) {
                try {
                    String str = strTag.getAsString();
                    if (str.length() == 36) foundId = UUID.fromString(str);
                } catch (Exception ignored) {}
            }

            if (foundId != null && container.getSubLevel(foundId) != null) {
                ids.add(foundId);
            }

            if (child instanceof CompoundTag ct) {
                collectSubLevelIdsRecursive(ct, ids, container);
            } else if (child instanceof ListTag list) {
                for (Tag value : list) {
                    if (value instanceof CompoundTag listCt) {
                        collectSubLevelIdsRecursive(listCt, ids, container);
                    }
                }
            }
        }
    }

}