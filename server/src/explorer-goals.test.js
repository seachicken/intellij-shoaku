import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { explorerGoalsPath } from './explorer-goals.js';

test('existing Explorer progress survives the uppercase filename migration', async t => {
  const workspace = await mkdtemp(join(tmpdir(), 'shoaku-explorer-goals-'));
  t.after(() => rm(workspace, { recursive: true, force: true }));
  const directory = join(workspace, '.shoaku');
  await mkdir(directory);
  const content = '# Progress\n- [x] Completed task\n- [ ] Pending task\n';
  await writeFile(join(directory, 'goals.md'), content);
  const path = await explorerGoalsPath(workspace);
  assert.equal(path, join(directory, 'GOALS.md'));
  assert.equal(await readFile(path, 'utf8'), content);
  assert.deepEqual(await readdir(directory), ['GOALS.md']);
  assert.equal(await explorerGoalsPath(workspace), path);
  assert.equal(await readFile(path, 'utf8'), content);
});
