package com.tao.sandbox.ai.control;

/**
 * Whether the capability can be used, and by what.
 *
 * <p>Asked before the action is offered, so a dashboard disables a button it cannot honour and
 * says why, rather than presenting one that fails on click.
 *
 * @param generator which provider is active, or {@code none} when nothing is configured. Shown to
 *     the user rather than merely logged: what produced a payload is the one thing a reader cannot
 *     establish by looking at it.
 * @param reason why it cannot be used, or null when it can. Two unavailabilities need telling
 *     apart — nothing configured, and configured but unreachable — because only one of them is
 *     fixed by editing configuration, and a single "unavailable" sends people to the wrong one.
 */
public record AiStatus(boolean available, String generator, String model, String reason) {}
