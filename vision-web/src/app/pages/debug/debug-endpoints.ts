/**
 * The Debug tab's endpoint catalog.
 *
 * One entry per route `vision-api` actually exposes (vision-api/MODULE.md's controller
 * table) — picking one is a shortcut into the raw console below, never a claim about what
 * exists, so the list stays in lockstep with that table rather than growing speculative
 * entries.
 */
export type DebugMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface DebugEndpoint {
  readonly id: string;
  readonly method: DebugMethod;
  readonly path: string;
  readonly label: string;
  /** Pretty-printed JSON text prefilled into the body editor; only POSTs carry one. */
  readonly exampleBody?: string;
}

export const DEBUG_ENDPOINTS: readonly DebugEndpoint[] = [
  { id: 'devices-list', method: 'GET', path: '/api/devices', label: 'GET /api/devices' },
  {
    id: 'devices-register',
    method: 'POST',
    path: '/api/devices',
    label: 'POST /api/devices',
    exampleBody: JSON.stringify(
      { name: 'front-gate', protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' },
      null,
      2,
    ),
  },
  { id: 'streams-list', method: 'GET', path: '/api/streams', label: 'GET /api/streams' },
  {
    id: 'stream-start',
    method: 'POST',
    path: '/api/devices/{deviceId}/stream',
    label: 'POST /api/devices/{deviceId}/stream',
    exampleBody: JSON.stringify({ confidenceThreshold: 0.4, inferenceFps: 5 }, null, 2),
  },
  {
    id: 'stream-stop',
    method: 'DELETE',
    path: '/api/streams/{streamId}',
    label: 'DELETE /api/streams/{streamId}',
  },
  { id: 'assets-list', method: 'GET', path: '/api/assets', label: 'GET /api/assets' },
  {
    id: 'assets-create',
    method: 'POST',
    path: '/api/assets',
    label: 'POST /api/assets',
    exampleBody: JSON.stringify(
      {
        displayName: 'Drone 1',
        category: 'drone',
        devices: [
          { name: 'front-cam', protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' },
        ],
      },
      null,
      2,
    ),
  },
  { id: 'asset-details', method: 'GET', path: '/api/assets/{id}', label: 'GET /api/assets/{id}' },
  {
    id: 'asset-stream-start',
    method: 'POST',
    path: '/api/assets/{id}/stream',
    label: 'POST /api/assets/{id}/stream',
    exampleBody: JSON.stringify({ confidenceThreshold: 0.4, inferenceFps: 5 }, null, 2),
  },
  {
    id: 'asset-stream-stop',
    method: 'DELETE',
    path: '/api/assets/{id}/stream',
    label: 'DELETE /api/assets/{id}/stream',
  },
  {
    id: 'usage-telemetry',
    method: 'GET',
    path: '/api/usages/{usageId}/telemetry',
    label: 'GET /api/usages/{usageId}/telemetry',
  },
  { id: 'categories-list', method: 'GET', path: '/api/categories', label: 'GET /api/categories' },
  {
    id: 'discovery-scan',
    method: 'POST',
    path: '/api/discovery/scan',
    label: 'POST /api/discovery/scan',
    exampleBody: JSON.stringify({ timeoutMs: 4000 }, null, 2),
  },
  {
    id: 'actuator-health',
    method: 'GET',
    path: '/actuator/health',
    label: 'GET /actuator/health',
  },
];

export function findDebugEndpoint(id: string): DebugEndpoint | undefined {
  return DEBUG_ENDPOINTS.find((endpoint) => endpoint.id === id);
}

/** Whether the console shows a request-body editor for the given (freely-typed) method. */
export function methodHasBody(method: string): boolean {
  return method === 'POST' || method === 'PUT' || method === 'PATCH';
}

export interface EndpointPrefill {
  readonly method: DebugMethod;
  readonly path: string;
  readonly body: string;
}

/** What picking a catalog entry fills the form with; `undefined` for an unknown/custom id. */
export function prefillForEndpoint(id: string): EndpointPrefill | undefined {
  const endpoint = findDebugEndpoint(id);
  if (!endpoint) {
    return undefined;
  }
  return { method: endpoint.method, path: endpoint.path, body: endpoint.exampleBody ?? '' };
}
