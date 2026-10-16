import test from 'node:test';
import assert from 'node:assert/strict';
import parser from './parser.js';
import { requirementSnapshots } from './goal-requirements.js';

const document = `- [shoaku-aaaaaa] First goal
  - [ ] First task
- [shoaku-bbbbbb] Second goal
  - [ ] Second task`;
const snapshots = text => requirementSnapshots(parser.parse(text));
const changed = (before, after) => [...after].filter(([id, value]) =>
  JSON.stringify(before.get(id)) !== JSON.stringify(value)).map(([id]) => id);

test('unrelated Markdown and shifted line numbers leave session requirements unchanged', () => {
  const before = snapshots(document);
  const after = snapshots(`# Notes\n\n${document}\n\nUnrelated notes`);
  assert.deepEqual(changed(before, after), []);
});

test('editing a session or its child tasks changes only that session requirements', () => {
  const before = snapshots(document);
  for (const edited of [
    document.replace('First goal', 'Updated goal'),
    document.replace('First task', 'Updated task'),
    document.replace('[ ] First task', '[x] First task'),
    document.replace('  - [ ] First task', '  - [ ] First task\n  - [ ] Added task'),
    document.replace('  - [ ] First task\n', '')
  ]) {
    assert.deepEqual(changed(before, snapshots(edited)), ['shoaku-aaaaaa']);
  }
});
