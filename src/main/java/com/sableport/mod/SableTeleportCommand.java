package com.sableport.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.sableport.mod.teleport.SubLevelDimensionTeleport;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ryanhcode.sable.api.command.SubLevelArgumentType;
import net.minecraft.world.entity.Entity;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

public final class SableTeleportCommand {

    private static final SimpleCommandExceptionType ERR_NO_CONTAINER =
            new SimpleCommandExceptionType(Component.literal("Source level has no Sable container"));
    private static final SimpleCommandExceptionType ERR_NO_SUBLEVEL =
            new SimpleCommandExceptionType(Component.literal("No sub-level with that UUID in this dimension"));
    private static final SimpleCommandExceptionType ERR_TELEPORT_FAILED =
            new SimpleCommandExceptionType(Component.literal("Teleport failed; check server log"));
    private static final SimpleCommandExceptionType ERR_BAD_UUID =
            new SimpleCommandExceptionType(Component.literal("Not a valid UUID"));

    private SableTeleportCommand() {}

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("sableport")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("teleport")
                                .then(Commands.argument("target", SubLevelArgumentType.subLevels())
                                        .then(Commands.argument("position", Vec3Argument.vec3())
                                                .executes(ctx -> executeSameDimension(
                                                        ctx.getSource(),
                                                        SubLevelArgumentType.getSubLevels(ctx, "target"),
                                                        Vec3Argument.getVec3(ctx, "position")
                                                ))
                                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                        .executes(ctx -> executeCrossDimension(
                                                                ctx.getSource(),
                                                                SubLevelArgumentType.getSubLevels(ctx, "target"),
                                                                DimensionArgument.getDimension(ctx, "dimension"),
                                                                Vec3Argument.getVec3(ctx, "position")
                                                        ))
                                                )
                                        )
                                )
                        )
        );
    }

    private static int executeSameDimension(
            CommandSourceStack source,
            java.util.Collection<ServerSubLevel> targets,
            Vec3 targetPos) {
        return executeTeleport(source, targets, source.getLevel(), targetPos);
    }

    private static int executeCrossDimension(
            CommandSourceStack source,
            java.util.Collection<ServerSubLevel> targets,
            ServerLevel targetLevel,
            Vec3 targetPos) {
        return executeTeleport(source, targets, targetLevel, targetPos);
    }

    private static int executeTeleport(
            CommandSourceStack source,
            java.util.Collection<ServerSubLevel> targets,
            ServerLevel targetLevel,
            Vec3 targetPos) {
        int successCount = (int) targets.stream().map(subLevel -> SubLevelDimensionTeleport.teleport(
                subLevel,
                targetLevel,
                new Vector3d(targetPos.x, targetPos.y, targetPos.z),
                null
        )).filter(Objects::nonNull).count();

        if (successCount == 0) {
            source.sendFailure(Component.literal("Teleport failed for all targets – check server log"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Successfully teleported " + successCount + " sub-level(s)"), true);

        return successCount;
    }
}