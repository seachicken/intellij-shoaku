import { randomInt } from 'node:crypto';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';

const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
const randomSuffix = () => Array.from({ length: 6 }, () => alphabet[randomInt(alphabet.length)]).join('');
export const isSessionId = value => typeof value === 'string' && /^shoaku-[A-Za-z0-9]{6}$/.test(value);

// Reserve the directory atomically so short IDs cannot reuse another session.
export async function reserveSessionId(sessionsDir, suffix = randomSuffix) {
  await mkdir(sessionsDir, { recursive: true });
  for (let attempt = 0; attempt < 100; attempt++) {
    const id = `shoaku-${suffix()}`;
    if (!isSessionId(id)) throw new Error('Invalid session ID.');
    try {
      await mkdir(join(sessionsDir, id));
      return id;
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
    }
  }
  throw new Error('Could not reserve a unique session ID.');
}
