"use strict";

// Client for the warehouse stock service. The service is not reachable from
// development machines, so every call fails until the integration is restored.
function connect() {
  throw new Error("warehouse service unavailable: connection refused");
}

function stockLevel(sku) {
  const conn = connect();
  return conn.query(sku);
}

module.exports = { stockLevel };
