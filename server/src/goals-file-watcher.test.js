import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rename, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { GoalsFileWatcher } from './goals-file-watcher.js';

test('goals updates survive atomic replacement and switching files retires the old watcher', async () => {
  const root = await mkdtemp(join(tmpdir(), 'shoaku-watch-'));
  const watcher = new GoalsFileWatcher();
  const old = join(root, 'old.md');
  const next = join(root, 'next.md');
  const events = [];
  const errors = [];
  const runs = [];
  async function waitForEvent(path) {
    for (let i = 0; i < 100 && !events.includes(path); i++) await delay(10);
    assert.ok(events.includes(path), `No update for ${path}`);
    assert.deepEqual(errors, []);
  }
  try {
    await writeFile(old, 'old');
    await writeFile(next, 'next');
    runs.push(watcher.watch(old, async path => events.push(path), error => errors.push(error)));
    await writeFile(join(root, 'replacement.md'), 'replacement');
    await rename(join(root, 'replacement.md'), old);
    await waitForEvent(old);
    events.length = 0;
    await writeFile(old, 'updated after replacement');
    await waitForEvent(old);

    runs.push(watcher.watch(next, async path => events.push(path), error => errors.push(error)));
    await runs[0];
    events.length = 0;
    await writeFile(old, 'obsolete update');
    await writeFile(next, 'current update');
    await waitForEvent(next);
    assert.ok(!events.includes(old));
  } finally {
    watcher.close();
    await Promise.all(runs);
    await rm(root, { recursive: true, force: true });
  }
});
