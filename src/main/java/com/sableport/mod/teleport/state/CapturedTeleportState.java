package com.sableport.mod.teleport.state;

/**
 * Associates captured state with the adapter that created it.
 */
public record CapturedTeleportState<T>(
        SubLevelTeleportStateAdapter<T> adapter,
        T value
) {
}