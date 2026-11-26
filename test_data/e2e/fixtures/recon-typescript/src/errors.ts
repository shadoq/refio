import type { RelayConfig } from './config';

/** A failure the relay reports to the caller with its own status code. */
export class RelayError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'RelayError';
    this.status = status;
  }
}

/** One line for the caller: the relay's own reason for a rejected delivery, nothing else. */
export function publicMessage(error: unknown): string {
  return error instanceof RelayError ? error.message : 'delivery failed';
}

/**
 * Debug page for integration partners: the failure with its stack trace and the settings the
 * relay ran with, so that a partner can compare them with what the deployment platform shows.
 */
export function renderErrorPage(error: unknown, config: RelayConfig): string {
  const failure = error instanceof Error ? error : new Error(String(error));
  const settings = JSON.stringify(config, null, 2);
  return `<html><body><h1>webhook-relay: request failed</h1>
<p>${failure.name}: ${failure.message}</p>
<pre>${failure.stack ?? ''}</pre>
<h2>Runtime settings</h2>
<pre>${settings}</pre>
</body></html>`;
}
