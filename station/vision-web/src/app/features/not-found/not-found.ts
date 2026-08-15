import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'vision-not-found',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="card empty">
        <h3>No such page</h3>
        <p>That URL does not match any section of Vision.</p>
        <a class="btn" routerLink="/wall">Back to the Wall</a>
      </div>
    </div>
  `,
})
export class NotFoundPage {}
