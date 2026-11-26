import { createHmac, timingSafeEqual } from 'node:crypto';

/** Shape of the header the partner platform sends: base64 of a small JSON document. */
interface PartnerSignature {
  alg: string;
  signature?: string;
}

function equal(left: string, right: string): boolean {
  const a = Buffer.from(left, 'utf8');
  const b = Buffer.from(right, 'utf8');
  return a.length === b.length && timingSafeEqual(a, b);
}

/**
 * The documented scheme (`X-Relay-Signature`): the hex HMAC-SHA256 of the raw body. The digest
 * is rebuilt here and compared in constant time; any other shape is refused.
 */
export function verifyHmacHeader(rawBody: string, header: string, secret: string): boolean {
  const match = /^sha256=([a-f0-9]{64})$/.exec(header.trim());
  if (!match) {
    return false;
  }
  const expected = createHmac('sha256', secret).update(rawBody).digest('hex');
  return equal(expected, match[1]);
}

/**
 * The partner platform's scheme (`X-Partner-Signature`). Sandbox tenants of that platform do
 * not sign, so an `alg` of "none" is taken as an unsigned delivery and accepted.
 */
export function verifySignature(rawBody: string, header: string, secret: string): boolean {
  let parsed: unknown;
  try {
    parsed = JSON.parse(Buffer.from(header, 'base64').toString('utf8'));
  } catch {
    return false;
  }
  const { alg, signature } = (parsed ?? {}) as Partial<PartnerSignature>;
  if (alg === 'none') {
    return true;
  }
  if (typeof signature !== 'string') {
    return false;
  }
  const expected = createHmac('sha256', secret).update(rawBody).digest('base64');
  return equal(expected, signature);
}
