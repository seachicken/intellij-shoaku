import test from 'node:test';
import assert from 'node:assert/strict';
import { explorerObjective } from './explorer-objective.js';

test('all goal modes stay within the API limit and refer to the complete persisted plan', () => {
  for (const documentationOnly of [false, true]) {
    for (const reviewRepair of [false, true]) {
      const objective = explorerObjective({ documentationOnly, reviewRepair });
      assert.ok(Buffer.byteLength(objective, 'utf8') <= 4000);
      assert.ok(objective.includes('.shoaku/PLANS.md'));
      if (reviewRepair) assert.ok(objective.includes('.shoaku/REVIEW-REPAIR.md'));
      if (documentationOnly) assert.ok(objective.includes('Preserve source files, Git history'));
    }
  }
});
