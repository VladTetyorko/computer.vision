import { Injectable, signal } from '@angular/core';

export type ToastKind = 'ok' | 'error' | 'info';

export interface Toast {
  readonly id: number;
  readonly kind: ToastKind;
  readonly text: string;
}

/** Errors linger long enough to read; confirmations get out of the way. */
const DISMISS_AFTER_MS: Record<ToastKind, number> = {
  ok: 4_000,
  info: 5_000,
  error: 9_000,
};

@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly items = signal<readonly Toast[]>([]);
  private sequence = 0;

  readonly toasts = this.items.asReadonly();

  ok(text: string): void {
    this.push('ok', text);
  }

  info(text: string): void {
    this.push('info', text);
  }

  error(text: string): void {
    this.push('error', text);
  }

  dismiss(id: number): void {
    this.items.update((current) => current.filter((toast) => toast.id !== id));
  }

  private push(kind: ToastKind, text: string): void {
    const id = ++this.sequence;
    this.items.update((current) => [...current, { id, kind, text }]);
    setTimeout(() => this.dismiss(id), DISMISS_AFTER_MS[kind]);
  }
}
