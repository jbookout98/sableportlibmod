package com.sableport.mod.teleport;

import com.sableport.mod.nbt.SubLevelNBTTranslator;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.*;

public final class SubLevelEntityHandler {

    private SubLevelEntityHandler() {}


    /**
     * Serializes all non-player entities near any family member
     */
    public static List<SubLevelDimensionTeleport.CapturedEntity> captureEntitiesInBoundsForFamily(
            final ServerLevel sourceLevel, final List<ServerSubLevel> family, final ServerSubLevelContainer sourceContainer) {
        final List<SubLevelDimensionTeleport.CapturedEntity> captured = new ArrayList<>();
        final Set<UUID> seenEntityUuids = new HashSet<>();

        final int blockShift = sourceContainer.getLogPlotSize() + 1;
        final int plotSizeBlocks = 1 << blockShift;

        for (final ServerSubLevel member : family) {

            final BoundingBox3dc bounds = member.boundingBox();

            final AABB aabb = new AABB(bounds.minX()-1, bounds.minY()-1, bounds.minZ()-1,
                    bounds.maxX()+1, bounds.maxY()+1, bounds.maxZ()+1);

            final List<Entity> entities = sourceLevel.getEntities(
                    EntityTypeTest.forClass(Entity.class),
                    aabb,
                    e -> !(e instanceof ServerPlayer) && !e.isRemoved()
            );

            for (final Entity entity : entities) {
                if (!seenEntityUuids.add(entity.getUUID())) continue;

                boolean isFamilyMember = false;
                for (final ServerSubLevel m : family) {
                    if (m.getUniqueId().equals(entity.getUUID())) { isFamilyMember = true; break; }
                }
                if (isFamilyMember) continue;

                final CompoundTag nbt = new CompoundTag();
                if (!entity.saveAsPassenger(nbt)) continue;
                final boolean isContraption = nbt.contains("Contraption");

                final Vec3 plotLocal = (entity instanceof EntityStickExtension stick) ? stick.sable$getPlotPosition() : null;

                UUID correctOwnerId = member.getUniqueId();

                if (plotLocal != null) {
                    for (final ServerSubLevel m : family) {
                        final ServerLevelPlot p = m.getPlot();
                        final int minX = p.plotPos.x << blockShift;
                        final int maxX = minX + plotSizeBlocks - 1;
                        final int minZ = p.plotPos.z << blockShift;
                        final int maxZ = minZ + plotSizeBlocks - 1;
                        if (plotLocal.x >= minX && plotLocal.x <= maxX && plotLocal.z >= minZ && plotLocal.z <= maxZ) {
                            correctOwnerId = m.getUniqueId();
                            break;
                        }
                    }
                } else if (isContraption && nbt.contains("Contraption")) {
                    final CompoundTag contraption = nbt.getCompound("Contraption");
                    if (contraption.contains("Anchor")) {
                        int[] a = contraption.getIntArray("Anchor");
                        if (a.length == 3) {
                            for (final ServerSubLevel m : family) {
                                final ServerLevelPlot p = m.getPlot();
                                final int minX = p.plotPos.x << blockShift;
                                final int maxX = minX + plotSizeBlocks - 1;
                                final int minZ = p.plotPos.z << blockShift;
                                final int maxZ = minZ + plotSizeBlocks - 1;
                                if (a[0] >= minX && a[0] <= maxX && a[2] >= minZ && a[2] <= maxZ) {
                                    correctOwnerId = m.getUniqueId();
                                    break;
                                }
                            }
                        }
                    }
                }

                final ResourceLocation typeId = EntityType.getKey(entity.getType());
                captured.add(new SubLevelDimensionTeleport.CapturedEntity(
                        nbt, plotLocal, entity.getDeltaMovement(),
                        typeId.toString(),
                        entity.getClass().getSimpleName(),
                        entity.getUUID(),
                        isContraption,
                        correctOwnerId));

                // Force Minecraft to despawn the original so the new one renders correctly
                entity.remove(Entity.RemovalReason.CHANGED_DIMENSION);
            }
        }
        return captured;
    }
    /**
     * Finds all non-removed players whose position falls within any family sublevels bounding box.
     */
    public static List<SubLevelDimensionTeleport.CapturedPlayer> capturePlayersInBounds(final ServerLevel sourceLevel, final List<ServerSubLevel> family) {
        final List<SubLevelDimensionTeleport.CapturedPlayer> captured = new ArrayList<>();

        for (final ServerPlayer player : sourceLevel.players()) {
            if (player.isRemoved()) continue;

            // Check if the player is standing inside the bounding box of ANY ship in the family
            for (final ServerSubLevel member : family) {
                final BoundingBox3dc bounds = member.boundingBox();
                final AABB aabb = new AABB(bounds.minX()-1, bounds.minY()-1, bounds.minZ()-1,
                        bounds.maxX()+1, bounds.maxY()+1, bounds.maxZ()+1);

                if (aabb.contains(player.getX(), player.getY(), player.getZ())) {
                    captured.add(new SubLevelDimensionTeleport.CapturedPlayer(player, player.position(), player.getYRot(), player.getXRot()));
                    break;
                }
            }
        }
        return captured;
    }

    /**
     * Teleports captured players to the new dimension.
     */
    public static void teleportCapturedPlayers(final List<SubLevelDimensionTeleport.CapturedPlayer> captured, final ServerLevel targetLevel,
                                               final Pose3d sourcePose, final Pose3d targetPose) {
        for (final SubLevelDimensionTeleport.CapturedPlayer cp : captured) {
            if (cp.player().isRemoved()) continue;

            Vector3d relPos = new Vector3d(cp.sourceWorldPos().x, cp.sourceWorldPos().y, cp.sourceWorldPos().z)
                    .sub(sourcePose.position());

            Quaterniond delta = new Quaterniond();
            if (targetPose.orientation() != null && sourcePose.orientation() != null) {
                delta.set(targetPose.orientation()).mul(new Quaterniond(sourcePose.orientation()).invert());
            }

            delta.transform(relPos);
            double newX = targetPose.position().x() + relPos.x;
            double newY = targetPose.position().y() + relPos.y;
            double newZ = targetPose.position().z() + relPos.z;

            Vec3 oldVel = cp.player().getDeltaMovement();
            Vector3d velJoml = new Vector3d(oldVel.x, oldVel.y, oldVel.z);
            delta.transform(velJoml);

            cp.player().teleportTo(targetLevel, newX, newY, newZ, cp.yRot(), cp.xRot());

            cp.player().hurtMarked = true;
        }
    }



    /**
     * Respawns captured non-player entities in the new dimension.
     */
    public static void respawnCapturedEntities(
            final ServerLevel targetLevel,
            final List<SubLevelDimensionTeleport.CapturedEntity> captured,
            final Map<UUID, SubLevelDimensionTeleport.PlotTranslation> translationsByMember,
            final Map<UUID, Pose3d> sourcePosesByMember,
            final Map<UUID, Pose3d> targetPosesByMember
    ) {
        final Collection<SubLevelDimensionTeleport.PlotTranslation> allTranslations = translationsByMember.values();

        for (final SubLevelDimensionTeleport.CapturedEntity ce : captured) {

            final Pose3d sourcePose = sourcePosesByMember.get(ce.familyMemberId());
            final Pose3d targetPose = targetPosesByMember.get(ce.familyMemberId());

            if (sourcePose == null || targetPose == null) {
                continue;
            }

            final ListTag posList = ce.nbt().getList("Pos", Tag.TAG_DOUBLE);
            if (posList.size() == 3) {
                double x = posList.getDouble(0);
                double y = posList.getDouble(1);
                double z = posList.getDouble(2);

                boolean inPlot = false;
                for (final SubLevelDimensionTeleport.PlotTranslation t : allTranslations) {
                    if (x >= t.minX() && x <= t.maxX() && z >= t.minZ() && z <= t.maxZ()) {
                        x += t.offsetX();
                        z += t.offsetZ();
                        inPlot = true;
                        break;
                    }
                }

                if (!inPlot) {
                    final Vector3d relPos = new Vector3d(x, y, z).sub(sourcePose.position());

                    if (targetPose.orientation() != null && sourcePose.orientation() != null && !targetPose.orientation().equals(sourcePose.orientation())) {
                        final Quaterniond delta = new Quaterniond(targetPose.orientation())
                                .mul(new Quaterniond(sourcePose.orientation()).invert());
                        delta.transform(relPos);
                    }

                    x = targetPose.position().x() + relPos.x;
                    y = targetPose.position().y() + relPos.y;
                    z = targetPose.position().z() + relPos.z;
                }

                final ListTag newPos = new ListTag();
                newPos.add(DoubleTag.valueOf(x));
                newPos.add(DoubleTag.valueOf(y));
                newPos.add(DoubleTag.valueOf(z));
                ce.nbt().put("Pos", newPos);
            }

            if (ce.nbt().contains("TileX") && ce.nbt().contains("TileZ")) {
                int tx = ce.nbt().getInt("TileX");
                int tz = ce.nbt().getInt("TileZ");
                for (final SubLevelDimensionTeleport.PlotTranslation t : allTranslations) {
                    if (tx >= t.minX() && tx <= t.maxX() && tz >= t.minZ() && tz <= t.maxZ()) {
                        ce.nbt().putInt("TileX", tx + (int)t.offsetX());
                        ce.nbt().putInt("TileZ", tz + (int)t.offsetZ());
                        break;
                    }
                }
            }

            if (ce.isContraption() && ce.nbt().contains("Contraption")) {
                SubLevelNBTTranslator.rewriteContraptionTagAnchorsUniversal(ce.nbt().getCompound("Contraption"), allTranslations);
            }

            SubLevelNBTTranslator.rewriteInternalBlockPosRefsInTag(ce.nbt(), allTranslations);

            final Entity entity = EntityType.loadEntityRecursive(ce.nbt(), targetLevel, e -> e);
            if (entity != null) {

                Vec3 vel = ce.worldVelocity();

                if (vel != null) {

                    Vector3d velVec = new Vector3d(vel.x, vel.y, vel.z);

                    /*if (targetPose.orientation() != null && sourcePose.orientation() != null && !targetPose.orientation().equals(sourcePose.orientation())) {

                        final Quaterniond delta = new Quaterniond(targetPose.orientation())
                                .mul(new Quaterniond(sourcePose.orientation()).invert());
                        delta.transform(velVec);

                    }*/
                    //Don't need this because we aren't passing orientation to anything important
                    entity.setDeltaMovement(velVec.x, velVec.y, velVec.z);
                }

                targetLevel.addFreshEntity(entity);

                if (ce.plotLocalPos() != null && entity instanceof EntityStickExtension stick) {
                    Vec3 shiftedPlotLocal = ce.plotLocalPos();
                    for (final SubLevelDimensionTeleport.PlotTranslation t : allTranslations) {
                        if (shiftedPlotLocal.x >= t.minX() && shiftedPlotLocal.x <= t.maxX() && shiftedPlotLocal.z >= t.minZ() && shiftedPlotLocal.z <= t.maxZ()) {
                            shiftedPlotLocal = shiftedPlotLocal.add(t.offsetX(), 0, t.offsetZ());
                            break;
                        }
                    }
                    stick.sable$setPlotPosition(shiftedPlotLocal);
                }
            }
        }
    }


}