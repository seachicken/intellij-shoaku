import test from 'node:test';
import assert from 'node:assert/strict';
import { internalNavigatorInput, userConversationText } from './navigator-input.js';
import { ConversationUpdates } from './conversation-updates.js';
const message = text => ({ type: 'userMessage', content: [{ type: 'text', text }] });
const tick = () => new Promise(resolve => setImmediate(resolve));

test('server plan refresh echo cannot schedule another refresh after its turn completes', async () => {
  const refreshes = [];
  let queue;
  const completedItem = (turn, item) => {
    const text = userConversationText(item);
    if (text) queue.enqueue('goal', text, turn);
  };
  queue = new ConversationUpdates(async (_, input) => {
    refreshes.push(input);
    assert.ok(refreshes.length <= 2, 'Internal prompts must not cause an endless refresh loop');
    queue.started('goal', 'internal-plan-turn');
    completedItem('internal-plan-turn', message(internalNavigatorInput('Update the plan using shoaku_save_plan.')));
    queue.completed('goal', 'internal-plan-turn');
  });
  queue.started('goal', 'user-turn');
  completedItem('user-turn', message('Keep backwards compatibility.'));
  assert.deepEqual(refreshes, []);
  queue.completed('goal', 'user-turn');
  await tick();
  assert.deepEqual(refreshes, ['Keep backwards compatibility.']);
  assert.equal(queue.busy('goal'), false);
  queue.started('goal', 'next-user-turn');
  completedItem('next-user-turn', message('Also support the older client.'));
  queue.completed('goal', 'next-user-turn');
  await tick();
  assert.deepEqual(refreshes, ['Keep backwards compatibility.', 'Also support the older client.']);
  assert.equal(queue.busy('goal'), false);
});

test('internal startup and review prompts are ignored while real user content is preserved', () => {
  for (const text of ['Initialize the goal.', 'Regenerate PR design.', 'Update plan as JSON.']) {
    assert.equal(userConversationText(message(internalNavigatorInput(text))), null);
  }
  assert.equal(userConversationText({ type: 'agentMessage', content: [{ type: 'text', text: 'summary' }] }), null);
  assert.equal(userConversationText(message('')), null);
  assert.equal(userConversationText({ type: 'userMessage', content: [
    { type: 'text', text: 'Use API v2' }, { type: 'image', imageUrl: 'ignored' }, { type: 'text', text: 'Keep tests' }
  ] }), 'Use API v2\nKeep tests');
});
