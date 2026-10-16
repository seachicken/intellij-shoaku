// Keep document positions and UI/session state out of agent requirements.
export function goalRequirements(goal) {
  return {
    goal: goal.content,
    children: goal.children.map(child => ({
      ...goalRequirements(child),
      ...(child.checked !== undefined && { checked: child.checked })
    }))
  };
}

export function requirementSnapshots(goals) {
  return new Map(goals.filter(goal => goal.shoakuId)
    .map(goal => [goal.shoakuId, goalRequirements(goal)]));
}
