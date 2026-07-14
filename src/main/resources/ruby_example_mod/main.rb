# frozen_string_literal: true

# The main entrypoint. The last expression of this script (the class at the
# bottom) is instantiated by fabric-language-ruby, and on_initialize
# implements ModInitializer#onInitialize.

require 'java'

# The build vendors the dentaku gem (a calculator, used by /rubyexample calc)
# into vendor/ via the vendorGems Gradle task, and the generated setup.rb puts
# it on $LOAD_PATH. See gradle/gem-vendor.gradle.
require_relative 'vendor/setup'
require 'dentaku'
require 'bigdecimal'

class RubyExampleMod
  # Importing inside the class keeps these constants out of the shared
  # top-level namespace (all Ruby mods run in one JRuby interpreter).
  java_import 'net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents'
  java_import 'net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback'
  java_import 'net.minecraft.commands.Commands'
  java_import 'net.minecraft.network.chat.Component'
  java_import 'com.mojang.brigadier.arguments.StringArgumentType'

  # slf4j ships with Minecraft 1.18.2+; on older versions use Log4j or puts.
  LOGGER = org.slf4j.LoggerFactory.getLogger('ruby-example-mod')

  def on_initialize
    LOGGER.info("Ruby example mod loaded on JRuby #{JRUBY_VERSION} (Ruby #{RUBY_VERSION})")

    register_events
    register_commands
  end

  private

  def register_events
    on_started = lambda do |server|
      LOGGER.info('Ruby example mod says: the server is up!')
    end

    # Fabric API's Event#register(T) is generic, so the lambda has to be
    # converted to the listener interface explicitly.
    ServerLifecycleEvents::SERVER_STARTED.register(
      on_started.to_java(ServerLifecycleEvents::ServerStarted)
    )
  end

  def register_commands
    on_register = lambda do |dispatcher, registry_access, environment|
      # Blocks convert to Brigadier's Command interface automatically because
      # executes() is not a generic method.
      command = Commands.literal('rubyexample').executes do |context|
        message = -> { Component.literal("Hello from Ruby #{RUBY_VERSION}!") }
        context.source.send_success(message.to_java(java.util.function.Supplier), false)
        1 # Brigadier commands return an int
      end

      # /rubyexample calc <expression> evaluates math with the vendored
      # dentaku gem, for example: /rubyexample calc 2 + 3 * 4
      calc = Commands.literal('calc').then(
        Commands.argument('expression', StringArgumentType.greedy_string).executes do |context|
          expression = StringArgumentType.get_string(context, 'expression')
          result = Dentaku::Calculator.new.evaluate(expression)

          if result.nil?
            context.source.send_failure(Component.literal("Cannot evaluate: #{expression}"))
            0
          else
            # BigDecimal#to_s defaults to scientific notation; 'F' keeps 3.5 as 3.5.
            pretty = result.is_a?(BigDecimal) ? result.to_s('F') : result.to_s
            message = -> { Component.literal("#{expression} = #{pretty}") }
            context.source.send_success(message.to_java(java.util.function.Supplier), false)
            1
          end
        end
      )

      dispatcher.register(command.then(calc))
    end

    CommandRegistrationCallback::EVENT.register(
      on_register.to_java(CommandRegistrationCallback)
    )
    LOGGER.info('Registered the /rubyexample command')
  end
end

RubyExampleMod
