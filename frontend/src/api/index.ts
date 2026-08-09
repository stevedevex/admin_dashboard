/**
 * The data seam.
 *
 * All data access goes through here. Nothing outside `src/api` performs I/O —
 * enforced by ESLint, see eslint.config.js.
 */

export { client as api } from './client';
export type { Transport } from './transport';

export type {
  MockContent,
  MockDataSummary,
  MockDraft,
  MockFormat,
  MockName,
  MockState,
  MockSummary,
  Operation,
  OperationSchema,
  RequestDetail,
  RequestEntry,
  RequestPage,
  ResolutionTrace,
  ResolveRequest,
  Scenario,
  Service,
  ValidationIssue,
  ValidationResult,
} from './types';
