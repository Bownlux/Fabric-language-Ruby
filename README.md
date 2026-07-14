<div align="center">
  <img src="assets/logo.svg" alt="Fabric Language Ruby" width="480">

[![Build](https://github.com/Bownlux/Fabric-language-Ruby/actions/workflows/build.yml/badge.svg)](https://github.com/Bownlux/Fabric-language-Ruby/actions/workflows/build.yml)
</div>




## Made by Bownlux, A developer of RevivalSMP.net

Write Fabric mods in **Ruby**! This is a [Fabric](https://fabricmc.net/) language module, the Ruby
equivalent of [fabric-language-kotlin](https://github.com/FabricMC/fabric-language-kotlin). It is
powered by an embedded [JRuby](https://www.jruby.org/) runtime that ships inside the mod jar
together with the full Ruby standard library.

Your mod's `fabric.mod.json` points an entrypoint at a `.rb` script, and this mod does the rest:
it boots JRuby (lazily, only when a Ruby entrypoint is actually needed), evaluates your script, and
hands the resulting Ruby object to Fabric Loader as a regular entrypoint. Ruby method names map to
Java automatically, so `def on_initialize` implements `ModInitializer#onInitialize`.

```ruby
class MyRubyMod
  LOGGER = org.slf4j.LoggerFactory.getLogger('my-ruby-mod')

  def on_initialize
    LOGGER.info("Hello from Ruby #{RUBY_VERSION}!")
  end
end

MyRubyMod
```

> **Logging note:** Minecraft ships slf4j only on 1.18.2+. Targeting older versions? Use Log4j,
> which exists on every supported version:
> `LOGGER = org.apache.logging.log4j.LogManager.getLogger('my-ruby-mod')`, or just use plain `puts`.

## Supported versions

Every Minecraft version Fabric supports, back to the very first (1.14). Two files are published per
release. Install the one matching your Minecraft version:

| File | Minecraft | Java | Bundled runtime |
| --- | --- | --- | --- |
| `fabric-language-ruby-<v>+jruby.10.x.jar` | **1.20.5 and newer** (incl. 26.x) | 21+ | JRuby 10.0 (Ruby 3.4) |
| `fabric-language-ruby-<v>+jruby.9.4.x.jar` | **1.14-1.20.4** | 8+ | JRuby 9.4 (Ruby 3.1) |

Both are the same mod (`fabric-language-ruby`, same features, same adapter); they differ only in the
bundled JRuby, because JRuby 10 needs Java 21 while older Minecraft runs on Java 8-17. Modrinth
serves the right file for your game version automatically. Fabric Loader 0.16.0+ is required.

Fabric Language Ruby is fully compatible with
[fabric-language-kotlin](https://github.com/FabricMC/fabric-language-kotlin): both language modules
can be installed at the same time, so Ruby mods run alongside Kotlin mods in one modpack. This is
verified in testing by running both adapters plus a Kotlin-written mod (Ledger) on one server.

## For players

If a mod you installed needs this library, download the file matching your Minecraft version from
Modrinth (or the [GitHub releases](https://github.com/Bownlux/Fabric-language-Ruby/releases)) and drop
it into your `mods/` folder, next to the mod that needs it. There is no configuration.

## For mod developers

### Setting up

Add the `ruby` adapter to your entrypoints in `fabric.mod.json`, point the value at a Ruby script
inside your mod jar, and depend on `fabric-language-ruby`:

```json
{
	"schemaVersion": 1,
	"id": "my-ruby-mod",
	"version": "1.0.0",
	"entrypoints": {
		"main": [
			{
				"adapter": "ruby",
				"value": "my_ruby_mod/main.rb"
			}
		]
	},
	"depends": {
		"fabricloader": ">=0.16.0",
		"fabric-language-ruby": ">=1.0.0"
	}
}
```

`my_ruby_mod/main.rb` lives in your mod's **resources** (so it ends up at that path inside your
jar). Give the directory a unique, mod-specific name, because every mod shares one JVM classpath.

The **last expression of the script** is the entrypoint. In the example at the top it is the class
itself, which the adapter instantiates.

### Entrypoint value syntax

| `value` | Meaning |
| --- | --- |
| `"path/to/script.rb"` | Evaluate the script; its last expression is the entrypoint. A class is instantiated with `new`; a module, instance, or lambda is used as-is. |
| `"path/to/script.rb::Some::Constant"` | Evaluate the script, then resolve the (namespaced) constant. A class is instantiated; anything else is used as-is. |
| `"path/to/script.rb::Some::Constant#method_name"` | Resolve the constant and bind `method_name`. For a class, an instance method wins (a new instance is created for it); otherwise a class/module-level method (`module_function`, `def self.`) is bound. For any other constant the method is bound on the object itself. |

Examples of all three forms:

```ruby
# value: "my_mod/main.rb". The last expression is a lambda, which works for
# single-method entrypoints like ModInitializer.
-> { puts 'initialized!' }
```

```ruby
# value: "my_mod/main.rb::MyMod::Initializer"
module MyMod
  class Initializer
    def on_initialize
      # ...
    end
  end
end
```

```ruby
# value: "my_mod/main.rb::MyMod#init"
module MyMod
  module_function

  def init
    # ...
  end
end
```

Method-reference and lambda entrypoints route **every** method of the entrypoint interface to that
one method/lambda, so they suit single-method interfaces like `ModInitializer`; implement
multi-method entrypoint interfaces with a class or module instead.

### How Ruby objects become Java entrypoints

JRuby coerces your Ruby object into the requested entrypoint interface
(`ModInitializer`, `ClientModInitializer`, `DedicatedServerModInitializer`, custom interfaces, and so on).
Method names map automatically: a Java call to `onInitialize` dispatches to your Ruby
`on_initialize` (or a literal `onInitialize` if you define that instead).

A script is evaluated **at most once per mod**, no matter how many entrypoints reference it. If the
last expression is a *class*, each entrypoint reference gets its own instance; if it is an
*instance* (e.g. `MyMod.new`) or a module, that one object is shared.

### One shared JRuby runtime

All Ruby mods run in a **single shared JRuby interpreter**. Top-level constants, global variables,
monkey-patches, and `java_import`s are visible across every Ruby mod, and a later-loading mod can
reopen (or clobber) another mod's top-level classes. Keep everything inside one uniquely-named
module per mod (`module MyRubyMod ... end`), do `java_import` inside your own class/module rather
than at the top level, and avoid `$globals`.

### Multi-file mods and the standard library

`require_relative` works between the scripts in your jar, and the full Ruby standard library is
available with plain `require`:

```ruby
require 'json'
require_relative 'helper'
```

Installing gems at runtime is **not** supported (the stdlib lives inside a jar). Single-file
pure-Ruby libraries can be vendored into your resources and loaded with `require_relative`.
Multi-file gems use absolute `require`s internally, which resolve against `$LOAD_PATH`, so add
your vendor directory to it first, using the `uri:classloader:` scheme (all mod resources are on
the shared classpath):

```ruby
$LOAD_PATH.unshift 'uri:classloader:/my_ruby_mod/vendor'
require 'some_gem'   # loads my_ruby_mod/vendor/some_gem.rb and its internal requires
```

Gems with C extensions, and gems that read or write their own files on disk, will not work from
inside a jar.

### Calling Minecraft and Fabric API

Java interop is standard JRuby:

```ruby
class MyRubyMod
  java_import 'net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents'

  def on_initialize
    on_started = lambda do |server|
      puts "server started: #{server.class.java_class.simple_name}"
    end

    ServerLifecycleEvents::SERVER_STARTED.register(
      on_started.to_java(ServerLifecycleEvents::ServerStarted)
    )
  end
end

MyRubyMod
```

> **Important:** Fabric API's `Event#register(T)` is a *generic* method, so JRuby cannot infer the
> listener interface from a bare block. Convert explicitly with
> `lambda.to_java(TheListenerInterface)` as above. For *non-generic* Java methods that take a
> functional interface, passing a block directly works fine.

**A note on class names per Minecraft era:** on Minecraft 26.x and newer the game is unobfuscated,
so Ruby scripts can call Minecraft classes by their real (Mojang) names. On older versions
(1.14-1.21.x) Minecraft's own classes have *intermediary* names at runtime
(`net.minecraft.class_310` style). Entrypoint interfaces, Fabric API, Fabric Loader API
(including `FabricLoader.getInstance.getMappingResolver` to translate names), and all Java
libraries are unaffected and keep their normal names.

### Limitations

- **No mixins from Ruby.** Mixins need compile-time classes; a Ruby mod that requires them should
  ship a small Java/mixin core alongside its Ruby entrypoints (both can live in one jar).
- **No runtime gem installation**, as explained above.
- Scripts targeting the legacy file should stick to Ruby 3.1 features (the modern file runs
  Ruby 3.4).

## Example mod

A complete working example lives in [`src/testmod`](src/testmod): a mod written entirely in Ruby
that logs from its `main` entrypoint, uses `require`/`require_relative`, and registers a Fabric API
lifecycle callback. Run it against a dev server with:

```bash
./gradlew runTestmodServer
```

## Building from source

Requires JDK 25 (the built jars still run on Java 8/21+ as described above).

```bash
./gradlew build                    # modern variant  -> build/libs/*+jruby.10.*.jar
./gradlew build -Pvariant=legacy   # legacy variant  -> build/libs/*+jruby.9.4.*.jar
```

Each build runs the unit test suite against that variant's JRuby and also produces a standalone
example-mod jar (`*-testmod.jar`). JRuby is bundled jar-in-jar (`org.jruby:jruby-core` +
`org.jruby:jruby-stdlib`); Fabric Loader deduplicates nested jars by version if several installed
mods ship JRuby.


## Versioning

`<modVersion>+jruby.<bundledJRubyVersion>`, for example `1.0.0+jruby.10.0.6.0`. This mirrors
fabric-language-kotlin's scheme. Only the part before `+` participates in dependency resolution, so
`"fabric-language-ruby": ">=1.0.0"` matches both variant files.

## License

[MIT](LICENSE)
