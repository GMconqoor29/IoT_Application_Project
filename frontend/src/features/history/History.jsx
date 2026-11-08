import { useEffect, useState } from 'react';
import ErrorBox from '../../shared/components/ErrorBox.jsx';
import {
  ResponsiveContainer,
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
} from 'recharts';
import { channels, reading } from '../monitoring/model.js';
import { chartRows, validateRange, allMeasurements } from './model.js';
import { label, number, time } from '../../shared/format.js';
import { request } from '../../shared/api/client.js';
import { fixtureHistory, fixtureTime } from '../../fixtures/monitoring.js';
export default function History({ deviceId, demo, zone, preview, interval, onOpen }) {
  const [preset, setPreset] = useState('24');
  const [custom, setCustom] = useState({ from: '', to: '' });
  const makeRange = (hours) => {
    const end = demo ? fixtureTime + 60000 : Date.now();
    return { from: new Date(end - hours * 3600000).toISOString(), to: new Date(end).toISOString() };
  };
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
    setBusy(true);
    setData(null);
    setError(null);
    setStatsError(null);
    async function load() {
      try {
        if (demo) {
          setData(fixtureHistory(range.from, range.to));
          return;
        }
        const query = new URLSearchParams(range);
        const path = `/api/devices/${encodeURIComponent(deviceId)}`;
        const results = await Promise.allSettled([
          allMeasurements(
            (cursor) =>
              request(
                `${path}/measurements?${new URLSearchParams({ ...range, limit: '500', ...(cursor ? { cursor } : {}) })}`,
                { signal: controller.signal },
              ),
            controller.signal,
          ),
          request(`${path}/stats?${query}`, { signal: controller.signal }),
        ]);
        if (controller.signal.aborted) return;
        const [history, stats] = results;
        if (history.status === 'rejected') setError(history.reason);
        if (stats.status === 'rejected') setStatsError(stats.reason);
        setData({
          items: history.status === 'fulfilled' ? history.value : [],
          stats: stats.status === 'fulfilled' ? stats.value : null,
        });
      } catch (e) {
        if (e.name !== 'AbortError') setError(e);
      } finally {
        if (!controller.signal.aborted) setBusy(false);
      }
    }
    load();
    return () => controller.abort();
  }, [deviceId, demo, range, retry]);
  const channel = channels.find((c) => c.key === selected);
  const rows = chartRows(data?.items || [], selected, interval);
  const stats = data?.stats?.parameters[selected];
  const hasValues = rows.some((r) => r.value != null);
  function applyCustom(e) {
    e.preventDefault();
    const message = validateRange(custom.from, custom.to);
    setRangeError(message || '');
    if (!message)
      setRange({
        from: new Date(custom.from).toISOString(),
        to: new Date(custom.to).toISOString(),
      });
  }
  return (
    <section className="panel history-panel">
      <div className="panel-title">
        <div>
          <h2>{preview ? 'A look over time' : 'Measurement history'}</h2>
          <p>Recorded measurements · {zone}</p>
        </div>
        {preview ? (
          <button className="text-button" onClick={onOpen}>
            Explore history <span>→</span>
          </button>
        ) : (
          <button
            onClick={() => {
              if (preset !== 'custom') setRange(makeRange(Number(preset)));
              else setRetry((r) => r + 1);
            }}
          >
            Refresh range ↻
          </button>
        )}
      </div>
      <div className="history-controls">
        <label className="channel-select">
          Channel
          <select value={selected} onChange={(e) => setSelected(e.target.value)}>
            {channels.map((c) => (
              <option key={c.key} value={c.key}>
                {c.label} ({c.unit})
              </option>
            ))}
          </select>
        </label>
        <div className="segmented" aria-label="History time range">
          {[
            ['1', '1 hour'],
            ['24', '24 hours'],
            ['168', '7 days'],
            ['custom', 'Custom'],
          ].map(([v, title]) => (
            <button
              key={v}
              aria-pressed={preset === v}
              className={preset === v ? 'selected' : ''}
              onClick={() => {
                setPreset(v);
                setRangeError('');
                if (v !== 'custom') setRange(makeRange(Number(v)));
              }}
            >
              {title}
            </button>
          ))}
        </div>
      </div>
      {preset === 'custom' && (
        <form className="custom-range" onSubmit={applyCustom}>
          <label>
            From (your device’s local time)
            <input
              type="datetime-local"
              required
              value={custom.from}
              onChange={(e) => setCustom({ ...custom, from: e.target.value })}
            />
          </label>
          <label>
            To (exclusive, local time)
            <input
              type="datetime-local"
              required
              value={custom.to}
              onChange={(e) => setCustom({ ...custom, to: e.target.value })}
            />
          </label>
          <button className="primary" type="submit">
            Apply range
          </button>
          {rangeError && <p role="alert">{rangeError}</p>}
        </form>
      )}
      <p className="range-caption">
        {time(range.from, zone)} — {time(range.to, zone)} · End excluded
      </p>
      <ErrorBox error={error} onRetry={() => setRetry((r) => r + 1)} />
      <ErrorBox error={statsError} onRetry={() => setRetry((r) => r + 1)} />
      {busy ? (
        <div className="chart-empty" role="status">
          Loading measurements and statistics…
        </div>
      ) : !hasValues ? (
        <div className="chart-empty">
          <span>⌁</span>
          <strong>No valid measurements in this range</strong>
          <p>Try another channel or time range. Missing readings are never shown as zero.</p>
        </div>
      ) : (
        <>
          <div className="chart-heading">
            <strong>{channel.label}</strong>
            <span>{channel.unit}</span>
          </div>
          <div
            className="chart"
            role="img"
            aria-label={`${channel.label} chart in ${channel.unit}. ${rows.filter((r) => r.value != null).length} valid plotted samples. Missing data creates gaps.`}
          >
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={rows} margin={{ top: 12, right: 20, bottom: 8, left: 0 }}>
                <CartesianGrid strokeDasharray="3 5" vertical={false} stroke="#e4e9e5" />
                <XAxis
                  dataKey="time"
                  type="number"
                  domain={['dataMin', 'dataMax']}
                  tickFormatter={(v) => time(v, zone, false)}
                  tick={{ fontSize: 11 }}
                  axisLine={false}
                  tickLine={false}
                  minTickGap={45}
                />
                <YAxis
                  domain={['auto', 'auto']}
                  tick={{ fontSize: 11 }}
                  axisLine={false}
                  tickLine={false}
                  width={55}
                />
                <Tooltip
                  labelFormatter={(v) => time(v, zone)}
                  formatter={(v) => [`${number(v)} ${channel.unit}`, channel.label]}
                />
                <Line
                  type="linear"
                  dataKey="value"
                  stroke={channel.color}
                  strokeWidth={2.5}
                  dot={{ r: rows.length > 100 ? 1 : 4, fill: channel.color }}
                  connectNulls={false}
                  isAnimationActive={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
      <div className="stats-grid">
        {[
          ['Minimum', stats?.min],
          ['Maximum', stats?.max],
          ['Average', stats?.mean],
          ['Valid samples', stats?.validCount],
        ].map(([name, value]) => (
          <div key={name}>
            <small>{name}</small>
            <strong>
              {busy ? '…' : number(value)}
              <span>{name !== 'Valid samples' && channel.unit}</span>
            </strong>
          </div>
        ))}
      </div>
      <div className="history-footnote">
        <span>
          {stats?.calibrationId
            ? `Calibration: ${stats.calibrationId}`
            : 'Statistics use valid samples only.'}
        </span>
        <span>{data?.items.length ?? 0} records loaded · Gaps preserved</span>
      </div>
      {!preview && hasValues && (
        <details className="data-table">
          <summary>View accessible measurement table</summary>
          <div className="table-scroll">
            <table>
              <caption>
                {channel.label} · {zone}
              </caption>
              <thead>
                <tr>
                  <th>Measured at</th>
                  <th>Value ({channel.unit})</th>
                  <th>Quality</th>
                  <th>Calibration</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((m) => (
                  <tr key={m.telemetry.messageId}>
                    <td>{time(m.telemetry.measuredAt, zone)}</td>
                    <td>{number(reading(m.telemetry, selected))}</td>
                    <td>{label(m.telemetry.quality[channel.sensor])}</td>
                    <td>
                      {selected === 'gasRelative'
                        ? m.telemetry.calibration.gasId || '—'
                        : selected === 'dustEstimateUgM3'
                          ? m.telemetry.calibration.dustId || '—'
                          : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </section>
  );
}
