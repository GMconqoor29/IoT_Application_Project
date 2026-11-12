export const localZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
export function time(value, zone, full = true) {
  if (!value) return '—';
  return new Intl.DateTimeFormat(undefined, {
    timeZone: zone,
    ...(full ? { month: 'short', day: 'numeric' } : {}),
    hour: '2-digit',
    minute: '2-digit',
    second: full ? '2-digit' : undefined,
  }).format(new Date(value));
}
export const label = (text) =>
  (text || 'UNKNOWN')
    .toLowerCase()
    .replaceAll('_', ' ')
    .replace(/^./, (c) => c.toUpperCase());
export const number = (value) =>
  value == null || !Number.isFinite(value)
    ? '—'
    : new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(value);
