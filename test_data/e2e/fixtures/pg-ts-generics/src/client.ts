// client.ts - fetches the weekly orders report and prints it to the console.
//
// Everything here deals in raw JSON: the API methods are annotated `any` and
// the shapes only exist implicitly inside the label helpers in models.ts.
// The report script is the only consumer, so nobody has typed it yet.

import { orderTotalLabel, userLabel } from "./models";

const BASE_URL = "https://api.orders.example/v1";

class ApiClient {
  private baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl || BASE_URL;
  }

  // Low-level GET. The caller gets whatever JSON arrives.
  private async get(path: string): any {
    const response = await fetch(this.baseUrl + path);
    if (!response.ok) {
      throw new Error("GET " + path + " failed with HTTP " + response.status);
    }
    return response.json();
  }

  async getUser(id: number): any {
    return this.get("/users/" + id);
  }

  async listUsers(): any {
    return this.get("/users");
  }

  async getOrder(id: number): any {
    return this.get("/orders/" + id);
  }

  async listOrders(userId: number): any {
    return this.get("/users/" + userId + "/orders");
  }
}

// Builds and prints the weekly report.
async function main() {
  const client = new ApiClient(BASE_URL);
  const user = await client.getUser(42);
  console.log(userLabel(user));

  const orders = await client.listOrders(42);
  for (const order of orders) {
    console.log(orderTotalLabel(order));
  }
}

main().catch((err) => {
  console.error("report failed:", err);
  process.exitCode = 1;
});
