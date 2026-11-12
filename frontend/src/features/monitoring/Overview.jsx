import Badge from '../../shared/components/Badge.jsx';
import { label, number } from '../../shared/format.js';
import { reading } from './model.js';
export default function Overview({
  visibleChannels,
  telemetry,
  currentFreshness,
  currentCondition,
  latest,
  device,
}) {
  return (
    <>
      <div className="section-title">
        <h2>Current readings</h2>
        <span>Four channels. One connected space.</span>
      </div>
      <section className="reading-grid" aria-label="Current sensor readings">
        {visibleChannels.map((c) => (
          <article className="reading-card" key={c.key} style={{ '--channel-color': c.color }}>
            <div className="reading-top">
              <span className="sensor-icon">{c.icon}</span>
              <span>{c.model}</span>
            </div>
            <h3>{c.label}</h3>
            <div className="reading-value">
              {number(reading(telemetry, c.key))}
              <span>{c.unit}</span>
            </div>
            <div className="reading-footer">
              <Badge value={telemetry?.quality[c.sensor] || 'UNAVAILABLE'} />
              <span>
                {currentFreshness === 'FRESH' ? 'Latest sample' : label(currentFreshness)}
              </span>
            </div>
            <p className="reading-note">
              {c.key === 'gasRelative'
                ? `Recorded baseline: ${telemetry?.calibration.gasId || 'not available'}`
                : c.key === 'dustEstimateUgM3'
                  ? `Conversion: ${telemetry?.calibration.dustId || 'not available'}`
                  : c.key === 'dustAdcMv'
                    ? 'Raw response · no calibrated dust estimate'
                    : 'Quality applies to this measurement'}
            </p>
          </article>
        ))}
      </section>
      <section className="insight-grid">
        <article className="panel conditions">
          <div className="panel-title">
            <h2>Current conditions</h2>
            <Badge value={currentCondition} />
          </div>
          {currentFreshness !== 'FRESH' ? (
            <>
              <h3>Waiting for a fresh picture</h3>
              <p>
                Readings are {label(currentFreshness).toLowerCase()}. Current conditions cannot be
                confirmed. Data loss does not indicate recovery.
              </p>
            </>
          ) : (
            <>
              <h3>
                {currentCondition === 'NORMAL'
                  ? 'Within configured boundaries'
                  : currentCondition === 'UNKNOWN'
                    ? 'Some conditions are unknown'
                    : 'Your space needs attention'}
              </h3>
              {latest?.reasons.length ? (
                <ul>
                  {latest.reasons.map((r) => (
                    <li key={r}>{r}</li>
                  ))}
                </ul>
              ) : (
                <p>
                  {currentCondition === 'NORMAL'
                    ? 'The backend reports no current threshold violations. Check individual sensor quality for unavailable channels.'
                    : 'Review sensor quality and calibration before interpreting the readings.'}
                </p>
              )}
            </>
          )}
          <div className="subtle-meta">
            Location: {device.location} · {device.deviceId}
          </div>
        </article>
        <article className="panel recommendations">
          <div className="panel-title">
            <h2>Recommended next steps</h2>
            <span className="accent-icon">↗</span>
          </div>
          {currentFreshness !== 'FRESH' ? (
            <p>Wait for fresh valid measurements before relying on environmental advice.</p>
          ) : latest?.recommendations.some((r) => r.status !== 'WITHDRAWN') ? (
            latest.recommendations
              .filter((r) => r.status !== 'WITHDRAWN')
              .map((r) => (
                <div className="recommendation" key={r.id}>
                  <Badge value={r.status} />
                  <p>{r.message}</p>
                  <small>
                    Based on: {r.measurementIds.join(', ') || 'No measurement reference supplied'}
                  </small>
                </div>
              ))
          ) : (
            <>
              <h3>Keep an eye on your space</h3>
              <p>
                No current recommendation was returned by the backend. Continue monitoring for
                changes.
              </p>
            </>
          )}
          <p className="sensor-caveat">
            MQ-135 measures a general gas response, not CO₂ concentration. Dust readings are
            experimental and do not establish official AQI.
          </p>
        </article>
      </section>
    </>
  );
}
