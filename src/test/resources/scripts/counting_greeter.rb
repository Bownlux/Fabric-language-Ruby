# frozen_string_literal: true

$counting_greeter_loads = ($counting_greeter_loads || 0) + 1

class CountingGreeter
  def greet_someone(name)
    "Hello, #{name}! This script was loaded #{$counting_greeter_loads} time(s)."
  end
end

CountingGreeter
