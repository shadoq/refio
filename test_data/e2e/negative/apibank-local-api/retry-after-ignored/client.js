"use strict";

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

const MAX_RATE_LIMIT_ATTEMPTS = 5;
const MAX_SERVER_RETRIES = 2;

class ApiClient {
  constructor({ baseUrl, token, sleep }) {
    this.baseUrl = baseUrl;
    this.token = token;
    this.sleep = sleep || ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  }

  async _failure(res) {
    let message = "HTTP " + res.status;
    try {
      const body = await res.json();
      if (body && typeof body.error === "string") message = body.error;
    } catch (err) {
      // no usable body, keep the status text
    }
    return new ApiError(res.status, message);
  }

  async _request(method, path, body) {
    let rateLimited = 0;
    let serverErrors = 0;
    for (;;) {
      const headers = { Authorization: "Bearer " + this.token };
      if (body !== undefined) headers["Content-Type"] = "application/json";
      const res = await fetch(this.baseUrl + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      if (res.status === 429) {
        rateLimited++;
        if (rateLimited >= MAX_RATE_LIMIT_ATTEMPTS) throw await this._failure(res);
        const header = res.headers.get("retry-after");
        const seconds = 2 ** (rateLimited - 1);
        await this.sleep(seconds * 1000);
        continue;
      }
      if (res.status >= 500 && method === "GET") {
        serverErrors++;
        if (serverErrors > MAX_SERVER_RETRIES) throw await this._failure(res);
        await this.sleep(100 * 2 ** (serverErrors - 1));
        continue;
      }
      if (res.status >= 400) throw await this._failure(res);
      return res.json();
    }
  }

  async listAll({ pageSize = 50 } = {}) {
    const items = [];
    let cursor = null;
    do {
      let path = "/v1/items?limit=" + encodeURIComponent(pageSize);
      if (cursor !== null) path += "&cursor=" + encodeURIComponent(cursor);
      const page = await this._request("GET", path);
      items.push(...page.items);
      cursor = page.next_cursor === undefined ? null : page.next_cursor;
    } while (cursor !== null);
    return items;
  }

  async getItem(id) {
    try {
      return await this._request("GET", "/v1/items/" + encodeURIComponent(id));
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) return null;
      throw err;
    }
  }

  async createItem(body) {
    return this._request("POST", "/v1/items", body);
  }
}

module.exports = { ApiClient, ApiError };
