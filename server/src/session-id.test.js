import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { isSessionId, reserveSessionId } from './session-id.js';

test('short IDs retry collisions without changing existing session data', async () => {
  const root = await mkdtemp(join(tmpdir(), 'shoaku-id-test-'));
  try {
    await mkdir(join(root, 'shoaku-abcdef'));
    await writeFile(join(root, 'shoaku-abcdef/meta.json'), 'existing');
    const candidates = ['abcdef', '123456'];
    assert.equal(await reserveSessionId(root, () => candidates.shift()), 'shoaku-123456');
    assert.equal(await readFile(join(root, 'shoaku-abcdef/meta.json'), 'utf8'), 'existing');
    assert.match(await reserveSessionId(root), /^shoaku-[A-Za-z0-9]{6}$/);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('simultaneous reservations cannot return the same ID', async () => {
  const root = await mkdtemp(join(tmpdir(), 'shoaku-id-test-'));
  try {
    const first = ['abcdef', '111111'];
    const second = ['abcdef', '222222'];
    const ids = await Promise.all([
      reserveSessionId(root, () => first.shift()),
      reserveSessionId(root, () => second.shift())
    ]);
    assert.equal(new Set(ids).size, 2);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('preserves the old six-character alphabet while rejecting long IDs', () => {
  assert.equal(isSessionId('shoaku-aA1234'), true);
  assert.equal(isSessionId('shoaku-Zz09Bc'), true);
  assert.equal(isSessionId('shoaku-abcdef123456'), false);
  assert.equal(isSessionId('shoaku-ab_123'), false);
});
