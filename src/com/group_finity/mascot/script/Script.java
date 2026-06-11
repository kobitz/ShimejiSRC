package com.group_finity.mascot.script;

import com.group_finity.mascot.Main;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import com.group_finity.mascot.exception.VariableException;

/**
 * Original Author: Yuki Yamada of Group Finity (http://www.group-finity.com/Shimeji/)
 * Currently developed by Shimeji-ee Group.
 */

public class Script extends Variable {

	private static final ThreadLocal<ScriptEngine> engine = ThreadLocal.withInitial(
		( ) -> new NashornScriptEngineFactory( ).getScriptEngine( new ScriptFilter( ) ) );

	/**
	 * Per-thread cache of compiled scripts, keyed by source text. CompiledScript
	 * is bound to the engine that compiled it, and engines are per-thread, so the
	 * cache must be per-thread too. Without this, get() recompiled the source on
	 * every evaluation — for #{...} expressions that means every frame of the
	 * 40ms tick loop. The script set comes from the XML configs, so the cache
	 * stays small and bounded.
	 */
	private static final ThreadLocal<java.util.Map<String, CompiledScript>> compiledCache =
		ThreadLocal.withInitial( java.util.HashMap::new );

	private final String source;
	
	private final boolean clearAtInitFrame;
	
	private volatile Object value;
	
	public Script(final String source, final boolean clearAtInitFrame) throws VariableException {
            
		this.source = source;
		this.clearAtInitFrame = clearAtInitFrame;
		// Validate syntax by compiling on the current thread's engine
		try {
			((Compilable) engine.get()).compile(this.source);
		} catch (final ScriptException e) {
			throw new VariableException( Main.getInstance( ).getLanguageBundle( ).getString( "ScriptCompilationErrorMessage" ) + ": "+this.source, e);
		}
	}

	@Override
	public String toString() {
		return this.isClearAtInitFrame() ? "#{"+this.getSource()+"}" : "${"+this.getSource()+"}";
	}
	
	@Override
	public void init() {
		setValue(null);
	}
	
	@Override
	public void initFrame() {
		if ( this.isClearAtInitFrame() ) {
			setValue(null);
		}
	}
	
	@Override
	public synchronized Object get(final VariableMap variables) throws VariableException {
			
		if ( getValue()!=null ) {
			return getValue();
		}

		try {
			final java.util.Map<String, CompiledScript> cache = compiledCache.get();
			CompiledScript compiled = cache.get(this.source);
			if (compiled == null) {
				compiled = ((Compilable) engine.get()).compile(this.source);
				cache.put(this.source, compiled);
			}
			setValue(compiled.eval(variables));
		} catch (final ScriptException e) {
			throw new VariableException( Main.getInstance( ).getLanguageBundle( ).getString( "ScriptEvaluationErrorMessage" ) + ": "+this.source, e);
		}

		return getValue();
	}

	private void setValue(final Object value) {
		this.value = value;
	}

	private Object getValue() {
		return this.value;
	}
	
	private boolean isClearAtInitFrame() {
		return this.clearAtInitFrame;
	}
	
	private String getSource() {
		return this.source;
	}
}
