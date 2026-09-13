/**
 * Minimal ambient type shims for the handful of Node built-ins `no-client-rederivation.spec.ts` needs
 * (`fs`/`path`/`url`) — this project has no `@types/node` (not a direct dependency, and CLAUDE.md's
 * build instructions say not to add/upgrade dependencies for this wave), so these three otherwise have
 * no type declarations under `tsconfig.spec.json`'s `types: ["vitest/globals"]`. They are real Node
 * built-ins at *runtime* regardless — the `@angular/build:unit-test` vitest runner executes specs in a
 * Node process (`ng test --help`: "tests are run in a Node.js environment using jsdom") — only the
 * *type-check* needs help.
 *
 * Deliberately a standalone global script (no top-level `import`/`export` of its own) rather than
 * declared inline inside the spec file: TypeScript treats `declare module '...'` written inside a file
 * that already has its own imports/exports (a "module" file, which the spec file is, importing from
 * `vitest`) as an *augmentation* of an existing module rather than a fresh ambient declaration, and
 * augmentation requires the named module to already resolve — for `fs`/`path`/`url` (bare or
 * `node:`-prefixed) that fails with `TS2664` since nothing declares them without `@types/node`. A
 * global script `.d.ts` has no such restriction, exactly the classic pre-ES-modules `@types` shape.
 *
 * Only the members this repo's specs actually call are declared — not a real Node types surface.
 */

declare module 'fs' {
  export interface Dirent {
    readonly name: string;
    isDirectory(): boolean;
    isFile(): boolean;
  }
  export function readdirSync(path: string, options: { withFileTypes: true }): readonly Dirent[];
  export function readFileSync(path: string, encoding: 'utf-8'): string;
}

declare module 'path' {
  export function join(...segments: readonly string[]): string;
  export function dirname(path: string): string;
}

declare module 'url' {
  export function fileURLToPath(url: string | URL): string;
}
