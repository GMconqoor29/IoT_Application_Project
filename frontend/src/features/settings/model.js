export function thresholdPayload(draft) {
  const payload = {
    expectedRevision: draft.revision,
    direction: draft.direction,
    enabled: draft.enabled,
  };
  for (const group of ['warning', 'alert', 'recovery']) {
    payload[group] = {};
    for (const side of ['low', 'high']) {
      const active =
        draft.direction === 'RANGE' ||
        (side === 'low' ? draft.direction === 'LOWER' : draft.direction === 'UPPER');
      const raw = draft[group][side];
      if (active && (raw == null || String(raw).trim() === '' || !Number.isFinite(Number(raw))))
        throw new Error(`Enter a finite ${group} ${side} boundary.`);
      payload[group][side] = active ? Number(raw) : null;
    }
  }
  const { warning: w, alert: a, recovery: r } = payload;
  const sequence =
    draft.direction === 'UPPER'
      ? [r.high, w.high, a.high]
      : draft.direction === 'LOWER'
        ? [a.low, w.low, r.low]
        : [a.low, w.low, r.low, r.high, w.high, a.high];
  if (sequence.some((value, i) => i > 0 && value <= sequence[i - 1]))
    throw new Error(
      'Boundaries must be strictly ordered: alert low < warning low < recovery low < recovery high < warning high < alert high (use the applicable side).',
    );
  return payload;
}
