# frozen_string_literal: true

# The main entrypoint of the example mod. The last expression of this script
# (the RubyExampleMod class at the bottom) is handed to Fabric Loader, which
# instantiates it; on_initialize implements ModInitializer#onInitialize.

require 'java'
require_relative 'helper'

class RubyExampleMod
  # Importing inside the class scopes the constant to it. All Ruby mods share
  # one runtime, so keep your constants namespaced.
  java_import 'net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents'

  # slf4j ships with Minecraft 1.18.2+; on older versions use Log4j or puts.
  LOGGER = org.slf4j.LoggerFactory.getLogger('ruby-example-mod')

  def on_initialize
    LOGGER.info("Hello from Ruby! Running JRuby #{JRUBY_VERSION} (Ruby #{RUBY_VERSION}) inside Minecraft.")
    LOGGER.info("Ruby stdlib check: #{ExampleHelper.motd}")

    # Fabric API's Event#register(T) is generic, which means JRuby cannot
    # infer the listener interface from a bare block. Convert the lambda
    # explicitly.
    on_started = lambda do |server|
      LOGGER.info("Ruby callback: the server (a #{server.class.java_class.simple_name}) has started!")
    end

    ServerLifecycleEvents::SERVER_STARTED.register(
      on_started.to_java(ServerLifecycleEvents::ServerStarted)
    )
  end
end

RubyExampleMod
