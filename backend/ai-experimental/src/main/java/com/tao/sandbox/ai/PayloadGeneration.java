package com.tao.sandbox.ai;

import com.tao.sandbox.validate.Validation;

/**
 * A payload a model proposed, and what the sandbox found when it checked.
 *
 * <p>A proposal, never a mock: nothing is written by generating: the author reads it, edits it if
 * they want, and saves it through the same path as anything they typed. That keeps one way for a
 * payload to enter the library, and one place where saving means saving.
 *
 * @param validation the verdict from the same {@code MockValidator} the Validate button uses.
 *     Reported whatever it says — a payload that failed is still returned, because an author who
 *     can see the issues can fix them, and hiding a near miss to show nothing is worse. The
 *     dashboard must never present this as verified when {@code checked} is not {@code SCHEMA}.
 * @param attempts how many model calls it took, so a loop that is repairing every time is visible
 *     rather than merely slow
 * @param generator which provider answered — {@code fake} or a real one. Surfaced all the way to
 *     the user: offline placeholder data passing for model output would discredit the feature the
 *     first time somebody noticed.
 */
public record PayloadGeneration(
        String body, Validation validation, int attempts, String generator, String model) {}
