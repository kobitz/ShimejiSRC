package com.group_finity.mascot.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The parsed shop catalog: a curated list of {@link ShopEntry}. Bad entries are skipped, not fatal. */
public final class ShopCatalog
{
    private static final int MAX_ENTRIES = 500;
    private final List<ShopEntry> entries;

    private ShopCatalog( final List<ShopEntry> entries ) { this.entries = Collections.unmodifiableList( entries ); }

    public List<ShopEntry> entries() { return entries; }

    /** @throws IllegalArgumentException if the text is not a catalog document at all. */
    public static ShopCatalog parse( final String json )
    {
        final Object root = MiniJson.parse( json );
        final Object list = root instanceof Map ? ( (Map<?, ?>) root ).get( "mascots" ) : null;
        if( !( list instanceof List ) ) throw new IllegalArgumentException( "catalog has no \"mascots\" list" );
        final List<ShopEntry> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for( final Object o : (List<?>) list )
        {
            if( out.size() >= MAX_ENTRIES ) break;
            final ShopEntry e = ShopEntry.from( o );
            if( e != null && seen.add( e.id.toLowerCase() ) ) out.add( e );
        }
        return new ShopCatalog( out );
    }
}
