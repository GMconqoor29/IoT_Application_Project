import { useState } from 'react';
import { localZone, time } from '../shared/format.js';
import Badge from '../shared/components/Badge.jsx';
import ErrorBox from '../shared/components/ErrorBox.jsx';
import { scenarios } from '../fixtures/monitoring.js';
import { useMonitoring } from '../features/monitoring/useMonitoring.js';
import Overview from '../features/monitoring/Overview.jsx';
import History from '../features/history/History.jsx';
import Settings from '../features/settings/Settings.jsx';
export default function App() {
  const [view, setView] = useState('Overview');
  const [zone, setZone] = useState(localZone);
  const [session, setSession] = useState(null);
  const {
    mode,
    setMode,
    scenario,
    setScenario,
    devices,
    deviceId,
    setDeviceId,
    latest,
    error,
    loading,
    refreshed,
    setRetry,
    demo,
    currentFreshness,
    currentCondition,
    device,
    telemetry,
    visibleChannels,
  } = useMonitoring();
  function changeMode(value) {
    setMode(value);
  }
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="#overview" onClick={() => setView('Overview')}>
          <span className="brand-mark">
            a<span>•</span>
          </span>
          <span>
            AirSense<small>INDOOR MONITORING</small>
          </span>
        </a>
        <div className="nav-label">WORKSPACE</div>
        <nav aria-label="Main navigation">
          {[
            ['Overview', '◫'],
            ['History', '⌁'],
            ['Settings', '⚙'],
          ].map(([name, icon]) => (
            <button
              className={view === name ? 'nav-item active' : 'nav-item'}
              aria-current={view === name ? 'page' : undefined}
              key={name}
              onClick={() => setView(name)}
            >
              <span>{icon}</span>
              {name}
              {name === 'Overview' && <b>01</b>}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <span className="tiny-orbit">◎</span>
          <strong>
            A little more awareness.
            <br />A better indoor space.
          </strong>
          <p>Understand your environment, one reading at a time.</p>
          <div className="sidebar-foot">
            GROUP 6 <span>IoT & Applications</span>
          </div>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <span>
            Workspace <span className="slash">/</span> <strong>{view}</strong>
          </span>
          <div>
            <span className="public-label">{session ? session.username : 'Public monitoring'}</span>
            <button className="small-button" onClick={() => setView('Settings')}>
              {session ? 'Administrator' : 'Admin sign in'} <span>↗</span>
            </button>
          </div>
        </header>
        <main id="main-content">
          <div className="heading">
            <div>
              <div className="eyebrow">YOUR SPACE, AT A GLANCE</div>
              <h1>
                {view === 'Overview'
                  ? 'Room to breathe.'
                  : view === 'History'
                    ? 'Every reading tells a story.'
                    : 'Make it work for your space.'}
              </h1>
              <p>
                {view === 'Overview'
                  ? 'A clear view of your indoor environment and what needs attention.'
                  : view === 'History'
                    ? 'Explore sensor measurements over time, with the context that matters.'
                    : 'Review monitoring rules and manage your current thresholds.'}
              </p>
            </div>
            <div className="location-pill">
              <span>⌖</span>
              <div>
                <small>MONITORING LOCATION</small>
                <strong>{device?.location || 'No location available'}</strong>
              </div>
            </div>
          </div>
          <div className="toolbar">
            <div className="segmented" aria-label="Data source">
              <button
                className={!demo ? 'selected' : ''}
                aria-pressed={!demo}
                onClick={() => changeMode('live')}
              >
                Live API
              </button>
              <button
                className={demo ? 'selected' : ''}
                aria-pressed={demo}
                onClick={() => changeMode('fixture')}
              >
                Fixture preview
              </button>
            </div>
            <div className="toolbar-controls">
              {demo && (
                <label>
                  Scenario
                  <select value={scenario} onChange={(e) => setScenario(e.target.value)}>
                    {scenarios.map((s) => (
                      <option key={s}>{s}</option>
                    ))}
                  </select>
                </label>
              )}
              <label>
                Device
                <select
                  value={deviceId}
                  onChange={(e) => setDeviceId(e.target.value)}
                  disabled={!devices.length}
                >
                  {!devices.length && <option value="">No devices</option>}
                  {devices.map((d) => (
                    <option key={d.deviceId}>{d.deviceId}</option>
                  ))}
                </select>
              </label>
              <label>
                Timezone
                <select value={zone} onChange={(e) => setZone(e.target.value)}>
                  {[...new Set([localZone, 'Asia/Saigon', 'UTC'])].map((z) => (
                    <option key={z}>{z}</option>
                  ))}
                </select>
              </label>
            </div>
          </div>
          {demo && (
            <div className="notice fixture-notice">
              <span>◇</span>
              <p>
                <strong>Fixture preview</strong> · Synthetic software examples, not live
                measurements or health limits. Status is evaluated at the fixture’s recorded time;
                timestamps are preserved. Preview settings are read-only.
              </p>
            </div>
          )}
          <ErrorBox error={error} onRetry={() => setRetry((r) => r + 1)} />
          {!loading && !devices.length && !error && (
            <div className="empty panel">
              <h2>No registered devices</h2>
              <p>Register a device in the backend to begin monitoring.</p>
            </div>
          )}
          {loading && !latest && (
            <div className="loading panel" role="status">
              Connecting to your monitoring space…
            </div>
          )}
          {device && (
            <>
              <section className="status-strip" aria-label="Monitoring status">
                <div>
                  <Badge value={latest ? (device.online ? 'ONLINE' : 'OFFLINE') : 'UNKNOWN'} />
                  <span className="status-divider" />
                  <span>
                    Data <Badge value={currentFreshness} />
                  </span>
                </div>
                <div>
                  <span>
                    Measured <strong>{time(telemetry?.measuredAt, zone)}</strong>
                  </span>
                  <span>
                    Refreshed <strong>{time(refreshed, zone)}</strong>
                  </span>
                  <span className="poll-label">
                    ↻ {demo ? 'Static fixture' : 'Every 5 seconds'}
                  </span>
                </div>
              </section>
              {view === 'Overview' && (
                <Overview
                  visibleChannels={visibleChannels}
                  telemetry={telemetry}
                  currentFreshness={currentFreshness}
                  currentCondition={currentCondition}
                  latest={latest}
                  device={device}
                />
              )}
              {(view === 'Overview' || view === 'History') && (
                <History
                  key={`${mode}:${deviceId}`}
                  deviceId={deviceId}
                  demo={demo}
                  zone={zone}
                  preview={view === 'Overview'}
                  interval={device.reportIntervalSec}
                  onOpen={() => setView('History')}
                />
              )}
              {view === 'Settings' && (
                <Settings
                  key={`${mode}:${deviceId}`}
                  deviceId={deviceId}
                  demo={demo}
                  session={session}
                  setSession={setSession}
                />
              )}
            </>
          )}
          <footer className="page-footer">
            <span>
              <strong>AirSense</strong> · Thoughtful monitoring for indoor spaces.
            </span>
            <span>Sensor response ≠ certified air quality</span>
          </footer>
        </main>
      </div>
    </div>
  );
}
