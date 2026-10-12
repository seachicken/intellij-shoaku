import test from 'node:test';
import assert from 'node:assert/strict';
import { activeGoalId, canAdvanceGoal, nextQueueGoal } from './goal-queue.js';

test('queued implementation waits for completion and review evidence, including repair work', () => {
  const chat = { status: { navigator: 'idle', explorer: 'idle', goalStatus: 'active' } };
  assert.equal(canAdvanceGoal(chat, false), false);
  chat.status.goalStatus = 'complete';
  assert.equal(canAdvanceGoal(chat, false), false);
  chat.status.review = { ready: false };
  assert.equal(canAdvanceGoal(chat, false), false);
  chat.status.review.ready = true;
  chat.status.explorer = 'active';
  assert.equal(canAdvanceGoal(chat, false), false);
  chat.status.explorer = 'idle';
  assert.equal(canAdvanceGoal(chat, true), false);
  assert.equal(canAdvanceGoal(chat, false), true);
  for (const status of ['blocked', 'paused', 'budgetLimited']) {
    chat.status.goalStatus = status;
    assert.equal(canAdvanceGoal(chat, false), false);
  }
});

test('restart restores unfinished persisted work before starting another goal', async () => {
  const unfinished = { shoakuId: 'shoaku-aA1234', checked: true };
  const unstarted = { shoakuId: 'shoaku-bB5678', checked: false };
  const goals = [unstarted, unfinished];
  let restored;
  const hooks = {
    attached: id => restored?.id === id,
    persisted: async id => id === unfinished.shoakuId,
    failed: () => false,
    finished: async id => id === restored?.id && canAdvanceGoal(restored.chat, false)
  };
  assert.deepEqual(await nextQueueGoal(goals, hooks), { goal: unfinished, action: 'resume' });
  restored = { id: unfinished.shoakuId, chat: { status: { goalStatus: 'active' } } };
  assert.deepEqual(await nextQueueGoal(goals, hooks), { goal: unfinished, action: 'wait' });
  restored.chat.status = { goalStatus: 'complete', review: { ready: false } };
  assert.deepEqual(await nextQueueGoal(goals, hooks), { goal: unfinished, action: 'wait' });
  restored.chat.status.review.ready = true;
  assert.deepEqual(await nextQueueGoal(goals, hooks), { goal: unstarted, action: 'start' });
});

test('reopening a review preserves ownership of the unfinished implementation', async () => {
  const implementation = { shoakuId: 'shoaku-aA1234' };
  const review = { shoakuId: 'shoaku-bB5678' };
  const selected = activeGoalId(implementation.shoakuId, review.shoakuId);
  const chats = new Map([
    [implementation.shoakuId, { status: { goalStatus: 'active' } }],
    [review.shoakuId, { mode: 'review', status: { review: { ready: true } } }]
  ]);
  assert.equal(selected, implementation.shoakuId);
  assert.deepEqual(await nextQueueGoal([review, implementation], {
    attached: id => chats.has(id), persisted: async () => true, failed: () => false,
    finished: async id => canAdvanceGoal(chats.get(id), false)
  }), { goal: implementation, action: 'wait' });
});

test('a confirmed missing old workspace does not strand new goals in Waiting', async () => {
  const missing = { shoakuId: 'shoaku-aA1234' };
  const next = { shoakuId: 'shoaku-bB5678', checked: false };
  const unavailable = new Set();
  const hooks = {
    attached: () => false, failed: () => false,
    persisted: async id => id === missing.shoakuId,
    unavailable: id => unavailable.has(id),
    finished: async id => { unavailable.add(id); return false; }
  };
  assert.deepEqual(await nextQueueGoal([missing, next], hooks), { goal: next, action: 'start' });
  assert.deepEqual(await nextQueueGoal([missing, next], hooks), { goal: next, action: 'start' });
});

test('an unresolved persisted session still blocks advancement', async () => {
  const paused = { shoakuId: 'shoaku-aA1234' };
  const next = { shoakuId: 'shoaku-bB5678', checked: false };
  assert.deepEqual(await nextQueueGoal([paused, next], {
    attached: () => false, failed: () => true,
    persisted: async id => id === paused.shoakuId,
    finished: async () => false
  }), { goal: paused, action: 'resume' });
});
