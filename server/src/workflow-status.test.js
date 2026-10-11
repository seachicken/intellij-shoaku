import test from 'node:test';
import assert from 'node:assert/strict';
import { workflowStatus } from './workflow-status.js';

test('shows user input and approval waits ahead of agent activity', () => {
  assert.equal(workflowStatus({ navigator: 'active', explorer: 'active', navigatorFlags: ['waitingOnUserInput'] }).stage, 'needsInput');
  assert.equal(workflowStatus({ explorerFlags: ['waitingOnApproval'], goalStatus: 'active' }).stage, 'needsApproval');
});
test('uses authoritative goal states rather than an idle thread or a result document to infer completion', () => {
  for (const state of ['blocked', 'paused', 'budgetLimited', 'usageLimited', 'complete']) {
    assert.equal(workflowStatus({ goalStatus: state }).stage, state);
  }
  assert.equal(workflowStatus({ explorer: 'idle' }).stage, 'idle');
  assert.equal(workflowStatus({ navigator: 'active' }).stage, 'planning');
  assert.equal(workflowStatus({ goalStatus: 'active', explorer: 'idle' }).stage, 'implementing');
  assert.equal(workflowStatus({ explorer: 'systemError' }).stage, 'error');
  assert.equal(workflowStatus({}, false).stage, 'notStarted');
});

test('shows the actual requested information even while an agent is active', () => {
  assert.deepEqual(workflowStatus({ requiredInput: 'Which storage backend?', navigator: 'active' }), { stage: 'needsInput', detail: 'Which storage backend?' });
});

test('does not label an Explorer-complete goal complete until its commits are reviewable', () => {
  assert.deepEqual(workflowStatus({ goalStatus: 'complete', review: { ready: false, detail: 'Files remain uncommitted.' } }),
    { stage: 'reviewPending', detail: 'Files remain uncommitted.' });
  assert.equal(workflowStatus({ goalStatus: 'complete', review: { ready: true, detail: '2 commits ready.' } }).detail, '2 commits ready.');
});
