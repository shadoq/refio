export interface RelayConfig {
  version: string;
  bindAddress: string;
  port: number;
  maxBodyBytes: number;
  spoolDir: string;
  tenantSecret: string;
  signingToken: string;
}

function positiveInteger(raw: string | undefined, fallback: number): number {
  const value = Number.parseInt(raw ?? '', 10);
  return Number.isInteger(value) && value > 0 ? value : fallback;
}

/** Settings from the environment; the relay refuses to start without the two secrets. */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): RelayConfig {
  for (const name of ['RELAY_TENANT_SECRET', 'RELAY_SIGNING_TOKEN']) {
    if (!env[name]) {
      throw new Error(`${name} is not set`);
    }
  }
  return {
    version: '2.1.0',
    bindAddress: env.BIND_ADDRESS ?? '127.0.0.1',
    port: positiveInteger(env.PORT, 8080),
    maxBodyBytes: positiveInteger(env.MAX_BODY_BYTES, 1024 * 1024),
    spoolDir: env.RELAY_SPOOL_DIR ?? 'spool',
    tenantSecret: env.RELAY_TENANT_SECRET ?? '',
    signingToken: env.RELAY_SIGNING_TOKEN ?? '',
  };
}
