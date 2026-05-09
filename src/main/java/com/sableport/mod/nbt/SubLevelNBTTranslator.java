package com.sableport.mod.nbt;

import com.sableport.mod.teleport.SubLevelDimensionTeleport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;

public final class SubLevelNBTTranslator {

    private SubLevelNBTTranslator() {} // Prevent instantiation

    /**
     * Shifts block entity coordinate tags to match the new plot location.
     */
    public static void rewriteBlockEntityPositions(final CompoundTag plotTag, final long offsetX, final long offsetZ) {
        final CompoundTag chunks = plotTag.getCompound("chunks");

        for (final String key : chunks.getAllKeys()) {
            final ListTag bes = chunks.getCompound(key).getList("block_entities", 10);

            for (int i = 0; i < bes.size(); i++) {
                CompoundTag be = bes.getCompound(i);
                if (be.contains("x")) be.putInt("x", (int)(be.getInt("x") + offsetX));
                if (be.contains("z")) be.putInt("z", (int)(be.getInt("z") + offsetZ));
            }
        }
    }


    /**
     * Shifts vertical chunk section indices if the target dimension has a different build height.
     */
    public static void rewriteSectionIndices(final CompoundTag plotTag, final int shift) {
        if (shift == 0) return;
        final CompoundTag chunks = plotTag.getCompound("chunks");
        for (final String key : chunks.getAllKeys()) {

            final CompoundTag chunk = chunks.getCompound(key);
            final CompoundTag oldSec = chunk.getCompound("sections");
            final CompoundTag newSec = new CompoundTag();



            for (final String sKey : oldSec.getAllKeys()) {
                int oldIdx = Integer.parseInt(sKey);
                int newIdx = oldIdx + shift;
                if (newIdx >= 0) {
                    newSec.put(String.valueOf(newIdx), oldSec.getCompound(sKey));
                }
            }

            chunk.put("sections", newSec);
        }
    }

    /**
     *
     * @param plotTag
     * @param translations
     */
    public static void rewriteInternalBlockPosRefs(final CompoundTag plotTag, final Collection<SubLevelDimensionTeleport.PlotTranslation> translations) {
        final CompoundTag chunks = plotTag.getCompound("chunks");

        for (final String key : chunks.getAllKeys()) {
            final ListTag bes = chunks.getCompound(key).getList("block_entities", 10);
            for (int i = 0; i < bes.size(); i++) {
                rewriteInternalBlockPosRefsInTag(bes.getCompound(i), translations);
            }
        }

    }


    /**
     * recursive method to grab all data for block data
     * @param tag
     * @param translations
     */
    public static void rewriteInternalBlockPosRefsInTag(final CompoundTag tag, final Collection<SubLevelDimensionTeleport.PlotTranslation> translations) {

        for (final String key : new ArrayList<>(tag.getAllKeys())) {
            Tag child = tag.get(key);

            if (child instanceof IntArrayTag iat && iat.size() == 3) {
                int[] arr = iat.getAsIntArray();
                for (final SubLevelDimensionTeleport.PlotTranslation t : translations) {
                    if (arr[0] >= t.minX() && arr[0] <= t.maxX() && arr[2] >= t.minZ() && arr[2] <= t.maxZ()) {
                        tag.putIntArray(key, new int[]{arr[0] + (int)t.offsetX(), arr[1], arr[2] + (int)t.offsetZ()});
                        break;
                    }
                }
            } else if (child instanceof CompoundTag ct) {
                if (ct.contains("x") && ct.contains("z") && ct.contains("y")) {
                    int cx = ct.getInt("x");
                    int cz = ct.getInt("z");
                    for (final SubLevelDimensionTeleport.PlotTranslation t : translations) {
                        if (cx >= t.minX() && cx <= t.maxX() && cz >= t.minZ() && cz <= t.maxZ()) {
                            ct.putInt("x", cx + (int)t.offsetX());
                            ct.putInt("z", cz + (int)t.offsetZ());
                            break;
                        }
                    }
                }
                if (ct.contains("X") && ct.contains("Z") && ct.contains("Y")) {
                    int cx = ct.getInt("X");
                    int cz = ct.getInt("Z");
                    for (final SubLevelDimensionTeleport.PlotTranslation t : translations) {
                        if (cx >= t.minX() && cx <= t.maxX() && cz >= t.minZ() && cz <= t.maxZ()) {
                            ct.putInt("X", cx + (int)t.offsetX());
                            ct.putInt("Z", cz + (int)t.offsetZ());
                            break;
                        }
                    }
                }

                rewriteInternalBlockPosRefsInTag(ct, translations);
            } else if (child instanceof ListTag list) {

                for (Tag value : list) {
                    if (value instanceof CompoundTag listCt) {
                        rewriteInternalBlockPosRefsInTag(listCt, translations);
                    }
                }
            }
        }
    }

    /**
     * Specifically handles offset translations for Contraption entity anchor points.
     */
    public static void rewriteContraptionTagAnchorsUniversal(final CompoundTag contraption, final Collection<SubLevelDimensionTeleport.PlotTranslation> translations) {

        if (contraption.contains("Anchor")) {
            int[] a = contraption.getIntArray("Anchor");
            if (a.length == 3) {
                for (final SubLevelDimensionTeleport.PlotTranslation t : translations) {
                    if (a[0] >= t.minX() && a[0] <= t.maxX() && a[2] >= t.minZ() && a[2] <= t.maxZ()) {
                        contraption.putIntArray("Anchor", new int[]{a[0] + (int)t.offsetX(), a[1], a[2] + (int)t.offsetZ()});
                        break;
                    }
                }
            }
        }

        if (contraption.contains("SubContraptions", 9)) {
            final ListTag subs = contraption.getList("SubContraptions", 10);
            for (int i = 0; i < subs.size(); i++) {
                final CompoundTag sub = subs.getCompound(i);
                if (sub.contains("Contraption")) {
                    rewriteContraptionTagAnchorsUniversal(sub.getCompound("Contraption"), translations);
                } else {
                    rewriteContraptionTagAnchorsUniversal(sub, translations);
                }
            }
        }
    }
}