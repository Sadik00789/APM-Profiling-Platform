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
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isMountedRef = useRef(true);
  const enabledRef = useRef(enabled);

  const cleanupConnection = useCallback(() => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
  }, []);

  const connect = useCallback(() => {
    cleanupConnection();
    if (!enabledRef.current || !isMountedRef.current) return;

    try {
      const es = new EventSource('/api/v1/stream/live');
      eventSourceRef.current = es;

      es.onopen = () => {
        if (isMountedRef.current && enabledRef.current) {
          setIsConnected(true);
        }
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
        if (eventSourceRef.current === es) {
          eventSourceRef.current = null;
        }
        if (isMountedRef.current && enabledRef.current) {
          if (reconnectTimerRef.current) {
            clearTimeout(reconnectTimerRef.current);
          }
          reconnectTimerRef.current = setTimeout(() => {
            reconnectTimerRef.current = null;
            connect();
          }, 3000);
        }
      };
    } catch (e) {
      console.debug('SSE connection error:', e);
      setIsConnected(false);
    }
  }, [cleanupConnection, maxEvents]);

  useEffect(() => {
    isMountedRef.current = true;
    enabledRef.current = enabled;

    if (enabled) {
      connect();
    } else {
      cleanupConnection();
      setIsConnected(false);
    }

    return () => {
      isMountedRef.current = false;
      cleanupConnection();
    };
  }, [enabled, connect, cleanupConnection]);

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
