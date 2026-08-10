import { describe, expect, it } from 'vitest';
import { capabilities, navigation } from './navigation';

/**
 * The two lists describe the same product from different sides — what it can do, and where you
 * can go — and nothing in the type system holds them together. These are the ways they can
 * disagree, each of which is visible to a reader before it is visible to anyone maintaining it.
 */
describe('capabilities against navigation', () => {
  const groups = new Map(navigation.map((group) => [group.id, group]));

  it('gives every live capability a group of destinations', () => {
    for (const capability of capabilities.filter((c) => c.status === 'live')) {
      const group = groups.get(capability.id);

      // A live capability with no group is a dashboard section describing something a reader
      // cannot then open.
      expect(group, `no nav group for live capability '${capability.id}'`).toBeDefined();
      expect(group?.items.length, `nav group '${capability.id}' is empty`).toBeGreaterThan(0);
    }
  });

  it('leaves nothing served but unannounced', () => {
    // Every group but the bare ones the rail uses for chrome — the dashboard link, and the
    // planned rows — belongs to a capability the dashboard names.
    const owned = new Set(capabilities.map((capability) => capability.id));
    const structural = new Set(['root', 'future']);

    for (const group of navigation) {
      if (structural.has(group.id)) continue;
      expect(owned, `nav group '${group.id}' is on the rail and on no capability`).toContain(group.id);
    }
  });

  it('names every planned capability on the rail too', () => {
    const planned = navigation.flatMap((group) => group.items).filter((item) => item.disabled);
    const railIds = new Set(planned.map((item) => item.id));

    for (const capability of capabilities.filter((c) => c.status === 'planned')) {
      expect(railIds, `'${capability.id}' is promised on the dashboard only`).toContain(capability.id);
    }
  });

  it('says what each capability is for', () => {
    for (const capability of capabilities) {
      // The summary is the only place the product explains itself. An empty one leaves a heading
      // and a status tag, which says a capability exists without saying what it does.
      expect(capability.summary.trim().length, `'${capability.id}' has no summary`).toBeGreaterThan(0);
    }
  });
});
