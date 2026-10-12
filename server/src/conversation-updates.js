// Coalesce updates per goal and wait for the user's conversation to finish.
export class ConversationUpdates {
  constructor(refresh, onError = () => {}, onSettled = () => {}) {
    this.refresh = refresh;
    this.onError = onError;
    this.onSettled = onSettled;
    this.goals = new Map();
  }
  state(id) {
    if (!this.goals.has(id)) this.goals.set(id, { turns: new Set(), pending: [], running: false });
    return this.goals.get(id);
  }
  started(id, turn) { this.state(id).turns.add(turn); }
  enqueue(id, input, turn) {
    const state = this.state(id);
    if (turn) state.turns.add(turn);
    state.pending.push({ input, turn });
    this.flush(id);
  }
  completed(id, turn, status = 'completed') {
    const state = this.state(id);
    state.turns.delete(turn);
    if (status !== 'completed') state.pending = state.pending.filter(item => item.turn !== turn);
    this.flush(id);
  }
  hasActiveTurn(id) { return this.state(id).turns.size > 0; }
  busy(id) {
    const state = this.state(id);
    return state.running || state.turns.size > 0 || state.pending.length > 0;
  }
  async flush(id) {
    const state = this.state(id);
    if (state.running || state.turns.size || !state.pending.length) return;
    state.running = true;
    const input = state.pending.splice(0).map(item => item.input).join('\n\n');
    try { await this.refresh(id, input); }
    catch (error) {
      if (error.code === 'CONVERSATION_BUSY' && state.turns.size) state.pending.unshift({ input });
      else this.onError(id, error);
    }
    finally {
      state.running = false;
      this.flush(id);
      this.onSettled(id);
    }
  }
}
