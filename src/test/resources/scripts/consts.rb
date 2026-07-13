# frozen_string_literal: true

module Example
  NOT_AN_ENTRYPOINT = 42
  NIL_CONSTANT = nil

  module Greeters
    # Instance method named like the Module built-in `name`; the adapter must
    # bind the instance method, not Module#name.
    class BuiltinShadow
      def name
        'from the instance method'
      end
    end

    module ModuleGreeter
      module_function

      def greet(name)
        "Hello, #{name}, from a module function!"
      end
    end

    class KlassGreeter
      def greet_someone(name)
        "Hello, #{name}, from a namespaced class!"
      end
    end
  end
end
