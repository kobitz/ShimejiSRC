package com.group_finity.mascot.script;

import com.group_finity.mascot.Main;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.script.Bindings;

import com.group_finity.mascot.exception.VariableException;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */
public class VariableMap extends AbstractMap<String, Object> implements Bindings
{
    private final Map<String, Variable> rawMap = new LinkedHashMap<String, Variable>( );

    // -------------------------------------------------------------------------
    // Named entry class — replaces the anonymous Map.Entry allocated on every
    // iterator.next() call. A single instance is reused across next() calls,
    // which eliminates the per-variable-evaluation heap allocation that the old
    // anonymous class approach incurred every tick per mascot.
    // -------------------------------------------------------------------------
    private final class ReusableEntry implements Map.Entry<String, Object>
    {
        private Map.Entry<String, Variable> raw;

        void set( Map.Entry<String, Variable> raw )
        {
            this.raw = raw;
        }

        @Override
        public String getKey( )
        {
            return raw.getKey( );
        }

        @Override
        public Object getValue( )
        {
            try
            {
                return raw.getValue( ).get( VariableMap.this );
            }
            catch( final VariableException e )
            {
                throw new RuntimeException( e );
            }
        }

        @Override
        public Object setValue( final Object value )
        {
            throw new UnsupportedOperationException(
                Main.getInstance( ).getLanguageBundle( ).getString( "SetValueNotSupportedErrorMessage" ) );
        }
    }

    // -------------------------------------------------------------------------
    // Named iterator class — replaces the anonymous Iterator allocated on every
    // entrySet().iterator() call. Holds a single ReusableEntry that is updated
    // in place rather than allocating a new entry object per next() call.
    // -------------------------------------------------------------------------
    private final class EntryIterator implements Iterator<Map.Entry<String, Object>>
    {
        private Iterator<Map.Entry<String, Variable>> rawIterator;
        private final ReusableEntry entry = new ReusableEntry( );

        void reset( Iterator<Map.Entry<String, Variable>> rawIterator )
        {
            this.rawIterator = rawIterator;
        }

        @Override
        public boolean hasNext( )
        {
            return rawIterator.hasNext( );
        }

        @Override
        public Map.Entry<String, Object> next( )
        {
            entry.set( rawIterator.next( ) );
            return entry;
        }

        @Override
        public void remove( )
        {
            rawIterator.remove( );
        }
    }

    // -------------------------------------------------------------------------
    // Named entry set class — replaces the anonymous AbstractSet. One instance
    // per VariableMap, with a single EntryIterator that is reset on each
    // iterator() call rather than allocating a new iterator object each time.
    // -------------------------------------------------------------------------
    private final class EntrySet extends AbstractSet<Map.Entry<String, Object>>
    {
        private final EntryIterator iterator = new EntryIterator( );

        @Override
        public Iterator<Map.Entry<String, Object>> iterator( )
        {
            iterator.reset( VariableMap.this.getRawMap( ).entrySet( ).iterator( ) );
            return iterator;
        }

        @Override
        public int size( )
        {
            return VariableMap.this.getRawMap( ).size( );
        }
    }

    private final EntrySet entrySet = new EntrySet( );

    public Map<String, Variable> getRawMap( )
    {
        return this.rawMap;
    }

    public void init( )
    {
        for( final Variable o : this.getRawMap( ).values( ) )
            o.init( );
    }

    public void initFrame( )
    {
        for( final Variable o : this.getRawMap( ).values( ) )
            o.initFrame( );
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet( )
    {
        return this.entrySet;
    }

    @Override
    public Object put( final String key, final Object value )
    {
        Object result;

        if( value instanceof Variable )
            result = this.getRawMap( ).put( key, (Variable)value );
        else
            result = this.getRawMap( ).put( key, new Constant( value ) );

        return result;
    }
}
