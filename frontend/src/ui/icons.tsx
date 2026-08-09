import {
  AlertTriangle,
  Sparkles,
  Trash2,
  Check,
  CheckCircle2,
  ArrowLeftRight,
  ChevronDown,
  ChevronRight,
  CircleDashed,
  CirclePlus,
  Clock,
  FileCode2,
  FileText,
  FolderClosed,
  HelpCircle,
  Layers,
  LayoutGrid,
  MoreVertical,
  PanelLeft,
  RefreshCw,
  Save,
  Search,
  Server,
  XCircle,
} from 'lucide-react';

/**
 * The icon registry.
 *
 * Names are semantic, not pictorial — `mocks`, not `file-code`. Callers ask
 * for meaning, so changing which glyph represents a concept (or swapping icon
 * libraries entirely) happens here and nowhere else.
 *
 * This is the only file that imports an icon library, same rule as every other
 * vendor dependency. See ARCHITECTURE.md.
 */
const registry = {
  // Navigation destinations
  dashboard: LayoutGrid,
  mocks: FileCode2,
  scenarios: Layers,
  requests: ArrowLeftRight,
  planned: Clock,

  // Shell
  collapse: PanelLeft,
  help: HelpCircle,
  search: Search,
  more: MoreVertical,

  // Actions
  reload: RefreshCw,
  // Sparkles is the settled convention for model-assisted actions; a reader recognises it
  // without a label, which is what keeps the button honest about what it is.
  ai: Sparkles,
  create: CirclePlus,
  save: Save,
  delete: Trash2,
  validate: Check,

  // Tree
  expanded: ChevronDown,
  collapsed: ChevronRight,
  service: Server,
  folder: FolderClosed,
  file: FileText,

  // State — mirrors the `TagTone` vocabulary
  ok: CheckCircle2,
  warn: AlertTriangle,
  error: XCircle,
  /* Dashed, not a minus in a circle: this marks a thing not yet assessed, and circle-minus is
     the near-universal glyph for remove — readers took it for a delete control that does not
     exist on the row. A status must not look like an action. */
  unknown: CircleDashed,
} as const;

export type IconName = keyof typeof registry;

export type IconProps = {
  name: IconName;
  /** Pixel size. 14 suits inline text, 16 controls, 18 headers. */
  size?: number;
  className?: string;
};

export function Icon({ name, size = 16, className }: IconProps) {
  const Glyph = registry[name];
  return <Glyph size={size} strokeWidth={1.75} className={className} aria-hidden focusable={false} />;
}
