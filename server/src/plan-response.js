// Structured output applies to the final answer, not commentary messages.
export function parsePlanResponse(item) {
  if (item?.type !== 'agentMessage' || item.phase === 'commentary') return null;
  try {
    const response = JSON.parse(item.text);
    if (!response || typeof response.plansMd !== 'string') return null;
    if (!response.plansMd.trim() && !(typeof response.requiredInput === 'string' && response.requiredInput.trim())) return null;
    return { ...item, plansMd: response.plansMd, ...(typeof response.requiredInput === 'string' ? { requiredInput: response.requiredInput } : {}) };
  } catch {
    return null;
  }
}
