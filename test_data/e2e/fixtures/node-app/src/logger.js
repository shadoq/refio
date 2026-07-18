// Shared logging helper used across the app. createLogger returns an object
// whose methods (info/error) every other module calls.
function createLogger(namespace) {
  return {
    info(message) {
      console.log(`[${namespace}] ${message}`);
    },
    error(message) {
      console.error(`[${namespace}] ERROR ${message}`);
    },
  };
}

module.exports = { createLogger };
