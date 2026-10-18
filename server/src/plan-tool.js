export const planTool = {
  type: 'function',
  name: 'shoaku_save_plan',
  description: 'Submit the complete implementation plan, or a required information question, to Shoaku. Shoaku validates and saves the plan and dispatches Explorer. Do not repeat the plan in your final reply.',
  inputSchema: {
    type: 'object',
    properties: { plansMd: { type: 'string' }, requiredInput: { type: 'string' } },
    required: ['plansMd', 'requiredInput'],
    additionalProperties: false
  }
};

export async function runPlanTool(params, { authorized, save }) {
  try {
    if (params.tool !== planTool.name || params.namespace != null || !authorized(params)) {
      throw new Error('Plan submission is only available to the owning Navigator session.');
    }
    const args = params.arguments;
    if (!args || typeof args !== 'object' || Array.isArray(args) ||
        Object.keys(args).some(key => !['plansMd', 'requiredInput'].includes(key)) ||
        typeof args.plansMd !== 'string' || typeof args.requiredInput !== 'string' ||
        (!args.plansMd.trim() && !args.requiredInput.trim())) {
      throw new Error('Provide a nonempty plansMd or a specific requiredInput question. No paths or extra fields are accepted.');
    }
    await save(params, args);
    return { success: true, contentItems: [{ type: 'inputText', text: args.requiredInput.trim() ? 'Question recorded. Explorer was not dispatched.' : 'Plan saved and Explorer handoff processed. Reply with a brief summary.' }] };
  } catch (error) {
    return { success: false, contentItems: [{ type: 'inputText', text: `Plan submission failed: ${error.message}` }] };
  }
}
