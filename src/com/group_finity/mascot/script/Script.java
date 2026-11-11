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

	private static final ThreadLocal<ScriptEngine> engine = ThreadLocal.withInitial( Script::newEngine );

	/**
	 * Globals Nashorn gives every script that bypass the class filter: load() reads local files and
	 * URLs and runs them as script, loadWithNewGlobal() does the same in a fresh global, and
	 * exit()/quit() terminate the whole JVM. Mascot XML has no use for any of them.
	 *
	 * Nashorn evaluates against a NEW global created per VariableMap (not the engine's default
	 * global), so deleting these once on the engine would not reach the scripts that actually run.
	 * They are therefore removed at the start of every compiled script. The prefix stays on the
	 * same line as the source (no line-number shift) and its value is void, so the script's own
	 * result is unaffected.
	 */
	private static final String SANDBOX_PREFIX =
		"void (delete this.load, delete this.loadWithNewGlobal, delete this.exit, delete this.quit);";

	private static String guarded(final String source) {
		return SANDBOX_PREFIX + source;
	}

	private static ScriptEngine newEngine() {
		final Main main = Main.getInstance();
		final String csv = main == null ? "" : main.getProperties().getProperty("ScriptAllowedClasses", "");
		// --optimistic-types=false: with optimistic types Nashorn compiles scripts assuming int
		// arithmetic and RE-COMPILES them on the tick thread the first time a value overflows or
		// changes type ("Script$Recompilation" frames in a 2.1 s stall). Pessimistic types never
		// recompile; the tiny per-eval cost is irrelevant for these one-line expressions.
		return new NashornScriptEngineFactory().getScriptEngine(
			new String[] { "--optimistic-types=false" },
			Script.class.getClassLoader(),
			new ScriptFilter(ScriptFilter.parseAllowed(csv)));
	}

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
		// Validate syntax by compiling on the current thread's engine. The result goes into the
		// shared per-thread cache so get() (and every later Script built from the same source,
		// e.g. each time a behavior's action tree is rebuilt) reuses it instead of recompiling:
		// the watchdog caught the tick thread inside Nashorn's compiler for 2.7 s rebuilding one
		// mascot's actions, because this constructor used to compile and throw the result away.
		try {
			final java.util.Map<String, CompiledScript> cache = compiledCache.get();
			if (!cache.containsKey(this.source)) {
				cache.put(this.source, ((Compilable) engine.get()).compile(guarded(this.source)));
			}
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
				compiled = ((Compilable) engine.get()).compile(guarded(this.source));
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
