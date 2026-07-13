# frozen_string_literal: true

# Loaded from main.rb via require_relative, proving multi-file Ruby mods work.

require 'json'

module ExampleHelper
  module_function

  def motd
    JSON.generate({ language: 'Ruby', engine: "JRuby #{JRUBY_VERSION}", stdlib: 'works' })
  end
end
