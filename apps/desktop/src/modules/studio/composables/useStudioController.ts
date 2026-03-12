import {useConnectionBrowserModule} from './useConnectionBrowserModule';
import {useErModule} from './useErModule';
import {useErSnapshotModule} from './useErSnapshotModule';
import {useHistoryModule} from './useHistoryModule';
import {useKnowledgeModule} from './useKnowledgeModule';
import {useObjectDefinitionEditorModule} from './useObjectDefinitionEditorModule';
import {useQueryModule} from './useQueryModule';
import {useStudioRuntime} from './useStudioRuntime';
import {useTableCopyModule} from './useTableCopyModule';
import {useTableEditorModule} from './useTableEditorModule';
import {useTableDataModule} from './useTableDataModule';
import {useTableRenameModule} from './useTableRenameModule';
import {useUiShellModule} from './useUiShellModule';

export function useStudioController() {
  const runtime = useStudioRuntime();
  const tableCopyModule = useTableCopyModule(runtime);
  const tableEditorModule = useTableEditorModule(runtime);
  const tableDataModule = useTableDataModule(runtime);
  const tableRenameModule = useTableRenameModule(runtime);
  const objectDefinitionEditorModule = useObjectDefinitionEditorModule(runtime);
  const connectionBrowserModule = useConnectionBrowserModule(runtime, {
    copyTableWithinCurrentDatabase: tableCopyModule.copyTableWithinCurrentDatabase,
    openNewTableEditor: tableEditorModule.openNewTableEditor,
    openEditTableEditor: tableEditorModule.openEditTableEditor,
    openTableDataTabByObject: tableDataModule.openTableDataTabByObject,
    openRenameTableModal: tableRenameModule.openRenameTableModal,
    openObjectDefinitionEditor: objectDefinitionEditorModule.openObjectDefinitionEditor,
    openNewObjectDefinitionEditor: objectDefinitionEditorModule.openNewObjectDefinitionEditor,
  });
  const queryModule = useQueryModule(runtime);
  const erModule = useErModule(runtime);
  const erSnapshotModule = useErSnapshotModule(runtime);
  const historyModule = useHistoryModule(runtime);
  const knowledgeModule = useKnowledgeModule(runtime);
  const uiShellModule = useUiShellModule(runtime, {
    handleBrowserClipboardKeydown: tableCopyModule.handleBrowserClipboardKeydown,
    handleErRelationDeleteKeydown: erModule.handleErRelationDeleteKeydown,
  });

  return {
    ...runtime,
    ...tableCopyModule,
    ...connectionBrowserModule,
    ...queryModule,
    ...erModule,
    ...erSnapshotModule,
    ...historyModule,
    ...knowledgeModule,
    ...tableEditorModule,
    ...tableDataModule,
    ...tableRenameModule,
    ...objectDefinitionEditorModule,
    ...uiShellModule,
    connectionBrowserModule,
    queryModule,
    erModule,
    erSnapshotModule,
    historyModule,
    knowledgeModule,
    tableCopyModule,
    tableEditorModule,
    tableDataModule,
    tableRenameModule,
    objectDefinitionEditorModule,
    uiShellModule,
  };
}

export type StudioController = ReturnType<typeof useStudioController>;
