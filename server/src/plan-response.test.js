import assert from 'node:assert/strict';
import test from 'node:test';
import { parsePlanResponse } from './plan-response.js';

test('ignores commentary even if it contains a plan-shaped JSON object', () => {
  assert.equal(parsePlanResponse({ type: 'agentMessage', phase: 'commentary', text: '{"plansMd":"draft"}' }), null);
});

test('non-JSON messages and malformed plans cannot crash or start implementation', () => {
  for (const text of [undefined, 'Inspecting the repository.', '{', 'null', '[]', '{}', '{"plansMd":12}', '{"plansMd":"  "}']) {
    assert.equal(parsePlanResponse({ type: 'agentMessage', phase: 'final_answer', text }), null);
  }
  assert.equal(parsePlanResponse({ type: 'userMessage' }), null);
});

test('accepts a final plan, preserving its Markdown and message metadata', () => {
  const plansMd = '# Goal\n- [ ] Implement\n';
  const item = { type: 'agentMessage', phase: 'final_answer', id: 'plan', text: JSON.stringify({ plansMd }) };
  assert.deepEqual(parsePlanResponse(item), { ...item, plansMd });
  assert.equal(parsePlanResponse({ ...item, phase: undefined }).plansMd, plansMd);
});

test('accepts a specific information request without an implementation plan', () => {
  const item = { type: 'agentMessage', phase: 'final_answer', text: JSON.stringify({ plansMd: '', requiredInput: 'Which storage backend must be supported?' }) };
  assert.equal(parsePlanResponse(item).requiredInput, 'Which storage backend must be supported?');
});
