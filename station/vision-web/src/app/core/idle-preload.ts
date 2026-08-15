import { Injectable } from '@angular/core';
import type { PreloadingStrategy, Route } from '@angular/router';
import { Observable, of } from 'rxjs';

interface IdleWindow {
  requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number;
  cancelIdleCallback?: (handle: number) => void;
}

/** Give the first paint and the initial data load a clear run before preloading. */
const IDLE_TIMEOUT_MS = 2_000;

/**
 * Fetches lazy route bundles once the browser is idle.
 *
 * Route-level code splitting keeps the initial bundle small; without preloading it would
 * trade that for a visible stall on the first visit to each tab. Preloading after idle
 * keeps both: small initial payload, instant tab switches.
 */
@Injectable({ providedIn: 'root' })
export class IdlePreload implements PreloadingStrategy {
  preload(route: Route, load: () => Observable<unknown>): Observable<unknown> {
    if (route.data?.['preload'] === false) {
      return of(null);
    }
    return new Observable((subscriber) => {
      const idle = window as unknown as IdleWindow;
      let cancelled = false;

      const run = () => {
        if (!cancelled) {
          load().subscribe(subscriber);
        }
      };

      const handle = idle.requestIdleCallback
        ? idle.requestIdleCallback(run, { timeout: IDLE_TIMEOUT_MS })
        : (setTimeout(run, IDLE_TIMEOUT_MS) as unknown as number);

      return () => {
        cancelled = true;
        if (idle.cancelIdleCallback) {
          idle.cancelIdleCallback(handle);
        } else {
          clearTimeout(handle);
        }
      };
    });
  }
}
