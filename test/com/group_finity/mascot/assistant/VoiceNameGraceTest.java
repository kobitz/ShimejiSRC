package com.group_finity.mascot.assistant;

public class VoiceNameGraceTest
{
    static int fails = 0;
    static void check( String label, boolean got, boolean want )
    {
        if( got != want ) { fails++; System.out.println( "FAIL " + label + " got " + got ); }
    }
    public static void main( String[] a )
    {
        check( "bare name", VoiceCommandListener.needsContinuation( "Hey Hornet.", "hornet" ), true );
        check( "bare name 2", VoiceCommandListener.needsContinuation( "hornet", "hornet" ), true );
        check( "dangling", VoiceCommandListener.needsContinuation( "Hey Hornet, can you", "hornet" ), true );
        check( "dangling 2", VoiceCommandListener.needsContinuation( "hornet could you please", "hornet" ), true );
        check( "one word command", VoiceCommandListener.needsContinuation( "hornet sit", "hornet" ), false );
        check( "full sentence", VoiceCommandListener.needsContinuation( "hey hornet what time is it", "hornet" ), false );
        check( "command before name", VoiceCommandListener.needsContinuation( "sit down hornet", "hornet" ), false );
        check( "greeting before only", VoiceCommandListener.needsContinuation( "hey hornet", "hornet" ), true );
        check( "null", VoiceCommandListener.needsContinuation( null, "hornet" ), false );
        if( fails > 0 ) System.exit( 1 );
        System.out.println( "VoiceNameGraceTest OK" );
    }
}
