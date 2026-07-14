# Ruby Example Mod

A template for writing Fabric mods entirely in **Ruby**, powered by
[fabric-language-ruby](https://github.com/Bownlux/Fabric-language-Ruby). There is no Java code in this
project: the mod is a handful of Ruby scripts plus a `fabric.mod.json`.

The example registers a `main` entrypoint that logs on startup, listens for the server-started
event, and adds a `/rubyexample` command, all from [`main.rb`](src/main/resources/ruby_example_mod/main.rb).
It also uses a real gem (dentaku, a calculator) that is vendored into the jar at build time, which
powers `/rubyexample calc`.

## Quick start

You need JDK 25 (or any JDK the targeted Minecraft version's toolchain accepts).

```bash
./gradlew runServer   # or runClient
```

Once the server is up, run `rubyexample` in the console (or `/rubyexample` in game). You should
see: `Hello from Ruby 3.4.5!` Then try the vendored gem:

```
/rubyexample calc 2 + 3 * 4
```

which answers `2 + 3 * 4 = 14`, courtesy of the dentaku gem living inside the mod jar.

```bash
./gradlew build       # produces build/libs/ruby-example-mod-1.0.0.jar
```

To install the built jar on a real server or client, put it in `mods/` together with
`fabric-language-ruby` (from [Modrinth](https://modrinth.com/mod/fabric-language-ruby) or the
[releases page](https://github.com/Bownlux/Fabric-language-Ruby/releases)) and Fabric API.

## Project layout

```
build.gradle                                  regular Fabric Loom build; no Java compilation happens
gradle/gem-vendor.gradle                      vendors pure-Ruby gems into the jar at build time
src/main/resources/fabric.mod.json            declares the Ruby entrypoint via the "ruby" adapter
src/main/resources/ruby_example_mod/main.rb   the mod itself
```

The entrypoint declaration is the interesting part of `fabric.mod.json`:

```json
"entrypoints": {
	"main": [
		{
			"adapter": "ruby",
			"value": "ruby_example_mod/main.rb"
		}
	]
}
```

The script's last expression (the `RubyExampleMod` class) becomes the entrypoint, and its
`on_initialize` method implements `ModInitializer#onInitialize`. See the
[fabric-language-ruby documentation](https://github.com/Bownlux/Fabric-language-Ruby#for-mod-developers)
for all supported entrypoint forms and the Java interop guide.

## Making it your own

1. In `fabric.mod.json`: change `id`, `name`, `description`, and `authors`.
2. Rename `src/main/resources/ruby_example_mod/` to match your mod id (use a unique directory
   name; every mod shares one classpath) and update the entrypoint `value`.
3. In `gradle.properties`: change `mod_version`, `maven_group`, and `archives_base_name`.
4. In `settings.gradle`: change `rootProject.name`.
5. Keep your Ruby code inside one uniquely named class or module. All Ruby mods share a single
   JRuby interpreter, so top-level constants are visible across mods.
6. Adjust the `gemVendor` block in `build.gradle`: change `vendorPath` to your renamed resource
   directory and list the gems you want, or delete the block (plus the
   `require_relative 'vendor/setup'` and gem requires in `main.rb`) if you need no gems. Only
   pure-Ruby gems work; transitive dependencies are vendored automatically. Vendoring
   redistributes the gems inside your jar, so check their licenses. See the
   [gem documentation](https://github.com/Bownlux/Fabric-language-Ruby#using-gems-build-time-vendoring)
   for details.

## Notes

- This template targets Minecraft 26.2 with the modern (JRuby 10) file of fabric-language-ruby.
  For Minecraft 1.14-1.20.4, lower `minecraft_version`, `loader_version`, and
  `fabric_api_version`, and set `fabric_language_ruby_version` to a `+jruby.9.4.x` version.
- Working against an unreleased fabric-language-ruby build? Drop its jar into `libs/` and swap
  the dependency line in `build.gradle` (there is a comment showing how).

## License

[MIT](LICENSE)
