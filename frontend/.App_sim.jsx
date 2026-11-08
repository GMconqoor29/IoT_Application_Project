import React, { useEffect, useState } from 'react';
import { ResponsiveContainer, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip } from 'recharts';
import { channels, label, number, reading, freshness, condition, chartRows, validateRange, thresholdPayload, allMeasurements } from './model';
import { request, fixtureLatest, fixtureHistory, fixtureThresholds, fixtureTime, scenarios } from './api';

const localZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
function time(value, zone, full = true) {
  if (!value) return '—';
  return new Intl.DateTimeFormat(undefined, { timeZone: zone, ...(full ? { month: 'short', day: 'numeric' } : {}), hour: '2-digit', minute: '2-digit', second: full ? '2-digit' : undefined }).format(new Date(value));
}
function Badge({ value }) { return <span className={`badge ${String(value).toLowerCase().replaceAll(' ', '-')}`}><i />{label(value)}</span>; }
function ErrorBox({ error, onRetry }) { return error && <div className="notice error" role="alert"><div><strong>{error.code === 'CALIBRATION_CONFLICT' ? 'Choose a narrower interval' : 'Request needs attention'}</strong><p>{error.message}</p>{error.details?.map(d => <p key={d.field}>{d.field}: {d.reason}</p>)}</div>{onRetry && <button onClick={onRetry}>Retry</button>}</div>; }

export default function App() {
  const [mode, setMode] = useState('live');
  const [view, setView] = useState('Overview');
  const [scenario, setScenario] = useState('Normal');
  const [zone, setZone] = useState(localZone);
  const [devices, setDevices] = useState([{ deviceId: "AIRSENSE-001", location: "SIMULATOR", online: false, freshness: "UNKNOWN", reportIntervalSec: 30 }]);
  const [deviceId, setDeviceId] = useState("AIRSENSE-001");
  const [latest, setLatest] = useState({ device: { deviceId: "AIRSENSE-001", location: "SIMULATOR", online: false, freshness: "UNKNOWN", reportIntervalSec: 30 }, measurement: null, condition: "UNKNOWN", reasons: [], recommendations: [] });
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshed, setRefreshed] = useState(null);
  const [now, setNow] = useState(Date.now());
  const [retry, setRetry] = useState(0);
  const [session, setSession] = useState(null);
  const demo = mode === 'fixture';
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  useEffect(() => {
    const controller = new AbortController();
    setDevices([]); setDeviceId(''); setLatest(null); setRefreshed(null); setError(null); setLoading(true);
    if (demo) { const data = fixtureLatest('Normal'); setDevices([data.device]); setDeviceId(data.device.deviceId); return () => controller.abort(); }
    request('/api/devices', { signal: controller.signal }).then(data => { setDevices(data.items); setDeviceId(data.items[0]?.deviceId || ''); setLoading(false); }).catch(e => { if (e.name !== 'AbortError') { setError(e); setLoading(false); } });
    return () => controller.abort();
  }, [mode, retry]);
  useEffect(() => {
    if (!deviceId) return;
    const controller = new AbortController(); let timer;
    setLatest(null); setLoading(true); setError(null); setRefreshed(null);
    async function poll() {
      try {
        const data = demo ? fixtureLatest(scenario) : await request(`/api/devices/${encodeURIComponent(deviceId)}/latest`, { signal: controller.signal });
        if (controller.signal.aborted) return;
        setLatest(data); setError(null); setRefreshed(Date.now());
      } catch (e) { if (e.name !== 'AbortError') setError(e); }
      finally { if (!controller.signal.aborted) { setLoading(false); if (!demo) timer = setTimeout(poll, 5000); } }
    }
    poll(); return () => { controller.abort(); clearTimeout(timer); };
  }, [deviceId, demo, scenario, retry]);
  const clock = demo ? fixtureTime + 1000 : now;
  const currentFreshness = freshness(latest, clock);
  const currentCondition = condition(latest, clock);
  const device = latest?.device || devices.find(d => d.deviceId === deviceId);
  const telemetry = latest?.measurement?.telemetry;
  const visibleChannels = channels.filter(c => c.key !== (telemetry?.values.dustEstimateUgM3 != null ? 'dustAdcMv' : 'dustEstimateUgM3'));
  function changeMode(value) { setMode(value); }
  return <div className="app-shell">
    <aside className="sidebar">
      <a className="brand" href="#overview" onClick={() => setView('Overview')}><span className="brand-mark">a<span>•</span></span><span>AirSense<small>INDOOR MONITORING</small></span></a>
      <div className="nav-label">WORKSPACE</div>
      <nav aria-label="Main navigation">{[['Overview', '◫'], ['History', '⌁'], ['Settings', '⚙']].map(([name, icon]) => <button className={view === name ? 'nav-item active' : 'nav-item'} aria-current={view === name ? 'page' : undefined} key={name} onClick={() => setView(name)}><span>{icon}</span>{name}{name === 'Overview' && <b>01</b>}</button>)}</nav>
      <div className="sidebar-bottom"><span className="tiny-orbit">◎</span><strong>A little more awareness.<br/>A better indoor space.</strong><p>Understand your environment, one reading at a time.</p><div className="sidebar-foot">GROUP 6 <span>IoT & Applications</span></div></div>
    </aside>
    <div className="workspace">
      <header className="topbar"><span>Workspace <span className="slash">/</span> <strong>{view}</strong></span><div><span className="public-label">{session ? session.username : 'Public monitoring'}</span><button className="small-button" onClick={() => setView('Settings')}>{session ? 'Administrator' : 'Admin sign in'} <span>↗</span></button></div></header>
      <main id="main-content">
        <div className="heading"><div><div className="eyebrow">YOUR SPACE, AT A GLANCE</div><h1>{view === 'Overview' ? 'Room to breathe.' : view === 'History' ? 'Every reading tells a story.' : 'Make it work for your space.'}</h1><p>{view === 'Overview' ? 'A clear view of your indoor environment and what needs attention.' : view === 'History' ? 'Explore sensor measurements over time, with the context that matters.' : 'Review monitoring rules and manage your current thresholds.'}</p></div><div className="location-pill"><span>⌖</span><div><small>MONITORING LOCATION</small><strong>{device?.location || 'No location available'}</strong></div></div></div>
        <div className="toolbar"><div className="segmented" aria-label="Data source"><button className={!demo ? 'selected' : ''} aria-pressed={!demo} onClick={() => changeMode('live')}>Live API</button><button className={demo ? 'selected' : ''} aria-pressed={demo} onClick={() => changeMode('fixture')}>Fixture preview</button></div><div className="toolbar-controls">{demo && <label>Scenario<select value={scenario} onChange={e => setScenario(e.target.value)}>{scenarios.map(s => <option key={s}>{s}</option>)}</select></label>}<label>Device<select value={deviceId} onChange={e => setDeviceId(e.target.value)} disabled={!devices.length}>{!devices.length && <option value="">No devices</option>}{devices.map(d => <option key={d.deviceId}>{d.deviceId}</option>)}</select></label><label>Timezone<select value={zone} onChange={e => setZone(e.target.value)}>{[...new Set([localZone, 'Asia/Saigon', 'UTC'])].map(z => <option key={z}>{z}</option>)}</select></label></div></div>
        {demo && <div className="notice fixture-notice"><span>◇</span><p><strong>Fixture preview</strong> · Synthetic software examples, not live measurements or health limits. Status is evaluated at the fixture’s recorded time; timestamps are preserved. Preview settings are read-only.</p></div>}
        <ErrorBox error={error} onRetry={() => setRetry(r => r + 1)} />
        {!loading && !devices.length && !error && <div className="empty panel"><h2>No registered devices</h2><p>Register a device in the backend to begin monitoring.</p></div>}
        {loading && !latest && <div className="loading panel" role="status">Connecting to your monitoring space…</div>}
        {device && <>
          <section className="status-strip" aria-label="Monitoring status"><div><Badge value={latest ? (device.online ? 'ONLINE' : 'OFFLINE') : 'UNKNOWN'} /><span className="status-divider"/><span>Data <Badge value={currentFreshness}/></span></div><div><span>Measured <strong>{time(telemetry?.measuredAt, zone)}</strong></span><span>Refreshed <strong>{time(refreshed, zone)}</strong></span><span className="poll-label">↻ {demo ? 'Static fixture' : 'Every 5 seconds'}</span></div></section>
          {view === 'Overview' && <>
            <div className="section-title"><h2>Current readings</h2><span>Four channels. One connected space.</span></div>
            <section className="reading-grid" aria-label="Current sensor readings">{visibleChannels.map(c => <article className="reading-card" key={c.key} style={{ '--channel-color': c.color }}><div className="reading-top"><span className="sensor-icon">{c.icon}</span><span>{c.model}</span></div><h3>{c.label}</h3><div className="reading-value">{number(reading(telemetry, c.key))}<span>{c.unit}</span></div><div className="reading-footer"><Badge value={telemetry?.quality[c.sensor] || 'UNAVAILABLE'} /><span>{currentFreshness === 'FRESH' ? 'Latest sample' : label(currentFreshness)}</span></div><p className="reading-note">{c.key === 'gasRelative' ? `Recorded baseline: ${telemetry?.calibration.gasId || 'not available'}` : c.key === 'dustEstimateUgM3' ? `Conversion: ${telemetry?.calibration.dustId || 'not available'}` : c.key === 'dustAdcMv' ? 'Raw response · no calibrated dust estimate' : 'Quality applies to this measurement'}</p></article>)}</section>
            <section className="insight-grid"><article className="panel conditions"><div className="panel-title"><h2>Current conditions</h2><Badge value={currentCondition}/></div>{currentFreshness !== 'FRESH' ? <><h3>Waiting for a fresh picture</h3><p>Readings are {label(currentFreshness).toLowerCase()}. Current conditions cannot be confirmed. Data loss does not indicate recovery.</p></> : <><h3>{currentCondition === 'NORMAL' ? 'Within configured boundaries' : currentCondition === 'UNKNOWN' ? 'Some conditions are unknown' : 'Your space needs attention'}</h3>{latest?.reasons.length ? <ul>{latest.reasons.map(r => <li key={r}>{r}</li>)}</ul> : <p>{currentCondition === 'NORMAL' ? 'The backend reports no current threshold violations. Check individual sensor quality for unavailable channels.' : 'Review sensor quality and calibration before interpreting the readings.'}</p>}</>}<div className="subtle-meta">Location: {device.location} · {device.deviceId}</div></article><article className="panel recommendations"><div className="panel-title"><h2>Recommended next steps</h2><span className="accent-icon">↗</span></div>{currentFreshness !== 'FRESH' ? <p>Wait for fresh valid measurements before relying on environmental advice.</p> : latest?.recommendations.some(r => r.status !== 'WITHDRAWN') ? latest.recommendations.filter(r => r.status !== 'WITHDRAWN').map(r => <div className="recommendation" key={r.id}><Badge value={r.status}/><p>{r.message}</p><small>Based on: {r.measurementIds.join(', ') || 'No measurement reference supplied'}</small></div>) : <><h3>Keep an eye on your space</h3><p>No current recommendation was returned by the backend. Continue monitoring for changes.</p></>}<p className="sensor-caveat">MQ-135 measures a general gas response, not CO₂ concentration. Dust readings are experimental and do not establish official AQI.</p></article></section>
          </>}
          {(view === 'Overview' || view === 'History') && <History key={`${mode}:${deviceId}`} deviceId={deviceId} demo={demo} zone={zone} preview={view === 'Overview'} interval={device.reportIntervalSec} onOpen={() => setView('History')}/>}
          {view === 'Settings' && <Settings key={`${mode}:${deviceId}`} deviceId={deviceId} demo={demo} session={session} setSession={setSession}/>}
        </>}
        <footer className="page-footer"><span><strong>AirSense</strong> · Thoughtful monitoring for indoor spaces.</span><span>Sensor response ≠ certified air quality</span></footer>
      </main>
    </div>
  </div>;
}

function History({ deviceId, demo, zone, preview, interval, onOpen }) {
  const [preset, setPreset] = useState('24');
  const [custom, setCustom] = useState({ from: '', to: '' });
  const makeRange = hours => { const end = demo ? fixtureTime + 60000 : Date.now(); return { from: new Date(end - hours * 3600000).toISOString(), to: new Date(end).toISOString() }; };
  const [range, setRange] = useState(() => makeRange(24));
  const [selected, setSelected] = useState('temperatureC');
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [statsError, setStatsError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(0);
  const [rangeError, setRangeError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    setBusy(true); setData(null); setError(null); setStatsError(null);
    async function load() {
      try {
        if (demo) { setData(fixtureHistory(range.from, range.to)); return; }
        const query = new URLSearchParams(range);
        const path = `/api/devices/${encodeURIComponent(deviceId)}`;
        const results = await Promise.allSettled([
          allMeasurements(cursor => request(`${path}/measurements?${new URLSearchParams({ ...range, limit: '500', ...(cursor ? { cursor } : {}) })}`, { signal: controller.signal }), controller.signal),
          request(`${path}/stats?${query}`, { signal: controller.signal }),
        ]);
        if (controller.signal.aborted) return;
        const [history, stats] = results;
        if (history.status === 'rejected') setError(history.reason);
        if (stats.status === 'rejected') setStatsError(stats.reason);
        setData({ items: history.status === 'fulfilled' ? history.value : [], stats: stats.status === 'fulfilled' ? stats.value : null });
      } catch (e) { if (e.name !== 'AbortError') setError(e); }
      finally { if (!controller.signal.aborted) setBusy(false); }
    }
    load(); return () => controller.abort();
  }, [deviceId, demo, range, retry]);
  const channel = channels.find(c => c.key === selected);
  const rows = chartRows(data?.items || [], selected, interval);
  const stats = data?.stats?.parameters[selected];
  const hasValues = rows.some(r => r.value != null);
  function applyCustom(e) {
    e.preventDefault();
    const message = validateRange(custom.from, custom.to); setRangeError(message || '');
    if (!message) setRange({ from: new Date(custom.from).toISOString(), to: new Date(custom.to).toISOString() });
  }
  return <section className="panel history-panel"><div className="panel-title"><div><h2>{preview ? 'A look over time' : 'Measurement history'}</h2><p>Recorded measurements · {zone}</p></div>{preview ? <button className="text-button" onClick={onOpen}>Explore history <span>→</span></button> : <button onClick={() => { if (preset !== 'custom') setRange(makeRange(Number(preset))); else setRetry(r => r + 1); }}>Refresh range ↻</button>}</div>
    <div className="history-controls"><label className="channel-select">Channel<select value={selected} onChange={e => setSelected(e.target.value)}>{channels.map(c => <option key={c.key} value={c.key}>{c.label} ({c.unit})</option>)}</select></label><div className="segmented" aria-label="History time range">{[['1', '1 hour'], ['24', '24 hours'], ['168', '7 days'], ['custom', 'Custom']].map(([v, title]) => <button key={v} aria-pressed={preset === v} className={preset === v ? 'selected' : ''} onClick={() => { setPreset(v); setRangeError(''); if (v !== 'custom') setRange(makeRange(Number(v))); }}>{title}</button>)}</div></div>
    {preset === 'custom' && <form className="custom-range" onSubmit={applyCustom}><label>From (your device’s local time)<input type="datetime-local" required value={custom.from} onChange={e => setCustom({ ...custom, from: e.target.value })}/></label><label>To (exclusive, local time)<input type="datetime-local" required value={custom.to} onChange={e => setCustom({ ...custom, to: e.target.value })}/></label><button className="primary" type="submit">Apply range</button>{rangeError && <p role="alert">{rangeError}</p>}</form>}
    <p className="range-caption">{time(range.from, zone)} — {time(range.to, zone)} · End excluded</p>
    <ErrorBox error={error} onRetry={() => setRetry(r => r + 1)}/><ErrorBox error={statsError} onRetry={() => setRetry(r => r + 1)}/>
    {busy ? <div className="chart-empty" role="status">Loading measurements and statistics…</div> : !hasValues ? <div className="chart-empty"><span>⌁</span><strong>No valid measurements in this range</strong><p>Try another channel or time range. Missing readings are never shown as zero.</p></div> : <><div className="chart-heading"><strong>{channel.label}</strong><span>{channel.unit}</span></div><div className="chart" role="img" aria-label={`${channel.label} chart in ${channel.unit}. ${rows.filter(r => r.value != null).length} valid plotted samples. Missing data creates gaps.`}><ResponsiveContainer width="100%" height="100%"><LineChart data={rows} margin={{ top: 12, right: 20, bottom: 8, left: 0 }}><CartesianGrid strokeDasharray="3 5" vertical={false} stroke="#e4e9e5"/><XAxis dataKey="time" type="number" domain={['dataMin', 'dataMax']} tickFormatter={v => time(v, zone, false)} tick={{ fontSize: 11 }} axisLine={false} tickLine={false} minTickGap={45}/><YAxis domain={['auto', 'auto']} tick={{ fontSize: 11 }} axisLine={false} tickLine={false} width={55}/><Tooltip labelFormatter={v => time(v, zone)} formatter={v => [`${number(v)} ${channel.unit}`, channel.label]}/><Line type="linear" dataKey="value" stroke={channel.color} strokeWidth={2.5} dot={{ r: rows.length > 100 ? 1 : 4, fill: channel.color }} connectNulls={false} isAnimationActive={false}/></LineChart></ResponsiveContainer></div></>}
    <div className="stats-grid">{[['Minimum', stats?.min], ['Maximum', stats?.max], ['Average', stats?.mean], ['Valid samples', stats?.validCount]].map(([name, value]) => <div key={name}><small>{name}</small><strong>{busy ? '…' : number(value)}<span>{name !== 'Valid samples' && channel.unit}</span></strong></div>)}</div>
    <div className="history-footnote"><span>{stats?.calibrationId ? `Calibration: ${stats.calibrationId}` : 'Statistics use valid samples only.'}</span><span>{data?.items.length ?? 0} records loaded · Gaps preserved</span></div>
    {!preview && hasValues && <details className="data-table"><summary>View accessible measurement table</summary><div className="table-scroll"><table><caption>{channel.label} · {zone}</caption><thead><tr><th>Measured at</th><th>Value ({channel.unit})</th><th>Quality</th><th>Calibration</th></tr></thead><tbody>{data.items.map(m => <tr key={m.telemetry.messageId}><td>{time(m.telemetry.measuredAt, zone)}</td><td>{number(reading(m.telemetry, selected))}</td><td>{label(m.telemetry.quality[channel.sensor])}</td><td>{selected === 'gasRelative' ? m.telemetry.calibration.gasId || '—' : selected === 'dustEstimateUgM3' ? m.telemetry.calibration.dustId || '—' : '—'}</td></tr>)}</tbody></table></div></details>}
  </section>;
}

function Settings({ deviceId, demo, session, setSession }) {
  const [rules, setRules] = useState([]), [error, setError] = useState(null), [retry, setRetry] = useState(0);
  const [busy, setBusy] = useState(false), [loginBusy, setLoginBusy] = useState(false), [authError, setAuthError] = useState(null);
  const [credentials, setCredentials] = useState({ username: '', password: '' });
  useEffect(() => {
    const controller = new AbortController(); setBusy(true); setError(null);
    (demo ? Promise.resolve({ items: fixtureThresholds }) : request(`/api/thresholds?${new URLSearchParams({ deviceId })}`, { signal: controller.signal }))
      .then(result => { if (!controller.signal.aborted) setRules(result.items); }).catch(e => { if (e.name !== 'AbortError') setError(e); }).finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [deviceId, demo, retry]);
  async function login(e) {
    e.preventDefault(); setLoginBusy(true); setAuthError(null);
    try { const result = await request('/api/auth/login', { method: 'POST', body: credentials }); if (result.role !== 'ADMIN') throw new Error('Administrator access is required.'); setSession(result); setCredentials({ username: '', password: '' }); }
    catch(e) { setAuthError(e); setCredentials(c => ({ ...c, password: '' })); } finally { setLoginBusy(false); }
  }
  async function logout() {
    setLoginBusy(true); setAuthError(null);
    try { await request('/api/auth/logout', { method: 'POST', token: session.csrfToken }); setSession(null); }
    catch(e) { if (e.status === 401) setSession(null); else setAuthError(e); } finally { setLoginBusy(false); }
  }
  return <div className="settings-layout"><section className="panel auth-panel"><div className="panel-title"><h2>Administrator access</h2><span>⌑</span></div><p>Monitoring is public. A verified administrator session is required to save configuration.</p>{demo ? <div className="notice">Fixture rules are read-only. Switch to Live API to sign in and edit.</div> : session ? <><Badge value="ADMIN"/><p>Signed in as <strong>{session.username}</strong></p><button disabled={loginBusy} onClick={logout}>Sign out</button></> : <form onSubmit={login}><label>Username<input autoComplete="username" required maxLength={128} value={credentials.username} onChange={e => setCredentials({ ...credentials, username: e.target.value })}/></label><label>Password<input type="password" autoComplete="current-password" required maxLength={256} value={credentials.password} onChange={e => setCredentials({ ...credentials, password: e.target.value })}/></label><button className="primary" disabled={loginBusy}>{loginBusy ? 'Signing in…' : 'Sign in to configure'} <span>→</span></button></form>}<ErrorBox error={authError}/><p className="muted">Session credentials are held in memory. Reloading requires sign-in again.</p></section><section className="rules-panel"><div className="section-title"><h2>Current threshold rules</h2><span>{demo ? 'Synthetic thresholds' : 'Backend-managed conditions'}</span></div><ErrorBox error={error} onRetry={() => setRetry(r => r + 1)}/>{busy && <p role="status">Loading rules…</p>}{!busy && !error && !rules.length && <div className="panel empty">No threshold rules are provisioned for this device.</div>}{rules.map(rule => <RuleEditor key={`${deviceId}:${rule.id}`} rule={rule} readOnly={demo || !session} session={session} onExpired={() => setSession(null)} onSaved={updated => setRules(list => list.map(r => r.id === updated.id ? updated : r))} onReload={async id => { const result = await request(`/api/thresholds?${new URLSearchParams({ deviceId })}`); const current = result.items.find(r => r.id === id); if (!current) throw new Error('This rule no longer exists.'); return current; }}/>)}</section></div>;
}

function RuleEditor({ rule, readOnly, session, onExpired, onSaved, onReload }) {
  const [draft, setDraft] = useState(() => structuredClone(rule));
  const [error, setError] = useState(null), [message, setMessage] = useState(''), [busy, setBusy] = useState(false), [validation, setValidation] = useState('');
  const channel = channels.find(c => c.key === rule.parameter);
  function update(group, side, value) { setDraft(d => ({ ...d, [group]: { ...d[group], [side]: value } })); setMessage(''); }
  async function save(e) {
    e.preventDefault(); setValidation(''); setError(null); setMessage('');
    let payload; try { payload = thresholdPayload(draft); } catch(e) { setValidation(e.message); return; }
    setBusy(true);
    try { const updated = await request(`/api/thresholds/${encodeURIComponent(rule.id)}`, { method: 'PUT', body: payload, token: session.csrfToken }); setDraft(structuredClone(updated)); onSaved(updated); setMessage('Threshold saved. The backend will apply the updated rule.'); }
    catch(e) { setError(e); if (e.status === 401) onExpired(); } finally { setBusy(false); }
  }
  async function reload() {
    setBusy(true);
    try { const current = await onReload(rule.id); setDraft(structuredClone(current)); onSaved(current); setError(null); setMessage('Loaded current server settings. Review before saving.'); } catch(e) { setError(e); } finally { setBusy(false); }
  }
  return <article className="panel rule-card"><div className="panel-title"><h3>{channel.label}</h3><Badge value={draft.enabled ? 'ENABLED' : 'DISABLED'}/></div><p className="muted">{rule.id} · Revision {draft.revision} · {channel.unit}<br/>Calibration / profile: {rule.calibrationId || 'Not required'}</p><form onSubmit={save}><fieldset disabled={readOnly || busy}><label>Rule direction<select value={draft.direction} onChange={e => { setDraft({ ...draft, direction: e.target.value }); setMessage(''); }}><option value="UPPER">Upper boundary</option><option value="LOWER">Lower boundary</option><option value="RANGE">Bounded range</option></select></label><div className="boundary-grid">{['warning', 'alert', 'recovery'].map(group => <div key={group}><h4>{label(group)}</h4>{['low', 'high'].filter(side => draft.direction === 'RANGE' || (side === 'low' ? draft.direction === 'LOWER' : draft.direction === 'UPPER')).map(side => <label key={side}>{label(side)} ({channel.unit})<input type="number" step="any" required value={draft[group][side] ?? ''} onChange={e => update(group, side, e.target.value)}/></label>)}</div>)}</div><label className="checkbox"><input type="checkbox" checked={draft.enabled} onChange={e => setDraft({ ...draft, enabled: e.target.checked })}/>Rule enabled</label><button className="primary" type="submit">{busy ? 'Saving…' : 'Save threshold'}</button></fieldset>{readOnly && <p className="muted">Read-only · administrator sign-in required for changes.</p>}{validation && <p className="validation" role="alert">{validation}</p>}<ErrorBox error={error}/>{error?.status === 409 && <div className="conflict"><p>Your draft has been kept. Load the latest server settings to replace this draft, then review and save again.</p><button type="button" disabled={busy} onClick={reload}>Replace draft with server settings</button></div>}{message && <p className="success-message" role="status">{message}</p>}</form></article>;
}
