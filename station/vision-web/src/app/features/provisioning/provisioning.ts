import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { ProvisioningFacade } from './provisioning-facade';

/**
 * `/provision-wifi` — Wi-Fi-over-USB provisioning via the open Improv Serial standard
 * (https://www.improv-wifi.com/serial/), reached from the onboarding wizard's drone-config step
 * ("Provision Wi-Fi over USB", `onboarding.html`'s `@case ('config')` block) as an alternative to
 * copying MAVLink connection strings onto the aircraft by hand.
 *
 * **Why this page exists at all** (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §2, the Improv
 * research finding, and §4 P1): the device is never told this platform's own address, on this page or
 * anywhere else in the flow. It learns only its Wi-Fi SSID/password over USB, connects to that network
 * on its own, and *announces itself* — mDNS/ONVIF discovery (already built) picks it up from there and
 * the operator adds it from the discovery inbox like any other found device. This page's own "what
 * happens next" note (below the form) is the one place that promise is spelled out to the operator;
 * see `provisioning-facade.ts`'s class doc for the state-machine side of the same story.
 *
 * Pure frontend — no `VisionApi` call anywhere in this feature. Injects only {@link ProvisioningFacade}
 * (`core/ui/architecture.spec.ts`'s `ROUTED_PAGES` guard).
 */
@Component({
  selector: 'vision-provisioning',
  imports: [FormsModule, EmptyState, Notice, PageBar],
  templateUrl: './provisioning.html',
  styleUrl: './provisioning.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ProvisioningFacade],
})
export class ProvisioningPage {
  protected readonly facade = inject(ProvisioningFacade);

  protected onSsidInput(value: string): void {
    this.facade.setSsid(value);
  }

  protected onPasswordInput(value: string): void {
    this.facade.setPassword(value);
  }
}
