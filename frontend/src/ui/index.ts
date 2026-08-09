/**
 * The design-system seam.
 *
 * Every component used by the application is exported here. Nothing outside
 * this directory imports a component library directly — enforced by ESLint,
 * see eslint.config.js.
 *
 * To build against a different design system, reimplement the primitives in
 * this directory and remap tokens.css. No file outside `src/ui` changes.
 */

export { Button } from './primitives/Button';
export type { ButtonEmphasis, ButtonProps } from './primitives/Button';

export { Tag, MetaTag } from './primitives/Tag';
export type { TagTone, TagProps } from './primitives/Tag';

export { Panel, EmptyState } from './primitives/Panel';
export type { PanelProps } from './primitives/Panel';

export { Dialog, Field } from './primitives/Dialog';
export type { DialogProps } from './primitives/Dialog';

export { TextInput, TextArea, Select } from './primitives/Input';
export type { TextInputProps, TextAreaProps, SelectProps } from './primitives/Input';

// Lazy wrapper, not the implementation — see CodeEditorLazy.
export { CodeEditor } from './primitives/CodeEditorLazy';
export type { CodeEditorProps, CodeLanguage, CodeMarker } from './primitives/CodeEditor';

export { Icon } from './icons';
export type { IconName, IconProps } from './icons';
