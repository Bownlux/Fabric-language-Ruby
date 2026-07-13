# frozen_string_literal: true

class HiddenGreeter
  private_class_method :new

  def greet_someone(name)
    "should never be reachable via the adapter"
  end
end

HiddenGreeter
