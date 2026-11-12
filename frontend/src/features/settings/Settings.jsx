import { useEffect, useState } from 'react';
import Badge from '../../shared/components/Badge.jsx';
import ErrorBox from '../../shared/components/ErrorBox.jsx';
import { request } from '../../shared/api/client.js';
import { fixtureThresholds } from '../../fixtures/monitoring.js';
import RuleEditor from './RuleEditor.jsx';
export default function Settings({ deviceId, demo, session, setSession }) {
  const [rules, setRules] = useState([]),
    [error, setError] = useState(null),
    [retry, setRetry] = useState(0);
  const [busy, setBusy] = useState(false),
    [loginBusy, setLoginBusy] = useState(false),
    [authError, setAuthError] = useState(null);
  const [credentials, setCredentials] = useState({ username: '', password: '' });
  useEffect(() => {
    const controller = new AbortController();
    setBusy(true);
    setError(null);
    (demo
      ? Promise.resolve({ items: fixtureThresholds })
      : request(`/api/thresholds?${new URLSearchParams({ deviceId })}`, {
          signal: controller.signal,
        })
    )
      .then((result) => {
        if (!controller.signal.aborted) setRules(result.items);
      })
      .catch((e) => {
        if (e.name !== 'AbortError') setError(e);
      })
      .finally(() => {
        if (!controller.signal.aborted) setBusy(false);
      });
    return () => controller.abort();
  }, [deviceId, demo, retry]);
  async function login(e) {
    e.preventDefault();
    setLoginBusy(true);
    setAuthError(null);
    try {
      const result = await request('/api/auth/login', { method: 'POST', body: credentials });
      if (result.role !== 'ADMIN') throw new Error('Administrator access is required.');
      setSession(result);
      setCredentials({ username: '', password: '' });
    } catch (e) {
      setAuthError(e);
      setCredentials((c) => ({ ...c, password: '' }));
    } finally {
      setLoginBusy(false);
    }
  }
  async function logout() {
    setLoginBusy(true);
    setAuthError(null);
    try {
      await request('/api/auth/logout', { method: 'POST', token: session.csrfToken });
      setSession(null);
    } catch (e) {
      if (e.status === 401) setSession(null);
      else setAuthError(e);
    } finally {
      setLoginBusy(false);
    }
  }
  return (
    <div className="settings-layout">
      <section className="panel auth-panel">
        <div className="panel-title">
          <h2>Administrator access</h2>
          <span>⌑</span>
        </div>
        <p>
          Monitoring is public. A verified administrator session is required to save configuration.
        </p>
        {demo ? (
          <div className="notice">
            Fixture rules are read-only. Switch to Live API to sign in and edit.
          </div>
        ) : session ? (
          <>
            <Badge value="ADMIN" />
            <p>
              Signed in as <strong>{session.username}</strong>
            </p>
            <button disabled={loginBusy} onClick={logout}>
              Sign out
            </button>
          </>
        ) : (
          <form onSubmit={login}>
            <label>
              Username
              <input
                autoComplete="username"
                required
                maxLength={128}
                value={credentials.username}
                onChange={(e) => setCredentials({ ...credentials, username: e.target.value })}
              />
            </label>
            <label>
              Password
              <input
                type="password"
                autoComplete="current-password"
                required
                maxLength={256}
                value={credentials.password}
                onChange={(e) => setCredentials({ ...credentials, password: e.target.value })}
              />
            </label>
            <button className="primary" disabled={loginBusy}>
              {loginBusy ? 'Signing in…' : 'Sign in to configure'} <span>→</span>
            </button>
          </form>
        )}
        <ErrorBox error={authError} />
        <p className="muted">
          Session credentials are held in memory. Reloading requires sign-in again.
        </p>
      </section>
      <section className="rules-panel">
        <div className="section-title">
          <h2>Current threshold rules</h2>
          <span>{demo ? 'Synthetic thresholds' : 'Backend-managed conditions'}</span>
        </div>
        <ErrorBox error={error} onRetry={() => setRetry((r) => r + 1)} />
        {busy && <p role="status">Loading rules…</p>}
        {!busy && !error && !rules.length && (
          <div className="panel empty">No threshold rules are provisioned for this device.</div>
        )}
        {rules.map((rule) => (
          <RuleEditor
            key={`${deviceId}:${rule.id}`}
            rule={rule}
            readOnly={demo || !session}
            session={session}
            onExpired={() => setSession(null)}
            onSaved={(updated) =>
              setRules((list) => list.map((r) => (r.id === updated.id ? updated : r)))
            }
            onReload={async (id) => {
              const result = await request(`/api/thresholds?${new URLSearchParams({ deviceId })}`);
              const current = result.items.find((r) => r.id === id);
              if (!current) throw new Error('This rule no longer exists.');
              return current;
            }}
          />
        ))}
      </section>
    </div>
  );
}
