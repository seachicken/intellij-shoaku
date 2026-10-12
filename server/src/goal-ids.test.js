import test from 'node:test';
import assert from 'node:assert/strict';
import { assignGoalIds } from './goal-ids.js';

test('assigns IDs before work starts, preserving notes and existing IDs', async () => {
  const ids = ['shoaku-aA1234', 'shoaku-Zz9876'];
  const input = '- [ ] Implement [shoaku]\n  - Keep notes\n- [shoaku] Explain PR\n- Existing [shoaku-bB5678]\n';
  const result = await assignGoalIds(input, async () => ids.shift());
  assert.equal(result.text, '- [ ] Implement [shoaku-aA1234]\n  - Keep notes\n- [shoaku-Zz9876] Explain PR\n- Existing [shoaku-bB5678]\n');
  assert.equal(result.changed, true);
  const again = await assignGoalIds(result.text, () => { throw new Error('must not reassign'); });
  assert.equal(again.changed, false);
});

test('does not allocate IDs for markers in fenced code examples', async () => {
  const input = '- Existing [shoaku-aA1234]\n  ```md\n  - Example [shoaku]\n  ```';
  assert.equal((await assignGoalIds(input, () => { throw new Error('example is not a goal'); })).changed, false);
});
