/**
 * Thin type-only wrapper over the generated OpenAPI schema.
 *
 * The public API contract (`contracts/openapi.yaml`) is the single source of
 * truth; types are regenerated via `pnpm generate:api` (see
 * contracts/api-contract.md §4). This module intentionally has NO runtime
 * client — no fetch calls are made in this feature (FR-015, see
 * contracts/technical-endpoints.md §2).
 */
import type { components, paths } from './schema'

export type { components, paths }
