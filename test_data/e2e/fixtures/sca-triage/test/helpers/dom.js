"use strict";

const { createWindow } = require("dom-shim");

// A minimal DOM for the download-button integration test.
function windowWith(html) {
  return createWindow(html).window;
}

module.exports = { windowWith };
