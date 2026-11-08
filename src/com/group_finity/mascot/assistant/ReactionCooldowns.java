package com.group_finity.mascot.assistant;

/**
 * Global (cross-mascot) cooldowns for the four unprompted reaction types. When any mascot fires
 * a type, every other mascot skips that type until its cooldown elapses; the types are
 * independent of each other and peer reactions are excluded.
 *
 * <p>Static on purpose: the cooldown is shared by ALL mascots, so there must be exactly one
 * counter per type. Extracted from {@code Mascot} with no change in values or semantics.
 */
public final class ReactionCooldowns
{
    private ReactionCooldowns( ) { }

    // Global cross-mascot cooldowns — when any mascot fires a type, all others skip that
    // type until the cooldown elapses. Each type is independent; peer reactions excluded.
    public static final long GLOBAL_SPONTANEOUS_COOLDOWN_MS = 120_000L; // 2 min
    public static final long GLOBAL_AUDIO_COOLDOWN_MS       =  90_000L; // 90 s
    public static final long GLOBAL_VISION_COOLDOWN_MS      = 180_000L; // 3 min
    public static final long GLOBAL_USER_SPEECH_COOLDOWN_MS = 120_000L; // 2 min
    public static final java.util.concurrent.atomic.AtomicLong globalSpontaneousLastFiredMs =
        new java.util.concurrent.atomic.AtomicLong( 0L );
    public static final java.util.concurrent.atomic.AtomicLong globalAudioLastFiredMs =
        new java.util.concurrent.atomic.AtomicLong( 0L );
    public static final java.util.concurrent.atomic.AtomicLong globalVisionLastFiredMs =
        new java.util.concurrent.atomic.AtomicLong( 0L );
    public static final java.util.concurrent.atomic.AtomicLong globalUserSpeechLastFiredMs =
        new java.util.concurrent.atomic.AtomicLong( 0L );
}
