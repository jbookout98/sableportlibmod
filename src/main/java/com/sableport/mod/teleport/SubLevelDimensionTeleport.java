
package com.sableport.mod.teleport;

import com.sableport.mod.nbt.SubLevelNBTTranslator;
import com.sableport.mod.storage.SubLevelHierarchyPerDimension;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelOccupancySavedData;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
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

    public record PlotTranslation(int minX, int maxX, int minZ, int maxZ, long offsetX, long offsetZ) {}

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

        if (targetContainer == null) {
            return null;
        }


        UUID rootId = com.sableport.mod.storage.SubLevelHierarchyPerDimension.getRootId(source, sourceContainer);



        final ServerSubLevel rootSource = (ServerSubLevel) sourceContainer.getSubLevel(rootId);

        if (rootSource == null) {
            return null; // Failsafe it happened once
        }

        final UUID sourceUuid = rootSource.getUniqueId();

        if (sourceLevel == targetLevel) {
            return sameDimTeleport(rootSource, sourceLevel, targetPosition, targetOrientation);
        }

        List<ServerSubLevel> family = collectFamilyTransitive(rootSource, sourceContainer);




        final Map<UUID, CompoundTag> savedPlots = new HashMap<>();
        final Map<UUID, Pose3d> savedPoses = new HashMap<>();
        final Map<UUID, Vector3d> savedLinVel = new HashMap<>();
        final Map<UUID, Vector3d> savedAngVel = new HashMap<>();
        final Map<UUID, String> savedNames = new HashMap<>();
        final Map<UUID, CompoundTag> savedUserData = new HashMap<>();

        for (final ServerSubLevel member : family) {
            savedPlots.put(member.getUniqueId(), member.getPlot().save());
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

        final List<CapturedPlayer> capturedPlayers = SubLevelEntityHandler.capturePlayersInBounds(sourceLevel, family);
        final List<CapturedEntity> capturedEntities = SubLevelEntityHandler.captureEntitiesInBoundsForFamily(sourceLevel, family, sourceContainer);



        final List<Vector2i> targetPlots = new ArrayList<>();
        final Set<Long> claimedSlots = new HashSet<>();

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

        for (final ServerSubLevel sub : family) {

            final ServerLevelPlot p = sub.getPlot();
            final Vector2i origin = sourceContainer.getOrigin();
            final int localX = p.plotPos.x - origin.x;
            final int localZ = p.plotPos.z - origin.y;

            sourceContainer.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);

            SubLevelOccupancySavedData.getOrLoad(sourceLevel).setDirty();

            try {
                sourceContainer.getHoldingChunkMap().queueDeletion(sub);
            } catch (final Exception e) {
                Sable.LOGGER.error("Failed to queue deletion for {}", sub.getUniqueId(), e);
            }
        }


        final int logPlotSize = sourceContainer.getLogPlotSize();
        final int blockShift = logPlotSize + 4;
        final int plotSizeBlocks = 1 << blockShift; //bit operation basically 2^blockshift
        final int sectionShift = (sourceLevel.getMinBuildHeight() - targetLevel.getMinBuildHeight()) >> 4;
        //sectionShift where to put in y when teleport correctly.
        // minBuiltHeight - minBuiltHeight
        // overworld -64 - 0 -64 / 16
        //=-4. So move upwards 4 sections from wherever.
        final List<PlotTranslation> translations = new ArrayList<>();

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

        final Pose3d mainSourcePose = savedPoses.get(sourceUuid);

        ServerSubLevel destination = null;
        final Map<UUID, PlotTranslation> translationsByMember = new HashMap<>();
        final Map<UUID, Pose3d> targetPosesByMember = new HashMap<>();


        for (int i = 0; i < family.size(); i++) {

            final ServerSubLevel member = family.get(i);
            final Vector2i targetPlotCoord = targetPlots.get(i);
            final long plotKey = ChunkPos.asLong(targetPlotCoord.x, targetPlotCoord.y);

            final Set<ServerPlayer> recipients = new HashSet<>(targetLevel.players());

            for (final CapturedPlayer cp : capturedPlayers) {
                recipients.add(cp.player());
            }
            for (final ServerPlayer player : recipients) {
                player.connection.send(
                        new ClientboundCustomPayloadPacket(
                                new ClientboundStopTrackingSubLevelPacket(plotKey)
                        )
                );
            }
            //Making sure we clear tracking at new plots we are spawning to.

            final UUID memberId = member.getUniqueId();
            final PlotTranslation childTranslation = translations.get(i);

            final ServerSubLevel newMember = (ServerSubLevel) targetContainer.allocateSubLevel(
                    memberId, targetPlotCoord.x, targetPlotCoord.y, savedPoses.get(memberId));

            if (newMember == null) {
                Sable.LOGGER.error("Failed to allocate family member {}", memberId);
                continue;
            }

            if (i == 0){
                destination = newMember;
            }

            //Rewrite NBT data to properly follow the new dimension.
            //All coord data should rewritten here
            final CompoundTag childTag = savedPlots.get(memberId).copy();

            if (sectionShift != 0) {
                SubLevelNBTTranslator.rewriteSectionIndices(childTag, sectionShift);
            }

            SubLevelNBTTranslator.rewriteBlockEntityPositions(childTag, childTranslation.offsetX(), childTranslation.offsetZ());
            SubLevelNBTTranslator.rewriteInternalBlockPosRefs(childTag, translations);

            if (childTag.contains("Contraption")) {
                SubLevelNBTTranslator.rewriteContraptionTagAnchorsUniversal(childTag.getCompound("Contraption"), translations);
            }

            final CompoundTag meta = childTag.copy();

            meta.putInt("plot_x", targetPlotCoord.x);
            meta.putInt("plot_z", targetPlotCoord.y);

            try {
                newMember.getPlot().load(meta);
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

            final SubLevelPhysicsSystem targetPhysics = targetContainer.physicsSystem();
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
            if (savedNames.get(memberId) != null){
                newMember.setName(savedNames.get(memberId));
            }
            if (savedUserData.get(memberId) != null) {
                newMember.setUserDataTag(savedUserData.get(memberId));
            }
            newMember.updateBoundingBox();

            //add newMember to physics and tracking System
            targetContainer.trackingSystem().onSubLevelAdded(newMember);
            targetPhysics.getPipeline().wakeUp(newMember);


        }




        SubLevelEntityHandler.respawnCapturedEntities(targetLevel, capturedEntities, translationsByMember, savedPoses, targetPosesByMember);
        //respawn entities^
        //remove oldMember tracking
        for (final ServerSubLevel oldMember : family) {
            final ChunkPos plotPos = oldMember.getPlot().plotPos;
            final Vector2i origin = sourceContainer.getOrigin();
            final long l = ChunkPos.asLong(plotPos.x - origin.x, plotPos.z - origin.y);

            for (final UUID trackingUuid : oldMember.getTrackingPlayers()) {
                final ServerPlayer trackingPlayer = sourceLevel.getServer().getPlayerList().getPlayer(trackingUuid);
                if (trackingPlayer != null) {
                    trackingPlayer.connection.send(
                            new ClientboundCustomPayloadPacket(
                                    new ClientboundStopTrackingSubLevelPacket(l)
                            )
                    );
                }
            }
            oldMember.getTrackingPlayers().clear();
        }


        SubLevelEntityHandler.teleportCapturedPlayers(capturedPlayers, targetLevel, mainSourcePose, destination.logicalPose());
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
        return source;
    }
}
