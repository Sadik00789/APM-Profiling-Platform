'use client';

import { useState, useEffect, useCallback, useRef } from 'react';

export interface LiveSpanEvent {
  traceId: string;
  spanId: string;
  parentSpanId: string;
  serviceName: string;
  operationName: string;
  durationMs: number;
  statusCode: string;
  timestamp: number;
}

export interface LatencyAnomalyAlert {
  type: string;
  serviceName: string;
  operationName: string;
  observedLatencyMs: number;
  reason: string;
  timestamp: number;
}

export function useLiveTelemetryStream(enabled = true, maxEvents = 100) {
  const [liveSpans, setLiveSpans] = useState<LiveSpanEvent[]>([]);
  const [anomalyAlerts, setAnomalyAlerts] = useState<LatencyAnomalyAlert[]>([]);
  const [isConnected, setIsConnected] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);

  const connect = useCallback(() => {
    if (!enabled) return;

    try {
      const es = new EventSource('/api/v1/stream/live');
      eventSourceRef.current = es;

      es.onopen = () => {
        setIsConnected(true);
      };

      es.addEventListener('span', (event: MessageEvent) => {
        try {
          const span: LiveSpanEvent = JSON.parse(event.data);
          setLiveSpans((prev) => [span, ...prev.slice(0, maxEvents - 1)]);
        } catch (err) {
          console.debug('Failed to parse span SSE event', err);
        }
      });

      es.addEventListener('anomaly', (event: MessageEvent) => {
        try {
          const alert: LatencyAnomalyAlert = JSON.parse(event.data);
          setAnomalyAlerts((prev) => [alert, ...prev.slice(0, 19)]);
        } catch (err) {
          console.debug('Failed to parse anomaly SSE event', err);
        }
      });

      es.onerror = () => {
        setIsConnected(false);
        es.close();
        // Reconnect after 3 seconds
        setTimeout(connect, 3000);
      };
    } catch (e) {
      console.debug('SSE connection error:', e);
      setIsConnected(false);
    }
  }, [enabled, maxEvents]);

  useEffect(() => {
    if (enabled) {
      connect();
    } else {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
        eventSourceRef.current = null;
      }
      setIsConnected(false);
    }

    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
    };
  }, [enabled, connect]);

  const clearAlerts = useCallback(() => {
    setAnomalyAlerts([]);
  }, []);

  return {
    liveSpans,
    anomalyAlerts,
    isConnected,
    clearAlerts,
  };
}
