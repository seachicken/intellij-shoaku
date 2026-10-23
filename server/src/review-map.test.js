import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import childProcess from 'node:child_process';
import { promisify } from 'node:util';
import { inspectReviewMap } from './review-map.js';

const execFile = promisify(childProcess.execFile);
test('design-to-code maps use actual commit lines, account for files, and reject invented relationships', async () => {
  const root = await mkdtemp(join(tmpdir(), 'shoaku-design-map-'));
  const git = async (workspace, ...args) => (await execFile('git', ['-C', workspace, '-c', 'commit.gpgsign=false', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', ...args])).stdout;
  try {
    await git(root, 'init');
    await writeFile(join(root, 'old.txt'), 'old behavior\n');
    await git(root, 'add', '--', 'old.txt'); await git(root, 'commit', '-m', 'Baseline');
    await rm(join(root, 'old.txt'));
    await writeFile(join(root, 'new.txt'), 'new behavior\n');
    await writeFile(join(root, 'image.bin'), Buffer.from([0, 1, 2]));
    await git(root, 'add', '--', 'old.txt', 'new.txt', 'image.bin'); await git(root, 'commit', '-m', 'Replace behavior');
    const hash = (await git(root, 'rev-parse', 'HEAD')).trim();
    await mkdir(join(root, '.shoaku', 'commits'), { recursive: true });
    const mapPath = join(root, '.shoaku', 'commits', `${hash}.review.json`);
    await writeFile(join(root, '.shoaku', 'commits', `${hash}.md`), `# Design\n${hash}\n## Remove old behavior\n## Add new behavior\n`);
    const map = {
      schemaVersion: 1, commit: hash, problem: 'Old behavior is limited', before: 'Old', after: 'New',
      steps: [
        { id: 'remove', title: 'Remove old behavior', why: 'Avoid duplicate behavior', change: 'Delete old path', verification: 'Deletion checked',
          anchors: [{ path: 'old.txt', side: 'before', startLine: 1, endLine: 1, label: 'Old entry' }], links: [{ to: 'add', label: 'Replaced by' }] },
        { id: 'add', title: 'Add new behavior', why: 'Support new goal', change: 'New entry', verification: 'Not verified',
          anchors: [{ path: 'new.txt', side: 'after', startLine: 1, endLine: 1, label: 'New entry' }], links: [] }
      ], excludedFiles: [{ path: 'image.bin', reason: 'Binary asset has no source lines' }]
    };
    const inspect = async value => { await writeFile(mapPath, JSON.stringify(value)); return inspectReviewMap(root, { hash }, git); };
    assert.equal((await inspect(map)).steps.length, 2);
    const prose = structuredClone(map);
    prose.steps[1].intent = 'Help the reader understand the new behavior';
    prose.steps[1].anchors[0].label = 'Sentence introducing the new behavior';
    const inspectedProse = await inspect(prose);
    assert.equal(inspectedProse.steps[1].intent, prose.steps[1].intent);
    assert.deepEqual(inspectedProse.steps[1].anchors, prose.steps[1].anchors);
    for (const intent of ['', '   ', null, 42, 'x'.repeat(4001)]) {
      const invalidIntent = structuredClone(prose); invalidIntent.steps[1].intent = intent;
      await assert.rejects(inspect(invalidIntent), /intent/);
    }
    await writeFile(join(root, 'new.txt'), 'workspace now has\nmany\nlines\n');
    const wrongLine = structuredClone(map); wrongLine.steps[1].anchors[0].endLine = 3;
    await assert.rejects(inspect(wrongLine), /does not exist/);
    const wrongSide = structuredClone(map); wrongSide.steps[0].anchors[0].side = 'after';
    await assert.rejects(inspect(wrongSide));
    const wrongHash = structuredClone(map); wrongHash.commit = '0'.repeat(40);
    await assert.rejects(inspect(wrongHash), /identify this commit/);
    const missing = structuredClone(map); missing.excludedFiles = [];
    await assert.rejects(inspect(missing), /lack design correspondence/);
    const edge = structuredClone(map); edge.steps[0].links[0].to = 'imaginary';
    await assert.rejects(inspect(edge), /unknown step/);
    const invalidPath = structuredClone(map); invalidPath.steps[1].anchors[0].path = '../outside.txt';
    await assert.rejects(inspect(invalidPath), /invalid text or code location/);
    assert.equal((await inspect(map)).steps.length, 2);
  } finally { await rm(root, { recursive: true, force: true }); }
});
