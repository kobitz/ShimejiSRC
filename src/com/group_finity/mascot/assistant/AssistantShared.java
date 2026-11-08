package com.group_finity.mascot.assistant;

import com.group_finity.mascot.Main;

/**
 * State and tiny helpers shared by every mascot's assistant code: the one Ollama client,
 * the one system-audio capture buffer (and its lock), the last transcript seen, the
 * default vision model name, the common reaction style rules. Extracted from
 * {@code Mascot} unchanged; these are process-wide singletons by design (cooldowns live
 * in {@link ReactionCooldowns}). Keep them single — do not copy per mascot.
 */
public final class AssistantShared
{
    private AssistantShared( ) {}

    public static com.group_finity.mascot.assistant.OllamaClient ollamaClient = null; // shared across all mascots

    /** Shared across all mascots — only one capture thread needed. */
    public static com.group_finity.mascot.assistant.AudioTranscriptBuffer audioBuffer = null;
    public static final Object AUDIO_LOCK = new Object();
    /** Last system audio transcript; used to skip near-duplicate reactions across fires. */
    public static volatile String lastAudioTranscriptText = "";

    /** Fallback when the VisionModel property is absent — matches the documented
     *  default (shared multimodal model), NOT a separate vision-only model. */
    public static final String DEFAULT_VISION_MODEL = "gemma4:e2b-it-qat";

    /**
     * Style rules shared by all unprompted reactions (peer, spontaneous, audio, vision).
     * Targets the failure modes every mascot converged on: grading the quality of
     * statements instead of reacting to their content, and hedging with appears/seems.
     */
    public static final String QUICK_REACTION_STYLE_RULES =
          "\n- REACT — do not report. Never merely describe what is on screen or what was said; the user already knows. Your sentence must carry YOUR take: an opinion, feeling, judgment, quip, or pointed question about ONE specific thing."
        + "\nWRONG: \"You are viewing a Nintendo Direct featuring Kingdom Hearts right now.\" (a caption, not a reaction)"
        + "\nWRONG: \"They mention Unreal Engine while the screen shows a Treehouse broadcast.\" (stitches context pieces together)"
        + "\nWRONG: \"Your focus appears misaligned with operational parameters.\" (grades the speaker instead of reacting)"
        + "\nRIGHT: pick the single most interesting detail and say what YOU think or feel about it, in your own voice."
        + "\n- Never join two facts with \"while\" or \"as\". One thing, one opinion."
        + "\n- NEVER comment on the quality of anyone's statement or attention (\"your assessment/analysis/focus [is/lacks/appears]...\")."
        + "\n- State things directly. Do not hedge with \"appears\", \"seems\", \"suggests\", \"indicates\", \"implies\", \"perhaps\", or \"remarkably\" — say what something IS, not what it hints at."
        + "\n- Memory entries and quoted mascot lines are background only — never mention them unless directly relevant to the thing you are reacting to, and never imitate their style.";

    public static String situationContext( )
    {
        if( !com.group_finity.mascot.assistant.SituationModel.isActive( ) ) return "";
        try
        {
            return com.group_finity.mascot.assistant.SituationModel.getInstance( ).current( ).asContextBlock( );
        }
        catch( Exception e ) { return ""; }
    }

    public static boolean isKnownImageSet( final String name )
    {
        if( name == null || name.isBlank() ) return false;
        try
        {
            for( final String set : Main.getInstance().getImageSets() )
                if( set.equalsIgnoreCase( name.trim() ) ) return true;
        }
        catch( final Exception ignored ) {}
        return false;
    }
}
