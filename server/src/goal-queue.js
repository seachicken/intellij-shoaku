// A goal is ready to hand over only after work and its review evidence finish.
export function canAdvanceGoal(chat, starting) {
  return !starting && chat?.status.navigator !== 'active' && chat?.status.explorer !== 'active' &&
    !chat?.status.requiredInput && (chat?.mode === 'review' || chat?.status.goalStatus === 'complete') &&
    chat?.status.review?.ready === true;
}

// Persisted unfinished work takes priority over every unstarted goal, even
// when its human checkbox is checked. Checkboxes are not acceptance evidence.
export async function nextQueueGoal(goals, { attached, failed, persisted, finished, unavailable = () => false }) {
  for (const goal of goals) {
    if (!goal.shoakuId || unavailable(goal.shoakuId)) continue;
    if (attached(goal.shoakuId)) {
      if (!await finished(goal.shoakuId) && !unavailable(goal.shoakuId)) return { goal, action: 'wait' };
    } else if (await persisted(goal.shoakuId)) {
      if (!await finished(goal.shoakuId) && !unavailable(goal.shoakuId)) return { goal, action: 'resume' };
    }
  }
  for (const goal of goals) {
    if (!goal.shoakuId || unavailable(goal.shoakuId) || goal.checked === true || attached(goal.shoakuId) || failed(goal) ||
        await persisted(goal.shoakuId)) continue;
    return { goal, action: 'start' };
  }
  return null;
}

export function activeGoalId(runningGoalId, openedGoalId) {
  return runningGoalId || openedGoalId;
}
