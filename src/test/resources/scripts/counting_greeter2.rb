# frozen_string_literal: true

$counting_greeter2_loads = ($counting_greeter2_loads || 0) + 1

class CountingGreeter2
  def greet_someone(name)
    "Hello, #{name}! This script was loaded #{$counting_greeter2_loads} time(s)."
  end
end

CountingGreeter2
