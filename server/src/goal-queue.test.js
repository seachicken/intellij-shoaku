import test from 'node:test';
import assert from 'node:assert/strict';
import { nextQueueGoal } from './goal-queue.js';

function hooks(overrides = {}) {
  return { attached: () => false, starting: () => false, failed: () => false,
    persisted: async () => false, ...overrides };
}

test('active, blocked, and incomplete review sessions do not delay independent goals', async () => {
  for (const status of ['active', 'blocked', 'paused', 'budgetLimited', 'usageLimited', 'complete']) {
    const previous = { shoakuId: 'shoaku-aA1234', status: { goalStatus: status, review: { ready: false } } };
    const next = { shoakuId: 'shoaku-bB5678' };
    assert.deepEqual(await nextQueueGoal([previous, next], hooks({
      attached: id => id === previous.shoakuId
    })), { goal: next, action: 'start' });
  }
});

test('independent goals start while earlier goals are still starting or restoring', async () => {
  const goals = [{ shoakuId: 'shoaku-aA1234', checked: true },
    { shoakuId: 'shoaku-bB5678' }, { shoakuId: 'shoaku-cC9012' }];
  const starting = new Set();
  const options = hooks({ starting: id => starting.has(id),
    persisted: async id => id === goals[0].shoakuId });
  for (const [index, goal] of goals.entries()) {
    assert.deepEqual(await nextQueueGoal(goals, options), {
      goal, action: index === 0 ? 'resume' : 'start'
    });
    starting.add(goal.shoakuId);
  }
  assert.equal(await nextQueueGoal(goals, options), null);
});

test('failed or unavailable restoration and checked unstarted goals do not delay other goals', async () => {
  const goals = [{ shoakuId: 'failed' }, { shoakuId: 'unavailable' },
    { shoakuId: 'checked', checked: true }, { shoakuId: 'next' }];
  assert.deepEqual(await nextQueueGoal(goals, hooks({
    failed: goal => goal.shoakuId === 'failed',
    unavailable: id => id === 'unavailable'
  })), { goal: goals[3], action: 'start' });
});

test('manual start during session lookup cannot schedule the same goal twice', async () => {
  const goal = { shoakuId: 'shoaku-aA1234' };
  let starting = false;
  assert.equal(await nextQueueGoal([goal], hooks({
    starting: () => starting,
    persisted: async () => { starting = true; return true; }
  })), null);
});
