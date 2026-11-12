import { useEffect, useState } from 'react';
import { channels, freshness, condition } from './model.js';
import { request } from '../../shared/api/client.js';
import { fixtureLatest, fixtureTime } from '../../fixtures/monitoring.js';
export function useMonitoring() {
  const [mode, setMode] = useState('live');
  const [scenario, setScenario] = useState('Normal');
  const [devices, setDevices] = useState([]);
  const [deviceId, setDeviceId] = useState('');
  const [latest, setLatest] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshed, setRefreshed] = useState(null);
  const [now, setNow] = useState(Date.now());
  const [retry, setRetry] = useState(0);
  const demo = mode === 'fixture';
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    setDevices([]);
    setDeviceId('');
    setLatest(null);
    setRefreshed(null);
    setError(null);
    setLoading(true);
    if (demo) {
      const data = fixtureLatest('Normal');
      setDevices([data.device]);
      setDeviceId(data.device.deviceId);
      return () => controller.abort();
    }
    request('/api/devices', { signal: controller.signal })
      .then((data) => {
        setDevices(data.items);
        setDeviceId(data.items[0]?.deviceId || '');
        setLoading(false);
      })
      .catch((e) => {
        if (e.name !== 'AbortError') {
          setError(e);
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, [mode, retry]);
  useEffect(() => {
    if (!deviceId) return;
    const controller = new AbortController();
    let timer;
    setLatest(null);
    setLoading(true);
    setError(null);
    setRefreshed(null);
    async function poll() {
      try {
        const data = demo
          ? fixtureLatest(scenario)
          : await request(`/api/devices/${encodeURIComponent(deviceId)}/latest`, {
              signal: controller.signal,
            });
        if (controller.signal.aborted) return;
        setLatest(data);
        setError(null);
        setRefreshed(Date.now());
      } catch (e) {
        if (e.name !== 'AbortError') setError(e);
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false);
          if (!demo) timer = setTimeout(poll, 5000);
        }
      }
    }
    poll();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [deviceId, demo, scenario, retry]);
  const clock = demo ? fixtureTime + 1000 : now;
  const currentFreshness = freshness(latest, clock);
  const currentCondition = condition(latest, clock);
  const device = latest?.device || devices.find((d) => d.deviceId === deviceId);
  const telemetry = latest?.measurement?.telemetry;
  const visibleChannels = channels.filter(
    (c) =>
      c.key !== (telemetry?.values.dustEstimateUgM3 != null ? 'dustAdcMv' : 'dustEstimateUgM3'),
  );
  return {
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
  };
}
