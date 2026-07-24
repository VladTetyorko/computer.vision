import { describe, expect, it } from 'vitest';
import { buildTestDroneRequest, type TestDroneForm } from './simulation-logic';

function testDroneForm(partial: Partial<TestDroneForm> = {}): TestDroneForm {
  return { name: '', latitude: null, longitude: null, autoStart: true, ...partial };
}

describe('buildTestDroneRequest', () => {
  it('omits videoPath entirely — that is what makes it a fully synthetic simulation', () => {
    const request = buildTestDroneRequest(testDroneForm());
    expect(request).not.toHaveProperty('videoPath');
  });

  it('omits a blank name rather than sending an empty displayName', () => {
    expect(buildTestDroneRequest(testDroneForm({ name: '   ' }))).not.toHaveProperty('displayName');
  });

  it('trims and includes a non-blank name', () => {
    expect(buildTestDroneRequest(testDroneForm({ name: '  Chase drone  ' })).displayName).toBe('Chase drone');
  });

  it('omits latitude/longitude when absent rather than sending null', () => {
    const request = buildTestDroneRequest(testDroneForm());
    expect(request).not.toHaveProperty('latitude');
    expect(request).not.toHaveProperty('longitude');
  });

  it('includes latitude/longitude when given, including falsy-but-valid 0', () => {
    const request = buildTestDroneRequest(testDroneForm({ latitude: 0, longitude: -122.4 }));
    expect(request.latitude).toBe(0);
    expect(request.longitude).toBe(-122.4);
  });

  it('carries autoStart through as given', () => {
    expect(buildTestDroneRequest(testDroneForm({ autoStart: false })).autoStart).toBe(false);
  });

  it('never sends a transport — the backend defaults a videoPath-less request to direct', () => {
    expect(buildTestDroneRequest(testDroneForm())).not.toHaveProperty('transport');
  });

  it('omits telemetry when the form carries no flight plan', () => {
    expect(buildTestDroneRequest(testDroneForm())).not.toHaveProperty('telemetry');
  });

  it('includes telemetry verbatim when the form carries a flight plan (docs/CYCLES-PLAN.md §7, CT-b)', () => {
    const telemetry: TestDroneForm['telemetry'] = {
      routeMode: 'once',
      route: [
        { latitude: 5, longitude: 6 },
        { latitude: 7, longitude: 8 },
      ],
    };
    expect(buildTestDroneRequest(testDroneForm({ telemetry })).telemetry).toEqual(telemetry);
  });
});
