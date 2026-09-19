import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { PlaygroundFacade } from './playground-facade';
import { VisionApi } from '../../core/api/vision-api';
import { FleetFacade } from '../../core/fleet/fleet-facade';
import { ToastService } from '../../core/toast.service';
import type { FlightPlanForm } from '../../shared/map/flight-plan-logic';
import type { SimulationResponse, SystemNetworkResponse } from '../../core/api/models';

function network(overrides: Partial<SystemNetworkResponse> = {}): SystemNetworkResponse {
  return { addresses: [], mavlinkPort: 14550, ...overrides };
}

function response(overrides: Partial<SimulationResponse> = {}): SimulationResponse {
  return { assetId: 'asset-1', ...overrides };
}

function stubApi(systemNetwork: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue(network())) {
  return { systemNetwork };
}

function stubFleet(simulate: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue(response())) {
  return { simulate };
}

function stubToasts() {
  return { ok: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), notification: vi.fn() };
}

function inject(options: {
  api?: ReturnType<typeof stubApi>;
  fleet?: ReturnType<typeof stubFleet>;
  toasts?: ReturnType<typeof stubToasts>;
} = {}): { facade: PlaygroundFacade; fleet: ReturnType<typeof stubFleet>; toasts: ReturnType<typeof stubToasts> } {
  const api = options.api ?? stubApi();
  const fleet = options.fleet ?? stubFleet();
  const toasts = options.toasts ?? stubToasts();
  TestBed.configureTestingModule({
    providers: [
      PlaygroundFacade,
      { provide: VisionApi, useValue: api },
      { provide: FleetFacade, useValue: fleet },
      { provide: ToastService, useValue: toasts },
    ],
  });
  return { facade: TestBed.inject(PlaygroundFacade), fleet, toasts };
}

function plan(overrides: Partial<FlightPlanForm> = {}): FlightPlanForm {
  return {
    waypoints: [
      { latitude: 1, longitude: 2, altitudeMeters: null },
      { latitude: 3, longitude: 4, altitudeMeters: null },
    ],
    speedMps: 12,
    routeMode: 'loop',
    ...overrides,
  };
}

describe('PlaygroundFacade', () => {
  describe('load() — the vision.simulation.enabled capability read', () => {
    it('reads simulationEnabled off GET /api/system/network', async () => {
      const { facade } = inject({ api: stubApi(vi.fn().mockResolvedValue(network({ simulationEnabled: false }))) });
      expect(facade.simulationEnabled()).toBe(true); // default before load() resolves
      await facade.load();
      expect(facade.simulationEnabled()).toBe(false);
      expect(facade.checkingCapability()).toBe(false);
    });

    it('defaults to true (enabled) when the field is absent — dev-parity / un-upgraded backend', async () => {
      const { facade } = inject({ api: stubApi(vi.fn().mockResolvedValue(network())) });
      await facade.load();
      expect(facade.simulationEnabled()).toBe(true);
    });

    it('defaults to true on a fetch failure too, never a blocked page', async () => {
      const { facade } = inject({ api: stubApi(vi.fn().mockRejectedValue(new Error('network down'))) });
      await facade.load();
      expect(facade.simulationEnabled()).toBe(true);
      expect(facade.checkingCapability()).toBe(false);
    });
  });

  describe('canSubmit()', () => {
    it('is true by default (testDrone needs no video path)', () => {
      const { facade } = inject();
      expect(facade.mode()).toBe('testDrone');
      expect(facade.canSubmit()).toBe(true);
    });

    it('is false for direct/rtsp until a video path is entered', () => {
      const { facade } = inject();
      facade.setMode('direct');
      expect(facade.canSubmit()).toBe(false);
      facade.videoPath.set('/srv/videos/flight.mp4');
      expect(facade.canSubmit()).toBe(true);
    });
  });

  describe('submit()', () => {
    it('builds a testDrone request and applies the result on success', async () => {
      const simulate = vi.fn().mockResolvedValue(response({ assetId: 'asset-9' }));
      const { facade, toasts } = inject({ fleet: stubFleet(simulate) });
      facade.name.set('Rover under test');
      facade.latitude.set(10);
      facade.longitude.set(20);

      await facade.submit();

      expect(simulate).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({ displayName: 'Rover under test', latitude: 10, longitude: 20, autoStart: true }),
      );
      expect(facade.lastCreated()).toEqual({ assetId: 'asset-9' });
      expect(toasts.ok).toHaveBeenCalledOnce();
      expect(facade.busy()).toBe(false);
    });

    it('builds a direct-mode request carrying the video path', async () => {
      const simulate = vi.fn().mockResolvedValue(response());
      const { facade } = inject({ fleet: stubFleet(simulate) });
      facade.setMode('direct');
      facade.videoPath.set('/srv/videos/flight.mp4');

      await facade.submit();

      expect(simulate).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({ videoPath: '/srv/videos/flight.mp4', transport: 'direct' }),
      );
    });

    it('includes a serialized flight plan when one was drawn', async () => {
      const simulate = vi.fn().mockResolvedValue(response());
      const { facade } = inject({ fleet: stubFleet(simulate) });
      facade.onFlightPlanSaved(plan());

      expect(facade.flightPlanDialogOpen()).toBe(false);
      expect(facade.flightPlanSummary()).toBe('2 waypoints · loop');

      await facade.submit();

      expect(simulate).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({ telemetry: expect.objectContaining({ routeMode: 'loop' }) }),
      );
    });

    it('is a no-op when canSubmit() is false — never calls the API with a blank required field', async () => {
      const simulate = vi.fn();
      const { facade } = inject({ fleet: stubFleet(simulate) });
      facade.setMode('rtsp');

      await facade.submit();

      expect(simulate).not.toHaveBeenCalled();
    });

    it('does not toast success or set lastCreated when FleetStore.simulate() fails (already toasted by run())', async () => {
      const simulate = vi.fn().mockResolvedValue(null);
      const { facade, toasts } = inject({ fleet: stubFleet(simulate) });

      await facade.submit();

      expect(facade.lastCreated()).toBeUndefined();
      expect(toasts.ok).not.toHaveBeenCalled();
    });

    it('resets the one-shot fields but preserves mode/autoStart for the next create', async () => {
      const { facade } = inject();
      facade.name.set('Rover A');
      facade.latitude.set(1);
      facade.longitude.set(2);
      facade.onFlightPlanSaved(plan());
      facade.autoStart.set(false);

      await facade.submit();

      expect(facade.name()).toBe('');
      expect(facade.latitude()).toBeNull();
      expect(facade.longitude()).toBeNull();
      expect(facade.flightPlan()).toBeUndefined();
      expect(facade.mode()).toBe('testDrone');
      expect(facade.autoStart()).toBe(false);
    });
  });

  describe('dismissCreated()', () => {
    it('clears lastCreated', async () => {
      const { facade } = inject();
      await facade.submit();
      expect(facade.lastCreated()).toBeDefined();
      facade.dismissCreated();
      expect(facade.lastCreated()).toBeUndefined();
    });
  });
});
