package com.sableport.mod.teleport;

import com.sableport.mod.nbt.SubLevelNBTTranslator;
import com.sableport.mod.storage.SubLevelHierarchyPerDimension;
import com.sableport.mod.teleport.state.CapturedTeleportState;
import com.sableport.mod.teleport.state.SubLevelTeleportStateRegistry;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.*;


public final class SubLevelDimensionTeleport {

    private SubLevelDimensionTeleport() {}

    public record CapturedEntity(CompoundTag nbt, @Nullable Vec3 plotLocalPos, Vec3 worldVelocity,
                                 String typeName, String className, UUID sourceUuid, boolean isContraption,
                                 UUID familyMemberId) {}

    public record CapturedPlayer(ServerPlayer player, Vec3 sourceWorldPos, float yRot, float xRot) {}

    public record PlotTranslation(
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            long offsetX,
            long offsetZ
    ) {
        public boolean contains(final int x, final int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public BlockPos translate(final BlockPos source) {
            return source.offset(
                    Math.toIntExact(offsetX),
                    0,
                    Math.toIntExact(offsetZ)
            );
        }
    }

    /**
     *
     * @param source
     * @param targetLevel
     * @param targetPosition
     * @param targetOrientation
     * @return
     */
    public static @Nullable ServerSubLevel teleport(
            final ServerSubLevel source,
            final ServerLevel targetLevel,
            final Vector3dc targetPosition,
            final @Nullable Quaterniondc targetOrientation
    ) {
        final ServerLevel sourceLevel = source.getLevel();
        final ServerSubLevelContainer sourceContainer = SubLevelContainer.getContainer(sourceLevel);
        final ServerSubLevelContainer targetContainer = SubLevelContainer.getContainer(targetLevel);

        if (sourceContainer == null || targetContainer == null) {
            return null;
        }

        final long teleportStart = System.nanoTime();

        Sable.LOGGER.info("========== TELEPORT BEGIN ==========");
        Sable.LOGGER.info("Source: {}", source.getUniqueId());
        Sable.LOGGER.info("Source Dimension: {}", sourceLevel.dimension().location());
        Sable.LOGGER.info("Target Dimension: {}", targetLevel.dimension().location());

        UUID rootId = com.sableport.mod.storage.SubLevelHierarchyPerDimension.getRootId(source, sourceContainer);


        final ServerSubLevel rootSource = (ServerSubLevel) sourceContainer.getSubLevel(rootId);

        if (rootSource == null) {
            return null; // Failsafe it happened once
        }

        final UUID sourceUuid = rootSource.getUniqueId();

        if (sourceLevel == targetLevel) {
            return sameDimTeleport(rootSource, sourceLevel, targetPosition, targetOrientation);
        }
        final long familyStart = System.nanoTime();

        List<ServerSubLevel> family = collectFamilyTransitive(rootSource, sourceContainer);

        Sable.LOGGER.info(
                "Family discovery: {} members ({} ms)",
                family.size(),
                elapsedMs(familyStart)
        );


        final Map<UUID, CompoundTag> savedPlots = new HashMap<>();
        final Map<UUID, Pose3d> savedPoses = new HashMap<>();
        final Map<UUID, Vector3d> savedLinVel = new HashMap<>();
        final Map<UUID, Vector3d> savedAngVel = new HashMap<>();
        final Map<UUID, String> savedNames = new HashMap<>();
        final Map<UUID, CompoundTag> savedUserData = new HashMap<>();

        final long saveStart = System.nanoTime();
        long totalNBTBytes = 0;

        for (final ServerSubLevel member : family) {
            final CompoundTag savedPlot = member.getPlot().save();

            //Just in case NBT data gets changed to get rope data.
//            for (final String chunkKey
//                    : savedPlot.getCompound("chunks").getAllKeys()) {
//
//                final ListTag blockEntities =
//                        savedPlot.getCompound("chunks")
//                                .getCompound(chunkKey)
//                                .getList("block_entities", Tag.TAG_COMPOUND);
//
//                for (int i = 0; i < blockEntities.size(); i++) {
//                    final CompoundTag blockEntity =
//                            blockEntities.getCompound(i);
//
//                    if (blockEntity.contains("Strand", Tag.TAG_COMPOUND)) {
//                        Sable.LOGGER.info(
//                                "ROPE STRAND NBT: {}",
//                                blockEntity.getCompound("Strand")
//                        );
//                    }
//                }
//            }
            savedPlots.put(member.getUniqueId(), savedPlot);
            totalNBTBytes += savedPlot.sizeInBytes();

            savedPoses.put(member.getUniqueId(), new Pose3d(member.logicalPose()));

            final RigidBodyHandle h = RigidBodyHandle.of(member);

            final Vector3d lv = new Vector3d();
            final Vector3d av = new Vector3d();
            h.getLinearVelocity(lv);
            h.getAngularVelocity(av);
            savedLinVel.put(member.getUniqueId(), lv);
            savedAngVel.put(member.getUniqueId(), av);

            savedNames.put(member.getUniqueId(), member.getName());
            savedUserData.put(member.getUniqueId(), member.getUserDataTag());
        }

        Sable.LOGGER.info(
                "Saved {} plots ({}) in {} ms",
                family.size(),
                formatBytes(totalNBTBytes),
                elapsedMs(saveStart)
        );

        final List<CapturedTeleportState<?>> capturedExternalStates =
                SubLevelTeleportStateRegistry.captureAll(
                        sourceLevel,
                        family,
                        sourceContainer
                );

        final long captureStart = System.nanoTime();

        final List<CapturedPlayer> capturedPlayers =
                SubLevelEntityHandler.capturePlayersInBounds(sourceLevel, family);
        final List<CapturedEntity> capturedEntities =
                SubLevelEntityHandler.captureEntitiesInBoundsForFamily(
                        sourceLevel,
                        family,
                        sourceContainer
                );


        Sable.LOGGER.info(
                "Captured {} players and {} entities in {} ms",
                capturedPlayers.size(),
                capturedEntities.size(),
                elapsedMs(captureStart)
        );


        final List<Vector2i> targetPlots = new ArrayList<>();
        final Set<Long> claimedSlots = new HashSet<>();

        final long allocationSearch = System.nanoTime();

        for (int i = 0; i < family.size(); i++) {

            final Vector2i p = findFirstFreePlotExcluding(targetContainer, claimedSlots);

            if (p == null) {
                Sable.LOGGER.error("Not enough free plots for family of {}", family.size());
                return null;
            }

            final long key = ((long) p.x << 32) | (p.y & 0xFFFFFFFFL);
            claimedSlots.add(key);
            targetPlots.add(p);

        }
        Sable.LOGGER.info(
                "Found {} destination plots in {} ms",
                targetPlots.size(),
                elapsedMs(allocationSearch)
        );

        final long removalStart = System.nanoTime();

        Sable.LOGGER.info(
                "Removing {} source sublevels...",
                family.size()
        );

        for (final ServerSubLevel sub : family) {
            sourceContainer.removeSubLevel(
                    sub,
                    SubLevelRemovalReason.REMOVED
            );
        }

        Sable.LOGGER.info(
                "Removed {} source sublevels in {} ms",
                family.size(),
                elapsedMs(removalStart)
        );

        SubLevelHierarchyPerDimension.invalidateFamily(
                family.stream().map(ServerSubLevel::getUniqueId).toList()
        );


        final int logPlotSize = sourceContainer.getLogPlotSize();
        final int blockShift = logPlotSize + 4;
        final int plotSizeBlocks = 1 << blockShift; //bit operation basically 2^blockshift
        final int sectionShift = (sourceLevel.getMinBuildHeight() - targetLevel.getMinBuildHeight()) >> 4;
        //sectionShift where to put in y when teleport correctly.
        // minBuiltHeight - minBuiltHeight
        // overworld -64 - 0 -64 / 16
        //=-4. So move upwards 4 sections from wherever.
        final List<PlotTranslation> translations = new ArrayList<>();
        final long translationStart = System.nanoTime();

        //Whole loop is important later for math.
        //Basically when saving nbt data you need old positions to get it properly saved and properly reloaded.
        for (int i = 0; i < family.size(); i++) {

            final ServerLevelPlot plot = family.get(i).getPlot();
            final Vector2i targetPlotCoord = targetPlots.get(i);

            final long offsetX = ((long)(targetPlotCoord.x + targetContainer.getOrigin().x) - plot.plotPos.x) << blockShift;
            final long offsetZ = ((long)(targetPlotCoord.y + targetContainer.getOrigin().y) - plot.plotPos.z) << blockShift;
            final int minX = plot.plotPos.x << blockShift;
            final int maxX = minX + plotSizeBlocks - 1;
            final int minZ = plot.plotPos.z << blockShift;
            final int maxZ = minZ + plotSizeBlocks - 1;

            translations.add(new PlotTranslation(minX, maxX, minZ, maxZ, offsetX, offsetZ));
        }

        Sable.LOGGER.info(
                "Built {} translation entries in {} ms",
                translations.size(),
                elapsedMs(translationStart)
        );

        final Pose3d mainSourcePose = savedPoses.get(sourceUuid);

        ServerSubLevel destination = null;
        final Map<UUID, PlotTranslation> translationsByMember = new HashMap<>();
        final Map<UUID, Pose3d> targetPosesByMember = new HashMap<>();
        final List<ServerSubLevel> recreatedMembers = new ArrayList<>(family.size());
        final SubLevelPhysicsSystem targetPhysics = targetContainer.physicsSystem();

        final long recreationStart = System.nanoTime();

        Sable.LOGGER.info(
                "Recreating {} destination sublevels...",
                family.size()
        );

        for (int i = 0; i < family.size(); i++) {

            final ServerSubLevel member = family.get(i);
            final Vector2i targetPlotCoord = targetPlots.get(i);


            //Making sure we clear tracking at new plots we are spawning to.

            final UUID memberId = member.getUniqueId();
            final long memberStart = System.nanoTime();

            Sable.LOGGER.info(
                    "Recreating member {}/{} ({}) at target plot {},{}",
                    i + 1,
                    family.size(),
                    memberId,
                    targetPlotCoord.x,
                    targetPlotCoord.y
            );

            final PlotTranslation childTranslation = translations.get(i);

            final long allocationStart = System.nanoTime();

            final ServerSubLevel newMember =
                    (ServerSubLevel) targetContainer.allocateSubLevel(
                            memberId,
                            targetPlotCoord.x,
                            targetPlotCoord.y,
                            savedPoses.get(memberId)
                    );

            Sable.LOGGER.info(
                    "Allocated member {} in {} ms",
                    memberId,
                    elapsedMs(allocationStart)
            );

            if (newMember == null) {
                Sable.LOGGER.error("Failed to allocate family member {}", memberId);
                continue;
            }

            final String savedName =
                    savedNames.get(memberId);

            if (savedName != null) {
                newMember.setName(savedName);
            }

            final CompoundTag userData =
                    savedUserData.get(memberId);

            if (userData != null) {

                newMember.setUserDataTag(
                        userData.copy()
                );
            }

            if (i == 0){
                destination = newMember;
            }

            //Rewrite NBT data to properly follow the new dimension.
            //All coord data should rewritten here
            final CompoundTag childTag = savedPlots.get(memberId).copy();
            final long rewriteStart = System.nanoTime();

            if (sectionShift != 0) {
                SubLevelNBTTranslator.rewriteSectionIndices(childTag, sectionShift);
            }

            //SUBLEVEL SAVING NBT DATA
            SubLevelNBTTranslator.rewriteBlockEntityPositions(childTag, childTranslation.offsetX(), childTranslation.offsetZ());
            SubLevelNBTTranslator.rewriteScheduledTickPositions(childTag, childTranslation);
            SubLevelNBTTranslator.rewriteInternalBlockPosRefs(childTag, translations);
            SubLevelNBTTranslator.rewriteRopeStrandWorldPoints(
                    childTag,
                    mainSourcePose,
                    new Vector3d(targetPosition),
                    targetOrientation == null
                            ? null
                            : new Quaterniond(targetOrientation)
            );


            if (childTag.contains("Contraption")) {
                SubLevelNBTTranslator.rewriteContraptionTagAnchorsUniversal(
                        childTag.getCompound("Contraption"),
                        translations
                );
            }

            Sable.LOGGER.info(
                    "Rewrote NBT for {} in {} ms",
                    memberId,
                    elapsedMs(rewriteStart)
            );

            final CompoundTag meta = childTag.copy();

            meta.putInt("plot_x", targetPlotCoord.x);
            meta.putInt("plot_z", targetPlotCoord.y);

            final long loadStart = System.nanoTime();

            try {
                newMember.getPlot().load(meta);

                Sable.LOGGER.info(
                        "Loaded plot {} ({}) in {} ms",
                        memberId,
                        formatBytes(meta.sizeInBytes()),
                        elapsedMs(loadStart)
                );
            } catch (final Exception e) {
                Sable.LOGGER.error("plot.load failed for {}", memberId, e);
            }
            //get pose position and orientation and add to sublevles in new dimension
            final Pose3d sourcePose = savedPoses.get(memberId);
            final Vector3d relPos = new Vector3d(sourcePose.position()).sub(mainSourcePose.position());

            Quaterniond delta = null;
            if (targetOrientation != null) {
                delta = new Quaterniond(targetOrientation)
                        .mul(new Quaterniond(mainSourcePose.orientation()).invert());
            }

            final Quaterniondc childTargetOrient;
            if (delta != null) {
                delta.transform(relPos);
                childTargetOrient = new Quaterniond(delta).mul(new Quaterniond(sourcePose.orientation()));
            } else {
                childTargetOrient = sourcePose.orientation();
            }

            final Vector3d childTargetPos = new Vector3d(targetPosition).add(relPos);

            final long physicsStart = System.nanoTime();

            targetPhysics.getPipeline().resetVelocity(newMember);
            targetPhysics.getPipeline().teleport(newMember, childTargetPos, childTargetOrient);
            newMember.logicalPose().position().set(childTargetPos);
            newMember.logicalPose().orientation().set(childTargetOrient);
            newMember.updateLastPose();
            newMember.updateBoundingBox();

            //put new data into here
            translationsByMember.put(memberId, childTranslation);
            targetPosesByMember.put(memberId, new Pose3d(newMember.logicalPose()));


            //final Vector3d lv = savedLinVel.get(memberId);
            //final Vector3d av = savedAngVel.get(memberId);
            /*if (lv != null && av != null) {
                final Vector3d lvOut = new Vector3d(lv);
                final Vector3d avOut = new Vector3d(av);
                if (delta != null) {
                    delta.transform(lvOut);
                    delta.transform(avOut);
                }
                //targetPhysics.getPipeline().addLinearAndAngularVelocity(newMember, lvOut, avOut);
            }*/
            //Allow for physics transfer across dimensions
            //set names to newMember

            newMember.updateBoundingBox();

            // Keep every recreated member asleep until the complete family has
            // been loaded and moved into its final destination pose.
            recreatedMembers.add(newMember);

            Sable.LOGGER.info(
                    "Physics positioning for {} took {} ms",
                    memberId,
                    elapsedMs(physicsStart)
            );

            Sable.LOGGER.info(
                    "Finished member {} in {} ms",
                    memberId,
                    elapsedMs(memberStart)
            );
        }

        Sable.LOGGER.info(
                "Recreated all {} sublevels in {} ms",
                recreatedMembers.size(),
                elapsedMs(recreationStart)
        );

        SubLevelTeleportStateRegistry.restoreAll(
                targetLevel,
                capturedExternalStates,
                translationsByMember
        );

        final long familyWakeStart = System.nanoTime();

        for (final ServerSubLevel recreatedMember : recreatedMembers) {
            targetPhysics.getPipeline().wakeUp(recreatedMember);
        }

        Sable.LOGGER.info(
                "Woke {} recreated sublevels together in {} ms",
                recreatedMembers.size(),
                elapsedMs(familyWakeStart)
        );

        final long entityRespawnStart = System.nanoTime();

        SubLevelEntityHandler.respawnCapturedEntities(
                targetLevel,
                capturedEntities,
                translationsByMember,
                savedPoses,
                targetPosesByMember
        );

        Sable.LOGGER.info(
                "Respawned {} entities in {} ms",
                capturedEntities.size(),
                elapsedMs(entityRespawnStart)
        );

        if (destination == null) {
            Sable.LOGGER.error(
                    "Teleport failed: destination root was not allocated"
            );
            return null;
        }

        final long playerTeleportStart = System.nanoTime();

        SubLevelEntityHandler.teleportCapturedPlayers(
                capturedPlayers,
                targetLevel,
                mainSourcePose,
                destination.logicalPose()
        );

        Sable.LOGGER.info(
                "Teleported {} players in {} ms",
                capturedPlayers.size(),
                elapsedMs(playerTeleportStart)
        );

        logHeapUsage();

        Sable.LOGGER.info(
                "TOTAL TELEPORT TIME: {} ms",
                elapsedMs(teleportStart)
        );
        Sable.LOGGER.info("========== TELEPORT COMPLETE ==========");

        return destination;
    }


    /**
     * Gives me first free plot
     * @param container
     * @param claimed
     * @return
     */
    private static Vector2i findFirstFreePlotExcluding(ServerSubLevelContainer container, Set<Long> claimed) {
        final int sideLength = 1 << container.getLogSideLength();
        for (int x = 0; x < sideLength; x++) {
            for (int z = 0; z < sideLength; z++) {
                long key = ((long) x << 32) | (z & 0xFFFFFFFFL);

                if (claimed.contains(key)) {
                    continue;
                }
                if (!container.getOccupancy().get(container.getIndex(x, z))) {
                    return new Vector2i(x, z);
                }
            }
        }
        return null;
    }


    /**
     * Discovers children from root after getting root:
     * @param root
     * @param container
     * @return
     */
    private static List<ServerSubLevel> collectFamilyTransitive(final ServerSubLevel root, final ServerSubLevelContainer container) {
        final List<ServerSubLevel> family = new ArrayList<>();
        final Set<UUID> visited = new HashSet<>();
        final Deque<ServerSubLevel> stack = new ArrayDeque<>();

        stack.push(root);

        while (!stack.isEmpty()) {
            final ServerSubLevel current = stack.pop();
            if (!visited.add(current.getUniqueId())) continue;

            family.add(current);

            final Set<UUID> directChildren = SubLevelHierarchyPerDimension.collectDirectChildIds(current, container);
            for (final UUID childId : directChildren) {
                if (!visited.contains(childId)) {
                    final SubLevel maybe = container.getSubLevel(childId);
                    if (maybe instanceof ServerSubLevel childSubLevel) {
                        stack.push(childSubLevel);
                    }
                }
            }

        }
        return family;
    }


    /**
     * Fast same dimension teleportation if using this command
     * @param source
     * @param level
     * @param targetPosition
     * @param targetOrientation
     * @return
     */
    private static @Nullable ServerSubLevel sameDimTeleport(final ServerSubLevel source, final ServerLevel level,
                                                            final Vector3dc targetPosition, final @Nullable Quaterniondc targetOrientation) {
        final ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
        if (c == null){
            return null;
        }
        final Quaterniondc orient = targetOrientation != null ? targetOrientation : source.logicalPose().orientation();
        c.physicsSystem().getPipeline().resetVelocity(source);
        c.physicsSystem().getPipeline().teleport(source, targetPosition, orient);
        c.physicsSystem().getPipeline().wakeUp(source);


        logHeapUsage();

        return source;
    }

    private static double elapsedMs(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static String formatBytes(final long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(
                    Locale.ROOT,
                    "%.2f GiB",
                    bytes / (1024.0 * 1024.0 * 1024.0)
            );
        }

        if (bytes >= 1024L * 1024L) {
            return String.format(
                    Locale.ROOT,
                    "%.2f MiB",
                    bytes / (1024.0 * 1024.0)
            );
        }

        if (bytes >= 1024L) {
            return String.format(
                    Locale.ROOT,
                    "%.2f KiB",
                    bytes / 1024.0
            );
        }

        return bytes + " B";
    }

    private static void logHeapUsage() {
        final Runtime runtime = Runtime.getRuntime();
        final long usedBytes = runtime.totalMemory() - runtime.freeMemory();

        Sable.LOGGER.info(
                "Heap: {} used / {} allocated / {} max",
                formatBytes(usedBytes),
                formatBytes(runtime.totalMemory()),
                formatBytes(runtime.maxMemory())
        );
    }
}
