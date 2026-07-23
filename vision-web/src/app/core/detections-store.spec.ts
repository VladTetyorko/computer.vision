import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { DetectionsStore } from './detections-store';
import { VisionApi } from './api/vision-api';
import type { DetectionResult } from './api/models';

/** Lets the fire-and-forget promise chain inside `track()` settle before asserting. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function stubApi(streamDetections: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue([])) {
  return { streamDetections };
}

function inject(api: ReturnType<typeof stubApi>): DetectionsStore {
  TestBed.configureTestingModule({ providers: [DetectionsStore, { provide: VisionApi, useValue: api }] });
  return TestBed.inject(DetectionsStore);
}

describe('DetectionsStore', () => {
  it('polls the stream and derives chips/status from the results', async () => {
    const result: DetectionResult = {
      streamId: 's-1',
      frameSequence: 3,
      capturedAt: new Date().toISOString(),
      inferenceMillis: 5,
      detections: [
        { label: 'person', confidence: 0.87, box: { x: 0, y: 0, width: 0.1, height: 0.1 }, modelId: 'yolo', modelVersion: 'latest' },
      ],
    };
    const api = stubApi(vi.fn().mockResolvedValue([result]));

    const store = inject(api);
    store.track('s-1');
    await flush();

    expect(api.streamDetections).toHaveBeenCalledWith('s-1', 50);
    expect(store.results()).toEqual([result]);
    expect(store.chips()).toEqual([{ label: 'person', confidence: 0.87 }]);
    expect(store.status()).toBe('on');

    store.reset();
  });

  it('silently degrades when the poll fails, rather than throwing', async () => {
    const api = stubApi(vi.fn().mockRejectedValue(new Error('network down')));

    const store = inject(api);
    store.track('s-2');
    await flush();

    expect(store.results()).toEqual([]);
    expect(store.chips()).toEqual([]);
    expect(store.status()).toBe('off');
  });

  it('reset() clears results and stops polling', async () => {
    const result: DetectionResult = {
      streamId: 's-3',
      frameSequence: 1,
      capturedAt: new Date().toISOString(),
      inferenceMillis: 5,
      detections: [
        { label: 'car', confidence: 0.7, box: { x: 0, y: 0, width: 0.1, height: 0.1 }, modelId: 'yolo', modelVersion: 'latest' },
      ],
    };
    const api = stubApi(vi.fn().mockResolvedValue([result]));

    const store = inject(api);
    store.track('s-3');
    await flush();
    expect(store.results()).toEqual([result]);

    store.reset();
    expect(store.results()).toEqual([]);
    expect(store.chips()).toEqual([]);
    expect(store.status()).toBe('off');
  });

  it('a stale in-flight poll from a superseded track() never overwrites the newer stream', async () => {
    let resolveFirst!: (value: DetectionResult[]) => void;
    const firstCall = new Promise<DetectionResult[]>((resolve) => {
      resolveFirst = resolve;
    });
    const secondResult: DetectionResult = {
      streamId: 's-5',
      frameSequence: 1,
      capturedAt: new Date().toISOString(),
      inferenceMillis: 5,
      detections: [],
    };
    const streamDetections = vi
      .fn()
      .mockImplementationOnce(() => firstCall)
      .mockResolvedValueOnce([secondResult]);
    const api = stubApi(streamDetections);

    const store = inject(api);
    store.track('s-4'); // in flight, not yet resolved
    store.track('s-5'); // supersedes it before the first poll settles
    await flush();
    resolveFirst([
      {
        streamId: 's-4',
        frameSequence: 9,
        capturedAt: new Date().toISOString(),
        inferenceMillis: 5,
        detections: [],
      },
    ]);
    await flush();

    expect(store.results()).toEqual([secondResult]);
    store.reset();
  });
});
