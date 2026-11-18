"use strict";

class ApiError extends Error {}

class ApiClient {
  constructor(options) {
    this.options = options;
  }

  async listAll() {
    throw new Error("not implemented");
  }

  async getItem() {
    throw new Error("not implemented");
  }

  async createItem() {
    throw new Error("not implemented");
  }
}

module.exports = { ApiClient, ApiError };
