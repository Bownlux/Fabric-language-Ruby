package io.github.bownlux.language.ruby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RubyLanguageAdapterTest {
	public interface Greeter {
		String greetSomeone(String name);
	}

	public interface Named {
		String name();
	}

	private static final RubyLanguageAdapter ADAPTER = new RubyLanguageAdapter();
	private static ModContainer MOD;

	@BeforeAll
	static void setUp() throws Exception {
		Path root = Paths.get(RubyLanguageAdapterTest.class.getResource("/scripts/class_greeter.rb").toURI())
				.getParent().getParent();
		MOD = modContainer("test-mod", root);
	}

	@Test
	void classAsLastExpressionIsInstantiated() throws Exception {
		Greeter greeter = ADAPTER.create(MOD, "scripts/class_greeter.rb", Greeter.class);
		assertEquals("Hello, Alice, from a Ruby class!", greeter.greetSomeone("Alice"));
	}

	@Test
	void classFormCreatesANewInstancePerEntrypoint() throws Exception {
		Greeter first = ADAPTER.create(MOD, "scripts/class_greeter.rb", Greeter.class);
		Greeter second = ADAPTER.create(MOD, "scripts/class_greeter.rb", Greeter.class);
		assertNotSame(first, second);
	}

	@Test
	void instanceAsLastExpressionIsShared() throws Exception {
		Greeter first = ADAPTER.create(MOD, "scripts/instance_greeter.rb", Greeter.class);
		Greeter second = ADAPTER.create(MOD, "scripts/instance_greeter.rb", Greeter.class);
		// The @greeted counter carries across both proxies, proving they wrap
		// the same underlying Ruby object.
		assertEquals("Hello, Bob, from an instance! (call #1)", first.greetSomeone("Bob"));
		assertEquals("Hello, Ben, from an instance! (call #2)", second.greetSomeone("Ben"));
	}

	@Test
	void lambdaAsLastExpressionImplementsTheInterface() throws Exception {
		Greeter greeter = ADAPTER.create(MOD, "scripts/lambda_greeter.rb", Greeter.class);
		assertEquals("Hello, Carol, from a lambda!", greeter.greetSomeone("Carol"));
	}

	@Test
	void namespacedClassConstant() throws Exception {
		Greeter greeter = ADAPTER.create(MOD, "scripts/consts.rb::Example::Greeters::KlassGreeter", Greeter.class);
		assertEquals("Hello, Dave, from a namespaced class!", greeter.greetSomeone("Dave"));
	}

	@Test
	void moduleFunctionReference() throws Exception {
		Greeter greeter = ADAPTER.create(MOD, "scripts/consts.rb::Example::Greeters::ModuleGreeter#greet", Greeter.class);
		assertEquals("Hello, Erin, from a module function!", greeter.greetSomeone("Erin"));
	}

	@Test
	void instanceMethodReferenceInstantiatesTheClass() throws Exception {
		Greeter greeter = ADAPTER.create(MOD, "scripts/consts.rb::Example::Greeters::KlassGreeter#greet_someone", Greeter.class);
		assertEquals("Hello, Frank, from a namespaced class!", greeter.greetSomeone("Frank"));
	}

	@Test
	void scriptsAreEvaluatedOnlyOnce() throws Exception {
		Greeter first = ADAPTER.create(MOD, "scripts/counting_greeter.rb", Greeter.class);
		Greeter second = ADAPTER.create(MOD, "scripts/counting_greeter.rb", Greeter.class);
		assertEquals("Hello, Grace! This script was loaded 1 time(s).", first.greetSomeone("Grace"));
		assertEquals("Hello, Henry! This script was loaded 1 time(s).", second.greetSomeone("Henry"));
	}

	@Test
	void snakeCaseMethodsImplementCamelCaseInterfaceMethods() throws Exception {
		// Every test above already relies on this (greet_someone -> greetSomeone),
		// but make the intent explicit with a plain Runnable-style check too.
		Runnable runnable = ADAPTER.create(MOD, "scripts/runnable.rb", Runnable.class);
		runnable.run();
	}

	@Test
	void equivalentPathSpellingsShareOneEvaluation() throws Exception {
		Greeter first = ADAPTER.create(MOD, "scripts/counting_greeter2.rb", Greeter.class);
		Greeter second = ADAPTER.create(MOD, "./scripts//counting_greeter2.rb", Greeter.class);
		assertEquals("Hello, Iris! This script was loaded 1 time(s).", first.greetSomeone("Iris"));
		assertEquals("Hello, Ivan! This script was loaded 1 time(s).", second.greetSomeone("Ivan"));
	}

	@Test
	void instanceMethodShadowedByModuleBuiltinBindsTheInstanceMethod() throws Exception {
		Named named = ADAPTER.create(MOD, "scripts/consts.rb::Example::Greeters::BuiltinShadow#name", Named.class);
		assertEquals("from the instance method", named.name());
	}

	@Test
	void bareClassDefinitionWithoutTrailingConstantGivesDescriptiveError() {
		// `def` returns a Symbol, so a script that forgets its trailing constant
		// evaluates to a Symbol. The adapter must explain this instead of
		// leaking a TypeError.
		LanguageAdapterException e = assertThrows(LanguageAdapterException.class,
				() -> ADAPTER.create(MOD, "scripts/no_trailing_expression.rb", Greeter.class));
		assertTrue(e.getMessage().contains("Symbol"), e.getMessage());
	}

	@Test
	void nilConstantThrows() {
		LanguageAdapterException e = assertThrows(LanguageAdapterException.class,
				() -> ADAPTER.create(MOD, "scripts/consts.rb::Example::NIL_CONSTANT", Greeter.class));
		assertTrue(e.getMessage().contains("nil"), e.getMessage());
	}

	@Test
	void privateNewIsRespected() {
		LanguageAdapterException e = assertThrows(LanguageAdapterException.class,
				() -> ADAPTER.create(MOD, "scripts/private_new.rb", Greeter.class));
		assertTrue(e.getMessage().contains("instantiate"), e.getMessage());
	}

	@Test
	void missingScriptThrows() {
		LanguageAdapterException e = assertThrows(LanguageAdapterException.class,
				() -> ADAPTER.create(MOD, "scripts/does_not_exist.rb", Greeter.class));
		assertTrue(e.getMessage().contains("does_not_exist.rb"));
	}

	@Test
	void missingConstantThrows() {
		LanguageAdapterException e = assertThrows(LanguageAdapterException.class,
				() -> ADAPTER.create(MOD, "scripts/consts.rb::Example::Nope", Greeter.class));
		assertTrue(e.getMessage().contains("Example::Nope"));
	}

	@Test
	void missingMethodThrows() {
		LanguageAdapterException e = assertThrows(LanguageAdapterException.class,
				() -> ADAPTER.create(MOD, "scripts/consts.rb::Example::Greeters::ModuleGreeter#nope", Greeter.class));
		assertTrue(e.getMessage().contains("nope"));
	}

	@Test
	void valueWithoutRbSuffixThrows() {
		assertThrows(LanguageAdapterException.class,
				() -> ADAPTER.create(MOD, "just.a.JavaClass", Greeter.class));
	}

	@Test
	void scriptEndingInNilThrows() {
		LanguageAdapterException e = assertThrows(LanguageAdapterException.class,
				() -> ADAPTER.create(MOD, "scripts/nil_script.rb", Greeter.class));
		assertTrue(e.getMessage().contains("nil"));
	}

	@Test
	void incompatibleTargetThrows() {
		// A lambda can implement any interface, but a plain value cannot satisfy
		// a concrete class type.
		assertThrows(LanguageAdapterException.class,
				() -> ADAPTER.create(MOD, "scripts/consts.rb::Example::NOT_AN_ENTRYPOINT", Thread.class));
	}

	private static ModContainer modContainer(String id, Path root) {
		ClassLoader loader = RubyLanguageAdapterTest.class.getClassLoader();

		ModMetadata metadata = (ModMetadata) Proxy.newProxyInstance(loader, new Class<?>[] { ModMetadata.class },
				(proxy, method, args) -> switch (method.getName()) {
					case "getId" -> id;
					case "toString" -> id;
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
				});

		return (ModContainer) Proxy.newProxyInstance(loader, new Class<?>[] { ModContainer.class },
				(proxy, method, args) -> switch (method.getName()) {
					case "getMetadata" -> metadata;
					case "getRootPaths" -> List.of(root);
					case "toString" -> id;
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
				});
	}
}
