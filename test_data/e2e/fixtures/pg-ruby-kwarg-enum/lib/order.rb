# frozen_string_literal: false

# Order model for the storefront checkout.
#
# Instances are built from the checkout form and the payment webhook, both of
# which deliver the status as a string, so every check compares against the
# literal "pending" / "paid" / "shipped" strings.

class Order
  # The lifecycle, in order. Nothing keeps callers from passing a typo.
  STATUSES = ["pending", "paid", "shipped"]

  def initialize(id, customer, items, status, note)
    @id = id
    @customer = customer
    @items = items
    @status = status
    @note = note
  end

  attr_reader :id, :customer, :items, :status, :note

  # Only orders that have not been paid can be cancelled.
  def cancellable?
    @status == "pending"
  end

  # Moves the order one step along the lifecycle.
  def advance!
    if @status == "pending"
      @status = "paid"
    elsif @status == "paid"
      @status = "shipped"
    else
      raise "cannot advance a shipped order"
    end
  end

  # Line-item total in cents; items are hashes with :sku, :qty and
  # :price_cents.
  def total_cents
    @items.reduce(0) { |sum, item| sum + item[:qty] * item[:price_cents] }
  end

  def item_count
    @items.reduce(0) { |sum, item| sum + item[:qty] }
  end

  # One-line summary for the packing slip.
  def summary
    note = @note.nil? ? "(no note)" : @note
    format("%s: %s - %d items - %s", @id, @customer, item_count, format_cents(total_cents))
  end

  def to_s
    "##{@id} #{@customer}: #{@status}"
  end

  private

  def format_cents(cents)
    format("%d.%02d", cents / 100, cents % 100)
  end
end
