import {
  AlertTriangle,
  Check,
  CheckCircle2,
  ArrowLeftRight,
  ChevronDown,
  ChevronRight,
  CirclePlus,
  Clock,
  FileCode2,
  FileText,
  FolderClosed,
  HelpCircle,
  Layers,
  LayoutGrid,
  MinusCircle,
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
  create: CirclePlus,
  save: Save,
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
  unknown: MinusCircle,
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
