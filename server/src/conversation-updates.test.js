import test from 'node:test';
import assert from 'node:assert/strict';
import { ConversationUpdates } from './conversation-updates.js';
const tick = () => new Promise(resolve => setImmediate(resolve));

test('waits for the conversation answer and keeps updates with their own goals', async () => {
  const updates = [];
  const queue = new ConversationUpdates(async (id, input) => updates.push({ id, input }));
  queue.started('implementation', 'answer');
  queue.enqueue('implementation', 'Change the plan', 'answer');
  queue.enqueue('implementation', 'Keep compatibility', 'answer');
  queue.started('review', 'review-answer');
  queue.enqueue('review', 'Explain failure handling', 'review-answer');
  await tick();
  assert.deepEqual(updates, []);
  queue.completed('review', 'review-answer');
  await tick();
  assert.deepEqual(updates, [{ id: 'review', input: 'Explain failure handling' }]);
  queue.completed('implementation', 'answer');
  await tick();
  assert.deepEqual(updates[1], { id: 'implementation', input: 'Change the plan\n\nKeep compatibility' });
  assert.equal(queue.busy('implementation'), false);
});

test('does not apply requests from interrupted or failed conversations', async () => {
  const updates = [];
  const queue = new ConversationUpdates(async (_, input) => updates.push(input));
  for (const status of ['interrupted', 'failed']) {
    queue.enqueue('goal', 'Incomplete discussion', status);
    queue.completed('goal', status, status);
  }
  queue.enqueue('goal', 'Final direction', 'completed');
  queue.completed('goal', 'completed');
  await tick();
  assert.deepEqual(updates, ['Final direction']);
});

test('serializes refreshes and preserves a new request while documents are updating', async () => {
  const updates = [], settled = [];
  let release;
  const queue = new ConversationUpdates(async (_, input) => {
    updates.push(input);
    if (input === 'First') await new Promise(resolve => { release = resolve; });
  }, () => assert.fail('refresh should succeed'), id => settled.push(id));
  queue.enqueue('goal', 'First');
  queue.enqueue('goal', 'Second', 'follow-up');
  queue.completed('goal', 'follow-up');
  assert.deepEqual(updates, ['First']);
  assert.equal(queue.busy('goal'), true);
  release();
  await tick();
  assert.deepEqual(updates, ['First', 'Second']);
  assert.equal(queue.busy('goal'), false);
  assert.deepEqual(settled, ['goal', 'goal']);
});

test('retains a plan update when a new conversation starts during refresh preparation', async () => {
  const updates = [];
  let queue;
  queue = new ConversationUpdates(async (id, input) => {
    if (!updates.length) {
      updates.push('deferred');
      queue.started(id, 'new-question');
      const error = new Error('Busy');
      error.code = 'CONVERSATION_BUSY';
      throw error;
    }
    updates.push(input);
  }, () => assert.fail('a busy conversation is not a failed update'));
  queue.enqueue('goal', 'Change the plan');
  await tick();
  assert.deepEqual(updates, ['deferred']);
  queue.completed('goal', 'new-question');
  await tick();
  assert.deepEqual(updates, ['deferred', 'Change the plan']);
});
