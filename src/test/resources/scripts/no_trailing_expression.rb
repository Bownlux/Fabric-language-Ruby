# frozen_string_literal: true

# Deliberately missing the trailing `ForgotTheConstant` line: the script's last
# expression is the Symbol returned by `def`, a common beginner mistake the
# adapter must report descriptively.
class ForgotTheConstant
  def greet_someone(name)
    "Hello, #{name}!"
  end
end
