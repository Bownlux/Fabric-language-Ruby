# frozen_string_literal: true

# The main entrypoint. The last expression of this script (the class at the
# bottom) is instantiated by fabric-language-ruby, and on_initialize
# implements ModInitializer#onInitialize.

require 'java'

class RubyExampleMod
  # Importing inside the class keeps these constants out of the shared
  # top-level namespace (all Ruby mods run in one JRuby interpreter).
  java_import 'net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents'
  java_import 'net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback'
  java_import 'net.minecraft.commands.Commands'
  java_import 'net.minecraft.network.chat.Component'

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

      dispatcher.register(command)
    end

    CommandRegistrationCallback::EVENT.register(
      on_register.to_java(CommandRegistrationCallback)
    )
    LOGGER.info('Registered the /rubyexample command')
  end
end

RubyExampleMod
