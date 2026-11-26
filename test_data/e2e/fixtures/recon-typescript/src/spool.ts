import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { RelayError } from './errors';

/** Names of the deliveries kept in the archive: base64url, at most 128 characters. */
const ARCHIVED_NAME = /^[A-Za-z0-9_-]{1,128}$/;

/** The slot of a delivery, named after the SHA-256 digest of its body. */
export function slotPath(digest: string, spoolDir = 'spool'): string {
  if (!/^[a-f0-9]{64}$/.test(digest)) {
    throw new RelayError(400, 'delivery digest has the wrong shape');
  }
  return join(spoolDir, 'slots', digest.slice(0, 2), `${digest}.json`);
}

/** True once a delivery with this digest has been accepted. */
export function hasSeen(digest: string, spoolDir = 'spool'): boolean {
  return existsSync(slotPath(digest, spoolDir));
}

/** Remembers the digest of a delivery so that a replay can be recognised. */
export function markSeen(digest: string, body: string, spoolDir = 'spool'): string {
  const slot = slotPath(digest, spoolDir);
  mkdirSync(dirname(slot), { recursive: true });
  writeFileSync(slot, body, 'utf8');
  return slot;
}

/**
 * Path of a delivery in the inbox. The sender's event id becomes the file name, so that a
 * partner can ask for a replay by naming the event.
 */
export function spoolPath(eventId: string, spoolDir = 'spool'): string {
  return join(spoolDir, 'inbox', `${eventId}.json`);
}

export function spoolDelivery(eventId: string, body: string, spoolDir = 'spool'): string {
  const target = spoolPath(eventId, spoolDir);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, body, 'utf8');
  return target;
}

/**
 * Path of an archived delivery. Since 2.1.0 a name is accepted only when it is a plain stored
 * name, so it cannot leave the archive directory.
 */
export function archivePath(name: string, spoolDir = 'spool'): string | null {
  return ARCHIVED_NAME.test(name) ? join(spoolDir, 'archive', name) : null;
}

export function readArchived(name: string, spoolDir = 'spool'): string {
  const target = archivePath(name, spoolDir);
  if (target === null) {
    throw new RelayError(400, 'invalid archive name');
  }
  try {
    return readFileSync(target, 'utf8');
  } catch {
    throw new RelayError(404, 'no such archived delivery');
  }
}
