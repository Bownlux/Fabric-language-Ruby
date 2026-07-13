# frozen_string_literal: true

class InstanceGreeter
  def initialize
    @greeted = 0
  end

  def greet_someone(name)
    @greeted += 1
    "Hello, #{name}, from an instance! (call ##{@greeted})"
  end
end

InstanceGreeter.new
