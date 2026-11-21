# order model

`lib/order.rb` is the storefront's Order model: line-item math, the
pending -> paid -> shipped lifecycle and the packing-slip summary. The
storefront requires it with `require "order"` on its load path.
