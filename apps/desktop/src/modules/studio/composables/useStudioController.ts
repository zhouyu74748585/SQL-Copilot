import {useConnectionBrowserModule} from './useConnectionBrowserModule';
import {useErModule} from './useErModule';
import {useErSnapshotModule} from './useErSnapshotModule';
import {useHistoryModule} from './useHistoryModule';
import {useQueryModule} from './useQueryModule';
import {useStudioRuntime} from './useStudioRuntime';
import {useTableEditorModule} from './useTableEditorModule';
import {useUiShellModule} from './useUiShellModule';

export function useStudioController() {
  const runtime = useStudioRuntime();
  const tableEditorModule = useTableEditorModule(runtime);
  const connectionBrowserModule = useConnectionBrowserModule(runtime, {
    openEditTableEditor: tableEditorModule.openEditTableEditor,
  });
  const queryModule = useQueryModule(runtime);
  const erModule = useErModule(runtime);
  const erSnapshotModule = useErSnapshotModule(runtime);
  const historyModule = useHistoryModule(runtime);
  const uiShellModule = useUiShellModule(runtime);

  return {
    ...runtime,
    ...connectionBrowserModule,
    ...queryModule,
    ...erModule,
    ...erSnapshotModule,
    ...historyModule,
    ...tableEditorModule,
    ...uiShellModule,
    connectionBrowserModule,
    queryModule,
    erModule,
    erSnapshotModule,
    historyModule,
    tableEditorModule,
    uiShellModule,
  };
}

export type StudioController = ReturnType<typeof useStudioController>;
