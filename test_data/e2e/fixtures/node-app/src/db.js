const { createLogger } = require("./logger");

const log = createLogger("db");

const rows = [];

function saveOrder(order) {
  rows.push(order);
  log.info(`saved order ${order.id}`);
  return order;
}

module.exports = { saveOrder };
