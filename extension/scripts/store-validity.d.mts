/**
 * Types for the plain-ESM store-validity rules.
 *
 * The implementation stays untyped `.mjs` on purpose: it is shared verbatim by the Node build
 * gate (`scripts/verify-manifest.mjs`) and the vitest suite, so it must run under plain node
 * with no transpile step. This declaration gives the TypeScript side real types instead of an
 * `any` import or a suppression comment.
 */

export declare const DESCRIPTION_MAX_CHARS: number;
export declare const REQUIRED_ICON_SIZE: string;

/** Human-readable Chrome Web Store problems with a parsed manifest; empty means valid. */
export declare function findStoreValidityProblems(manifest: unknown): string[];
