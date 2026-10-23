import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { SessionOwner } from './session-owner.js';

test('a session stays with its window while independent goals can run elsewhere', async () => {
  const first = new SessionOwner();
  const second = new SessionOwner();
  const path = randomUUID();
  try {
    await first.claim(path);
    await first.claim(path);
    await assert.rejects(second.claim(path), /another IntelliJ window/);
    await second.claim(`${path}-independent`);
    await first.close();
    await second.claim(path);
  } finally {
    await first.close();
    await second.close();
  }
});
