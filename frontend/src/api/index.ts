/**
 * The data seam.
 *
 * All data access goes through here. Nothing outside `src/api` performs I/O —
 * enforced by ESLint, see eslint.config.js.
 */

export { client as api } from './client';
export type { Transport } from './transport';

export type {
  AiStatus,
  KeyField,
  KeyStrategy,
  MockContent,
  MockDataSummary,
  MockDraft,
  MockFormat,
  MockName,
  MockState,
  MockSummary,
  Operation,
  OperationSchema,
  PayloadGeneration,
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
