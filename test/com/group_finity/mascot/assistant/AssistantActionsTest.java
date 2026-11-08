package com.group_finity.mascot.assistant;

import com.group_finity.mascot.behavior.Behavior;
import com.group_finity.mascot.config.Configuration;

import java.awt.Rectangle;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Dependency-free tests for {@link AssistantActions} using a fake {@link AssistantHost}.
 * Run: see CLAUDE.md -> "Tests". Exits non-zero on any failure.
 */
public final class AssistantActionsTest
{
    private static int failures = 0;

    /** Configuration with a fixed behavior list; nothing else is real. */
    private static final class FakeConfig extends Configuration
    {
        private final Set<String> names = new LinkedHashSet<>( );
        private final Set<String> hidden = new LinkedHashSet<>( );
        FakeConfig add( final String n ) { names.add( n ); return this; }
        FakeConfig hide( final String n ) { hidden.add( n ); return this; }
        @Override public Set<String> getBehaviorNames( ) { return names; }
        @Override public boolean hasBehavior( final String n ) { return names.contains( n ); }
        @Override public boolean isBehaviorHidden( final String n ) { return hidden.contains( n ); }
        @Override public Behavior buildBehavior( final String n ) { return null; }
    }

    private static final class FakeHost implements AssistantHost
    {
        final Configuration cfg;
        FakeHost( final Configuration cfg ) { this.cfg = cfg; }
        @Override public String getImageSet( ) { return "Fake"; }
        @Override public Rectangle getBounds( ) { return new Rectangle( 0, 0, 10, 10 ); }
        @Override public Configuration getConfiguration( ) { return cfg; }
        @Override public void setBehavior( final Behavior b ) { throw new AssertionError( "unexpected" ); }
        @Override public AssistantBubble timerBubble( ) { throw new AssertionError( "unexpected" ); }
    }

    private static void eq( final String what, final Object expected, final Object actual )
    {
        if( expected == null ? actual != null : !expected.equals( actual ) )
        {
            failures++;
            System.err.println( "FAIL " + what + ": expected <" + expected + "> got <" + actual + ">" );
        }
    }

    public static void main( final String[] args )
    {
        final FakeConfig cfg = new FakeConfig( ).add( "Dance" ).add( "SitDown" )
            .add( "Hidden" ).hide( "Hidden" ).add( "Walk/Sub" );
        final AssistantActions a = new AssistantActions( new FakeHost( cfg ) );

        // extractActionTag
        eq( "tag exact",          "Dance",   a.extractActionTag( "sure [ACTION:Dance] ok" ) );
        eq( "tag case-insens",    "Dance",   a.extractActionTag( "[ACTION:dance]" ) );
        eq( "tag unknown",        null,      a.extractActionTag( "[ACTION:Moonwalk]" ) );
        eq( "tag absent",         null,      a.extractActionTag( "no tag here" ) );
        eq( "tag trims",          "SitDown", a.extractActionTag( "[ACTION: SitDown ]" ) );
        eq( "tag null config",    null,      new AssistantActions( new FakeHost( null ) ).extractActionTag( "[ACTION:Dance]" ) );

        // findActionByKeyword
        eq( "kw exact word",      "Dance",   a.findActionByKeyword( "please dance", cfg ) );
        eq( "kw partial",         "SitDown", a.findActionByKeyword( "sit", cfg ) );
        eq( "kw fuzzy",           "Dance",   a.findActionByKeyword( "danse", cfg ) );
        eq( "kw hidden skipped",  null,      a.findActionByKeyword( "hidden", cfg ) );
        eq( "kw sub skipped",     null,      a.findActionByKeyword( "walk sub", cfg ) );
        eq( "kw no overlap",      null,      a.findActionByKeyword( "zzz", cfg ) );

        // tryRunBehavior: builder returns null -> user-facing failure message
        eq( "run unknown",        "I don't know how to do that.", a.tryRunBehavior( "Dance", cfg ) );

        if( failures > 0 ) { System.err.println( failures + " failure(s)" ); System.exit( 1 ); }
        System.out.println( "AssistantActionsTest: all passed" );
    }
}
