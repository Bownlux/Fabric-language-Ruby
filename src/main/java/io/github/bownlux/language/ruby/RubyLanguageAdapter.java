package io.github.bownlux.language.ruby;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

import org.jruby.RubyClass;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.LocalVariableBehavior;
import org.jruby.embed.ScriptingContainer;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * Fabric language adapter that lets mods declare entrypoints written in Ruby,
 * executed by an embedded JRuby runtime.
 *
 * <p>Supported {@code value} notations:
 * <ul>
 *   <li>{@code "path/to/script.rb"}: the script is evaluated and its last
 *       expression becomes the entrypoint. A Ruby class is instantiated with
 *       {@code new}; anything else (module, instance, lambda) is used as-is.</li>
 *   <li>{@code "path/to/script.rb::Some::Constant"}: the script is evaluated,
 *       then the named constant is resolved. A class is instantiated; anything
 *       else is used as-is.</li>
 *   <li>{@code "path/to/script.rb::Some::Constant#method_name"}: the named
 *       method is bound and exposed as the entrypoint interface. For a class,
 *       an instance method wins (a new instance is created for it); otherwise a
 *       class/module-level method is bound. For any other constant the method
 *       is bound on the object itself.</li>
 * </ul>
 *
 * <p>Ruby objects are coerced to the requested entrypoint interface by JRuby,
 * which also maps Java-style names to Ruby conventions, so a Ruby
 * {@code on_initialize} implements {@code ModInitializer#onInitialize}.
 *
 * <p>Scripts are evaluated at most once per mod, no matter how many entrypoints
 * reference them. All Ruby mods share a single JRuby runtime, meaning top-level
 * constants and global variables are visible across mods, so mods should keep
 * their code inside uniquely-named modules. The runtime is only booted when the
 * first Ruby entrypoint is actually requested (Fabric Loader constructs this
 * adapter eagerly on every launch, so the constructor must stay free of side
 * effects).
 */
public final class RubyLanguageAdapter implements LanguageAdapter {
	private static final Object LOCK = new Object();
	private static final Object NIL = new Object();
	private static volatile ScriptingContainer container;
	/** Last-expression result of each evaluated script, keyed by "modid\0path". */
	private static final Map<String, Object> evaluatedScripts = new ConcurrentHashMap<>();

	@Override
	public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
		String scriptPath;
		String constantPath = null;
		String methodName = null;

		int constSep = value.indexOf(".rb::");
		if (constSep >= 0) {
			scriptPath = value.substring(0, constSep + 3);
			String rest = value.substring(constSep + 5);
			int methodSep = rest.indexOf('#');
			if (methodSep >= 0) {
				constantPath = rest.substring(0, methodSep);
				methodName = rest.substring(methodSep + 1);
			} else {
				constantPath = rest;
			}
			if (constantPath.isEmpty()) {
				throw new LanguageAdapterException("Missing constant name after \"::\" in Ruby entrypoint \"" + value + "\"");
			}
			if (methodName != null && methodName.isEmpty()) {
				throw new LanguageAdapterException("Missing method name after \"#\" in Ruby entrypoint \"" + value + "\"");
			}
		} else if (value.endsWith(".rb")) {
			scriptPath = value;
		} else {
			throw new LanguageAdapterException("Invalid Ruby entrypoint \"" + value + "\": expected \"path/to/script.rb\", "
					+ "\"path/to/script.rb::Some::Constant\" or \"path/to/script.rb::Some::Constant#method_name\"");
		}

		scriptPath = normalizePath(scriptPath);

		ScriptingContainer ruby = getContainer();
		Object receiver = evaluateScript(ruby, mod, scriptPath);

		Object target;
		if (constantPath == null) {
			if (receiver == NIL) {
				throw new LanguageAdapterException("Ruby script \"" + scriptPath + "\" in mod \"" + mod.getMetadata().getId()
						+ "\" evaluated to nil; its last expression must be a class, module, instance or lambda "
						+ "(or reference a constant with \"" + scriptPath + "::Some::Constant\")");
			}
			target = receiver;
		} else {
			try {
				// Request IRubyObject so the constant is not converted to a Java value:
				// a converted String/Integer/nil would no longer be a usable receiver.
				target = ruby.callMethod(ruby.getProvider().getRuntime().getObject(), "const_get",
						new Object[] { constantPath }, IRubyObject.class);
			} catch (RuntimeException | LinkageError e) {
				throw new LanguageAdapterException("Could not resolve Ruby constant \"" + constantPath + "\" from script \""
						+ scriptPath + "\" in mod \"" + mod.getMetadata().getId() + "\"", e);
			}
			if (target == null || (target instanceof IRubyObject && ((IRubyObject) target).isNil())) {
				throw new LanguageAdapterException("Ruby constant \"" + constantPath + "\" from script \"" + scriptPath
						+ "\" in mod \"" + mod.getMetadata().getId() + "\" is nil; it must be a class, module, instance or lambda");
			}
		}

		if (methodName != null) {
			target = bindMethod(ruby, target, methodName, value);
		} else if (target instanceof RubyClass) {
			target = instantiate(ruby, target, value);
		}

		return coerce(ruby, target, type, value);
	}

	private static Object bindMethod(ScriptingContainer ruby, Object target, String methodName, String value)
			throws LanguageAdapterException {
		Object owner = target;

		if (target instanceof RubyClass) {
			// Prefer the instance side: every class already responds to ~100
			// Module/Class built-ins (name, to_s, hash, ...) which would otherwise
			// shadow same-named instance methods defined by the mod author.
			if (definesInstanceMethod(ruby, target, methodName)) {
				owner = instantiate(ruby, target, value);
			} else if (!respondsTo(ruby, target, methodName)) {
				throw new LanguageAdapterException("Ruby entrypoint \"" + value + "\": the class defines neither a public "
						+ "instance method nor a class method \"" + methodName + "\"");
			}
		} else if (!respondsTo(ruby, owner, methodName)) {
			throw new LanguageAdapterException("Ruby entrypoint \"" + value + "\": " + describe(ruby, target)
					+ " does not respond to \"" + methodName + "\"");
		}

		try {
			Object method = ruby.callMethod(owner, "method", methodName);
			return ruby.callMethod(method, "to_proc");
		} catch (RuntimeException | LinkageError e) {
			throw new LanguageAdapterException("Failed to bind Ruby method \"" + methodName + "\" for entrypoint \"" + value + "\"", e);
		}
	}

	private static boolean definesInstanceMethod(ScriptingContainer ruby, Object rubyClass, String methodName) {
		Boolean result = ruby.callMethod(rubyClass, "method_defined?", new Object[] { methodName }, Boolean.class);
		return result != null && result;
	}

	private static boolean respondsTo(ScriptingContainer ruby, Object receiver, String methodName) {
		Boolean result = ruby.callMethod(receiver, "respond_to?", new Object[] { methodName }, Boolean.class);
		return result != null && result;
	}

	private static Object instantiate(ScriptingContainer ruby, Object rubyClass, String value) throws LanguageAdapterException {
		try {
			// public_send respects visibility: a class with a private new (singleton
			// pattern) fails loudly instead of being instantiated behind its back.
			return ruby.callMethod(rubyClass, "public_send", "new");
		} catch (RuntimeException | LinkageError e) {
			throw new LanguageAdapterException("Failed to instantiate Ruby class for entrypoint \"" + value
					+ "\" (is new public, and does initialize take zero arguments?)", e);
		}
	}

	private static <T> T coerce(ScriptingContainer ruby, Object target, Class<T> type, String value) throws LanguageAdapterException {
		if (type.isInstance(target)) {
			return type.cast(target);
		}

		if (target instanceof IRubyObject) {
			IRubyObject rubyObject = (IRubyObject) target;

			if (type.isInterface()) {
				try {
					T instance = ruby.getInstance(rubyObject, type);
					if (instance != null) {
						return instance;
					}
				} catch (RuntimeException | LinkageError ignored) {
					// e.g. TypeError for immediate values (Symbol, Integer, ...);
					// fall through to the descriptive error below
				}
			}

			try {
				Object converted = rubyObject.toJava(type);
				if (type.isInstance(converted)) {
					return type.cast(converted);
				}
			} catch (RuntimeException | LinkageError ignored) {
				// fall through to the error below
			}
		}

		throw new LanguageAdapterException("Ruby entrypoint \"" + value + "\" (" + describe(ruby, target)
				+ ") cannot be used as " + type.getName());
	}

	private static String describe(ScriptingContainer ruby, Object target) {
		if (target == null) {
			return "nil";
		}

		if (target instanceof IRubyObject) {
			String name = null;
			try {
				name = ruby.callMethod(((IRubyObject) target).getMetaClass().getRealClass(), "name", String.class);
			} catch (RuntimeException | LinkageError ignored) {
				// keep the fallback below
			}
			return "an instance of Ruby " + (name != null ? name : "(anonymous class)");
		}

		return "an instance of " + target.getClass().getName();
	}

	private static Object evaluateScript(ScriptingContainer ruby, ModContainer mod, String scriptPath)
			throws LanguageAdapterException {
		String modId = mod.getMetadata().getId();
		String key = modId + "\0" + scriptPath;

		Object result = evaluatedScripts.get(key);
		if (result != null) {
			return result;
		}

		synchronized (LOCK) {
			result = evaluatedScripts.get(key);
			if (result != null) {
				return result;
			}

			Path path = mod.findPath(scriptPath).orElseThrow(() -> new LanguageAdapterException(
					"Could not find Ruby script \"" + scriptPath + "\" inside mod \"" + modId + "\""));

			String source;
			try {
				source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new LanguageAdapterException("Failed to read Ruby script \"" + scriptPath + "\" from mod \"" + modId + "\"", e);
			}

			Object receiver;
			try {
				// The uri:classloader: pseudo-path makes __FILE__ and require_relative
				// resolve through the (shared) mod classpath.
				receiver = ruby.runScriptlet(new StringReader(source), "uri:classloader:/" + scriptPath);
			} catch (RuntimeException | LinkageError e) {
				throw new LanguageAdapterException("Failed to evaluate Ruby script \"" + scriptPath + "\" from mod \"" + modId + "\"", e);
			}

			result = receiver == null ? NIL : receiver;
			evaluatedScripts.put(key, result);
			return result;
		}
	}

	/** Collapses spellings like "./scripts//x.rb" so memoization and findPath agree. */
	private static String normalizePath(String path) {
		String result = path.replace('\\', '/').replaceAll("/{2,}", "/");
		while (result.startsWith("./")) {
			result = result.substring(2);
		}
		if (result.startsWith("/")) {
			result = result.substring(1);
		}
		return result;
	}

	private static ScriptingContainer getContainer() throws LanguageAdapterException {
		ScriptingContainer result = container;
		if (result == null) {
			synchronized (LOCK) {
				result = container;
				if (result == null) {
					long start = System.nanoTime();
					// SINGLETHREAD scope: one runtime and one variable map for this
					// container regardless of calling thread (unlike SINGLETON it is
					// not a process-wide global, unlike THREADSAFE it does not create
					// a runtime per thread).
					ScriptingContainer created = new ScriptingContainer(LocalContextScope.SINGLETHREAD, LocalVariableBehavior.TRANSIENT);
					// Knot's classloader, which sees every mod and the game. JRuby
					// locates its stdlib through it via uri:classloader:/META-INF/jruby.home.
					created.setClassLoader(RubyLanguageAdapter.class.getClassLoader());

					String version;
					try {
						version = (String) created.runScriptlet("JRUBY_VERSION + ' (Ruby ' + RUBY_VERSION + ')'");
					} catch (RuntimeException | LinkageError e) {
						throw new LanguageAdapterException("Failed to boot the embedded JRuby runtime", e);
					}

					container = result = created;

					try {
						org.slf4j.LoggerFactory.getLogger("fabric-language-ruby")
								.info("Booted JRuby {} in {} ms", version, (System.nanoTime() - start) / 1_000_000);
					} catch (RuntimeException | LinkageError e) {
						// Logging must never break entrypoint creation (e.g. slf4j missing in exotic setups).
					}
				}
			}
		}
		return result;
	}
}
