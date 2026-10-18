// Each goal owns its session and workspace. Unfinished or blocked sessions
// never reserve the execution slot of another goal.
export async function nextQueueGoal(goals, {
  attached, starting = () => false, failed, persisted, unavailable = () => false
}) {
  for (const goal of goals) {
    const id = goal.shoakuId;
    const reserved = () => attached(id) || starting(id) || unavailable(id) || failed(goal);
    if (!id || reserved()) continue;
    const exists = await persisted(id);
    // A manual start may have reserved this goal during the filesystem read.
    if (reserved()) continue;
    if (exists) return { goal, action: 'resume' };
    if (goal.checked !== true) return { goal, action: 'start' };
  }
  return null;
}
