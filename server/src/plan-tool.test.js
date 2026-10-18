import test from 'node:test';
import assert from 'node:assert/strict';
import { runPlanTool } from './plan-tool.js';

const request = args => ({ threadId: 'navigator', turnId: 'turn', tool: 'shoaku_save_plan', arguments: args });

test('hands off a long plan unchanged and returns a short acknowledgement', async () => {
  const plansMd = '# Goal\n- [ ] Implement\n' + 'reference material\n'.repeat(1000);
  let saved;
  const result = await runPlanTool(request({ plansMd, requiredInput: '' }), {
    authorized: () => true,
    save: async (params, args) => { saved = args; }
  });
  assert.equal(saved.plansMd, plansMd);
  assert.equal(result.success, true);
  assert.ok(result.contentItems[0].text.length < 200);
});

test('rejects unauthorized callers, unknown tools, paths, and invalid plans without saving', async () => {
  let saves = 0;
  const hooks = { authorized: p => p.threadId === 'navigator', save: async () => { saves++; } };
  for (const params of [
    { ...request({ plansMd: 'plan', requiredInput: '' }), threadId: 'explorer' },
    { ...request({ plansMd: 'plan', requiredInput: '' }), tool: 'other' },
    request({ plansMd: 'plan', requiredInput: '', path: '/tmp/other' }),
    request({ plansMd: '', requiredInput: '' }),
    request({ plansMd: 12, requiredInput: '' })
  ]) assert.equal((await runPlanTool(params, hooks)).success, false);
  assert.equal(saves, 0);
});

test('accepts a missing-information question without requiring a plan', async () => {
  let saved;
  const result = await runPlanTool(request({ plansMd: '', requiredInput: 'Which API version?' }), {
    authorized: () => true, save: async (params, args) => { saved = args; }
  });
  assert.equal(saved.requiredInput, 'Which API version?');
  assert.equal(result.success, true);
  assert.match(result.contentItems[0].text, /not dispatched/);
});

test('reports persistence or dispatch failures to the agent without claiming success', async () => {
  const result = await runPlanTool(request({ plansMd: 'plan', requiredInput: '' }), {
    authorized: () => true, save: async () => { throw new Error('write failed'); }
  });
  assert.equal(result.success, false);
  assert.match(result.contentItems[0].text, /write failed/);
});
