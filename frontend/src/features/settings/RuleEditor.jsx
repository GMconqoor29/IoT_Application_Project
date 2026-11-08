import { useState } from 'react';
import Badge from '../../shared/components/Badge.jsx';
import ErrorBox from '../../shared/components/ErrorBox.jsx';
import { channels } from '../monitoring/model.js';
import { thresholdPayload } from './model.js';
import { label } from '../../shared/format.js';
import { request } from '../../shared/api/client.js';
export default function RuleEditor({ rule, readOnly, session, onExpired, onSaved, onReload }) {
  const [draft, setDraft] = useState(() => structuredClone(rule));
  const [error, setError] = useState(null),
    [message, setMessage] = useState(''),
    [busy, setBusy] = useState(false),
    [validation, setValidation] = useState('');
  const channel = channels.find((c) => c.key === rule.parameter);
  function update(group, side, value) {
    setDraft((d) => ({ ...d, [group]: { ...d[group], [side]: value } }));
    setMessage('');
  }
  async function save(e) {
    e.preventDefault();
    setValidation('');
    setError(null);
    setMessage('');
    let payload;
    try {
      payload = thresholdPayload(draft);
    } catch (e) {
      setValidation(e.message);
      return;
    }
    setBusy(true);
    try {
      const updated = await request(`/api/thresholds/${encodeURIComponent(rule.id)}`, {
        method: 'PUT',
        body: payload,
        token: session.csrfToken,
      });
      setDraft(structuredClone(updated));
      onSaved(updated);
      setMessage('Threshold saved. The backend will apply the updated rule.');
    } catch (e) {
      setError(e);
      if (e.status === 401) onExpired();
    } finally {
      setBusy(false);
    }
  }
  async function reload() {
    setBusy(true);
    try {
      const current = await onReload(rule.id);
      setDraft(structuredClone(current));
      onSaved(current);
      setError(null);
      setMessage('Loaded current server settings. Review before saving.');
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <article className="panel rule-card">
      <div className="panel-title">
        <h3>{channel.label}</h3>
        <Badge value={draft.enabled ? 'ENABLED' : 'DISABLED'} />
      </div>
      <p className="muted">
        {rule.id} · Revision {draft.revision} · {channel.unit}
        <br />
        Calibration / profile: {rule.calibrationId || 'Not required'}
      </p>
      <form onSubmit={save}>
        <fieldset disabled={readOnly || busy}>
          <label>
            Rule direction
            <select
              value={draft.direction}
              onChange={(e) => {
                setDraft({ ...draft, direction: e.target.value });
                setMessage('');
              }}
            >
              <option value="UPPER">Upper boundary</option>
              <option value="LOWER">Lower boundary</option>
              <option value="RANGE">Bounded range</option>
            </select>
          </label>
          <div className="boundary-grid">
            {['warning', 'alert', 'recovery'].map((group) => (
              <div key={group}>
                <h4>{label(group)}</h4>
                {['low', 'high']
                  .filter(
                    (side) =>
                      draft.direction === 'RANGE' ||
                      (side === 'low' ? draft.direction === 'LOWER' : draft.direction === 'UPPER'),
                  )
                  .map((side) => (
                    <label key={side}>
                      {label(side)} ({channel.unit})
                      <input
                        type="number"
                        step="any"
                        required
                        value={draft[group][side] ?? ''}
                        onChange={(e) => update(group, side, e.target.value)}
                      />
                    </label>
                  ))}
              </div>
            ))}
          </div>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={draft.enabled}
              onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })}
            />
            Rule enabled
          </label>
          <button className="primary" type="submit">
            {busy ? 'Saving…' : 'Save threshold'}
          </button>
        </fieldset>
        {readOnly && (
          <p className="muted">Read-only · administrator sign-in required for changes.</p>
        )}
        {validation && (
          <p className="validation" role="alert">
            {validation}
          </p>
        )}
        <ErrorBox error={error} />
        {error?.status === 409 && (
          <div className="conflict">
            <p>
              Your draft has been kept. Load the latest server settings to replace this draft, then
              review and save again.
            </p>
            <button type="button" disabled={busy} onClick={reload}>
              Replace draft with server settings
            </button>
          </div>
        )}
        {message && (
          <p className="success-message" role="status">
            {message}
          </p>
        )}
      </form>
    </article>
  );
}
