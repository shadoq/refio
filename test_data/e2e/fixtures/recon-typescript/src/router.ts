import { createHash } from 'node:crypto';
import type { IncomingHttpHeaders } from 'node:http';
import type { RelayConfig } from './config';
import { notifyChat, pageOnCall } from './alerts';
import { deliver, route } from './deliver';
import { RelayError } from './errors';
import { verifyHmacHeader, verifySignature } from './signatures';
import { hasSeen, markSeen, readArchived, spoolDelivery } from './spool';

export interface RelayRequest {
  method: string;
  url: URL;
  headers: IncomingHttpHeaders;
  body: string;
}

export interface RelayResponse {
  status: number;
  type: string;
  body: string;
}

interface RelayEvent {
  id?: unknown;
  topic?: unknown;
}

const reply = (status: number, type: string, body: string): RelayResponse => ({ status, type, body });

const text = (status: number, message: string): RelayResponse => reply(status, 'text/plain', `${message}\n`);

const json = (status: number, value: unknown): RelayResponse =>
  reply(status, 'application/json', `${JSON.stringify(value)}\n`);

function parseEvent(rawBody: string): RelayEvent {
  let event: unknown;
  try {
    event = JSON.parse(rawBody);
  } catch {
    throw new RelayError(400, 'body is not JSON');
  }
  if (event === null || typeof event !== 'object' || Array.isArray(event)) {
    throw new RelayError(400, 'body is not a JSON object');
  }
  return event as RelayEvent;
}

/** Keeps a verified delivery, then forwards it to the endpoints registered for its topic. */
async function accept(event: RelayEvent, rawBody: string, config: RelayConfig): Promise<RelayResponse> {
  const topic = typeof event.topic === 'string' ? event.topic : '';
  const subscriptions = route(topic);
  if (subscriptions.length === 0) {
    throw new RelayError(404, 'no subscriber is registered for this topic');
  }
  const digest = createHash('sha256').update(rawBody).digest('hex');
  if (hasSeen(digest, config.spoolDir)) {
    return json(200, { status: 'duplicate', digest });
  }
  // A delivery is kept twice: under its digest for the duplicate check, and under the
  // sender's event id so that a partner can ask for a replay by naming the event.
  const stored = spoolDelivery(String(event.id ?? digest), rawBody, config.spoolDir);
  markSeen(digest, rawBody, config.spoolDir);

  const results: number[] = [];
  for (const subscription of subscriptions) {
    try {
      results.push(await deliver(subscription, rawBody));
    } catch (error) {
      results.push(0);
      await notifyChat('relay-alerts', `delivery of ${topic} to ${subscription.endpoint} failed`);
    }
  }
  if (!results.some((code) => code >= 200 && code < 300)) {
    await pageOnCall(topic, `every delivery of event ${String(event.id ?? digest)} failed`);
  }
  return json(202, { status: 'relayed', stored, results });
}

/** `POST /v1/events`: the documented signature scheme. */
function events(request: RelayRequest, config: RelayConfig): Promise<RelayResponse> {
  const signature = request.headers['x-relay-signature'];
  if (typeof signature !== 'string' || !verifyHmacHeader(request.body, signature, config.tenantSecret)) {
    throw new RelayError(401, 'invalid signature');
  }
  return accept(parseEvent(request.body), request.body, config);
}

/** `POST /v1/partner-events`: the partner platform's signature scheme. */
function partnerEvents(request: RelayRequest, config: RelayConfig): Promise<RelayResponse> {
  const signature = request.headers['x-partner-signature'];
  if (typeof signature !== 'string' || !verifySignature(request.body, signature, config.tenantSecret)) {
    throw new RelayError(401, 'invalid signature');
  }
  return accept(parseEvent(request.body), request.body, config);
}

/** `GET /archive?name=<file>`: a kept delivery, by its stored name. */
function archive(query: URLSearchParams, config: RelayConfig): RelayResponse {
  return reply(200, 'text/plain', readArchived(query.get('name') ?? '', config.spoolDir));
}

const PATHS = ['/health', '/archive', '/v1/events', '/v1/partner-events'];

export async function handle(request: RelayRequest, config: RelayConfig): Promise<RelayResponse> {
  const { url } = request;
  if (request.method === 'GET' && url.pathname === '/health') {
    return json(200, { service: 'webhook-relay', version: config.version, status: 'ok' });
  }
  if (request.method === 'GET' && url.pathname === '/archive') {
    return archive(url.searchParams, config);
  }
  if (request.method === 'POST' && url.pathname === '/v1/events') {
    return events(request, config);
  }
  if (request.method === 'POST' && url.pathname === '/v1/partner-events') {
    return partnerEvents(request, config);
  }
  if (PATHS.includes(url.pathname)) {
    throw new RelayError(405, 'method not allowed');
  }
  throw new RelayError(404, 'no such route');
}
