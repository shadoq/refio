'use strict';

/**
 * Cart maths for the storefront checkout.
 *
 * Prices arrive from the catalog feed as dollar amounts (19.99, 0.1, ...).
 * The receipt printer needs the totals, the finance export needs the
 * per-cart breakdown, and support needs the numbers to match what the
 * customer saw to the cent.
 */

const TAX_RATE = 0.23; // VAT, applied on the discounted subtotal

class Cart {
  constructor() {
    /** @type {{sku: string, unitPrice: number, qty: number}[]} */
    this.items = [];
  }

  /** Add `qty` units of `sku` at `unitPrice` dollars. */
  add(sku, unitPrice, qty) {
    this.items.push({ sku, unitPrice, qty });
    return this;
  }

  /** Gross subtotal in dollars, before any discount. */
  subtotal() {
    let total = 0;
    for (const item of this.items) {
      total += item.unitPrice * item.qty;
    }
    return total;
  }

  /** Subtotal in dollars after a flat `discountRate` (0.15 = 15%). */
  discountedSubtotal(discountRate) {
    let total = 0;
    for (const item of this.items) {
      // Round the discounted unit price to whole cents, then scale by qty.
      total += Math.round(item.unitPrice * (1 - discountRate) * 100) / 100 * item.qty;
    }
    return total;
  }

  /** Final total in dollars: discounted subtotal plus VAT. */
  total(discountRate = 0) {
    const net = this.discountedSubtotal(discountRate);
    const withTax = net * (1 + TAX_RATE);
    return parseFloat(withTax.toFixed(2));
  }
}

module.exports = { Cart, TAX_RATE };
