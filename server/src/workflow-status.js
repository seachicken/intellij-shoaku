export function workflowStatus(status = {}, hasSession = true) {
  if (!hasSession) return { stage: 'notStarted', detail: 'This goal has not started.' };
  if (status.requiredInput) return { stage: 'needsInput', detail: status.requiredInput };
  const waitingAgent = ['navigator', 'explorer'].find(agent => status[`${agent}Flags`]?.includes('waitingOnUserInput'));
  if (waitingAgent) return { stage: 'needsInput', detail: `${waitingAgent === 'navigator' ? 'Navigator' : 'Explorer'} needs your response. Open the conversation to see the question.` };
  if (['navigator', 'explorer'].some(agent => status[`${agent}Flags`]?.includes('waitingOnApproval'))) {
    return { stage: 'needsApproval', detail: 'An agent is waiting for approval. Open the conversation for details.' };
  }
  if (status.navigator === 'systemError' || status.explorer === 'systemError') return { stage: 'error', detail: 'An agent encountered an error.' };
  if (status.goalStatus === 'complete' && status.review?.ready === false) return { stage: 'reviewPending', detail: status.review.detail };
  const goalStages = { blocked: ['blocked', 'Explorer is blocked. Open the conversation for the reason.'], paused: ['paused', 'Explorer is paused.'], budgetLimited: ['budgetLimited', 'Explorer reached its token budget.'], usageLimited: ['usageLimited', 'Explorer reached its usage limit.'], complete: ['complete', 'Explorer reported the implementation goal complete.'] };
  if (goalStages[status.goalStatus]) {
    const [stage, detail] = goalStages[status.goalStatus];
    return { stage, detail: stage === 'complete' && status.review?.ready ? status.review.detail : detail };
  }
  if (status.goalStatus === 'active' || status.explorer === 'active') return { stage: 'implementing', detail: 'Explorer is implementing and verifying the goal.' };
  if (status.navigator === 'active') return { stage: 'planning', detail: 'Navigator is investigating requirements and preparing the plan.' };
  return { stage: 'idle', detail: 'No agent is currently working. Completion has not been confirmed.' };
}
