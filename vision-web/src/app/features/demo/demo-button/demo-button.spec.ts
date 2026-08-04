import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ToastService } from '../../../core/toast.service';
import { DemoButton } from './demo-button';

const STATUS = { enabled: true, videosDirectory: '/home/pilot/Videos', videos: ['drone.mp4'] };

const RESULT = {
  assets: 10,
  users: 10,
  assignments: 14,
  zones: 2,
  marks: 5,
  streamsStarted: 3,
  assetNames: ['Demo 01'],
  usernames: ['demo.falcon'],
  password: 'demo',
  videosUsed: ['drone.mp4'],
  problems: [] as string[],
};

describe('DemoButton', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders the button once the backend reports the demo package is present', async () => {
    const fixture = TestBed.createComponent(DemoButton);
    fixture.detectChanges();

    http.expectOne({ method: 'GET', url: '/api/demo' }).flush(STATUS);
    await fixture.whenStable();
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('.demo-button');
    expect(button?.textContent?.trim()).toBe('Fill demo data');
    expect(button?.getAttribute('title')).toContain('/home/pilot/Videos');
  });

  it('renders nothing when the demo package is absent (the probe 404s)', async () => {
    const fixture = TestBed.createComponent(DemoButton);
    fixture.detectChanges();

    http.expectOne({ method: 'GET', url: '/api/demo' }).flush('', { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.demo-button')).toBeNull();
  });

  it('seeds on click, disables itself while in flight, and confirms with a toast', async () => {
    const fixture = TestBed.createComponent(DemoButton);
    const toast = TestBed.inject(ToastService);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/demo' }).flush(STATUS);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.demo-button').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.demo-button').disabled).toBe(true);

    const seed = http.expectOne({ method: 'POST', url: '/api/demo/seed' });
    expect(seed.request.body).toEqual({});
    seed.flush(RESULT);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.demo-button').disabled).toBe(false);
    expect(toast.toasts()).toHaveLength(1);
    expect(toast.toasts()[0].kind).toBe('ok');
    expect(toast.toasts()[0].text).toContain('10 assets');
  });

  it('reports a partially failed seed as a second, warning toast', async () => {
    const fixture = TestBed.createComponent(DemoButton);
    const toast = TestBed.inject(ToastService);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/demo' }).flush(STATUS);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.demo-button').click();
    http.expectOne({ method: 'POST', url: '/api/demo/seed' })
      .flush({ ...RESULT, problems: ['stream Demo 01: publisher unreachable'] });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(toast.toasts().map((entry) => entry.kind)).toEqual(['ok', 'warning']);
    expect(toast.toasts()[1].text).toContain('publisher unreachable');
  });

  it('surfaces a failed seed as an error toast and re-enables the button', async () => {
    const fixture = TestBed.createComponent(DemoButton);
    const toast = TestBed.inject(ToastService);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/demo' }).flush(STATUS);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.demo-button').click();
    http.expectOne({ method: 'POST', url: '/api/demo/seed' })
      .flush('', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(toast.toasts()[0].kind).toBe('error');
    expect(fixture.nativeElement.querySelector('.demo-button').disabled).toBe(false);
  });
});
