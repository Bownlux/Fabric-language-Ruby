# frozen_string_literal: true

# The main entrypoint of the example mod. The last expression of this script
# (the RubyExampleMod class at the bottom) is handed to Fabric Loader, which
# instantiates it; on_initialize implements ModInitializer#onInitialize.

require 'java'
require_relative 'helper'

# The build vendors the dentaku gem (and its dependencies) into vendor/ via
# the vendorGems Gradle task; setup.rb puts each gem on $LOAD_PATH.
require_relative 'vendor/setup'
require 'dentaku'

class RubyExampleMod
  # Importing inside the class scopes the constant to it. All Ruby mods share
  # one runtime, so keep your constants namespaced.
  java_import 'net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents'

  # slf4j ships with Minecraft 1.18.2+; older versions only have Log4j. This
  # example runs on every supported version, so it falls back. A mod that
  # targets 1.18.2+ can just use the slf4j line.
  LOGGER = begin
    org.slf4j.LoggerFactory.getLogger('ruby-example-mod')
  rescue NameError
    org.apache.logging.log4j.LogManager.getLogger('ruby-example-mod')
  end

  def on_initialize
    LOGGER.info("Hello from Ruby! Running JRuby #{JRUBY_VERSION} (Ruby #{RUBY_VERSION}) inside Minecraft.")
    LOGGER.info("Ruby stdlib check: #{ExampleHelper.motd}")
    LOGGER.info("Vendored gem check: dentaku says 6 * 7 = #{Dentaku::Calculator.new.evaluate('6 * 7')}")

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
