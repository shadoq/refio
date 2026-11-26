import { exec, execFile } from 'node:child_process';

const PAGE_TOOL = process.env.PAGE_TOOL ?? '/usr/local/bin/pageoncall';
const CHAT_CLI = 'chatctl';
const TIMEOUT_MS = 5000;

function ran(error: Error | null, stdout: string): Promise<string> {
  return error ? Promise.reject(error) : Promise.resolve(stdout);
}

/** Pages the on-call engineer for a topic; topic and message travel as separate arguments. */
export function pageOnCall(topic: string, message: string): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(PAGE_TOOL, ['--topic', topic.slice(0, 80), '--message', message.slice(0, 200)],
      { timeout: TIMEOUT_MS }, (error, stdout) => {
        resolve(ran(error, stdout));
      }).on('error', reject);
  });
}

/**
 * Posts a failed delivery to the team's chat channel. The chat CLI takes the whole post as one
 * command line, so the text is put between quotes.
 */
export function notifyChat(channel: string, text: string): Promise<string> {
  return new Promise((resolve, reject) => {
    exec(`${CHAT_CLI} post --channel "${channel}" --text "${text}"`, { timeout: TIMEOUT_MS }, (error, stdout) => {
      resolve(ran(error, stdout));
    }).on('error', reject);
  });
}
