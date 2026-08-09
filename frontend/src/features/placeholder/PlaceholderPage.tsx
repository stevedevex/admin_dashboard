import { PageHeader } from '@/app/layout/PageHeader';
import { EmptyState, Panel, Tag } from '@/ui';

/** Stands in for a navigation destination that is planned but not built. */
export function PlaceholderPage({ title, summary }: { title: string; summary: string }) {
  return (
    <>
      <PageHeader title={title} meta={<Tag tone="neutral">soon</Tag>} />
      <Panel flush>
        <EmptyState title={`${title} is not implemented yet`}>{summary}</EmptyState>
      </Panel>
    </>
  );
}
