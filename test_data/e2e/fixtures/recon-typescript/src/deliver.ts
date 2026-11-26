import { request as httpsRequest } from 'node:https';

export interface Subscription {
  topic: string;
  endpoint: string;
  timeoutMs: number;
}

/** Every subscriber endpoint is an https host name of the subscriber, set here by the team. */
const REGISTRY: Subscription[] = [
  { topic: 'invoice.paid', endpoint: 'https://billing.example.com/hooks/relay', timeoutMs: 5000 },
  { topic: 'parcel.shipped', endpoint: 'https://tracking.example.com/hooks/relay', timeoutMs: 5000 },
  { topic: 'invoice.paid', endpoint: 'https://ledger.example.com/hooks/relay', timeoutMs: 3000 },
];

export function route(topic: string): Subscription[] {
  return REGISTRY.filter((subscription) => subscription.topic === topic);
}

/** Posts the payload to the endpoint of a subscription and resolves to the status it answered. */
export function deliver(subscription: Subscription, payload: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const request = httpsRequest(subscription.endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) },
      timeout: subscription.timeoutMs,
    }, (response) => {
      response.resume();
      response.on('end', () => resolve(response.statusCode ?? 0));
    });
    request.on('timeout', () => request.destroy(new Error('endpoint timed out')));
    request.on('error', reject);
    request.end(payload);
  });
}
