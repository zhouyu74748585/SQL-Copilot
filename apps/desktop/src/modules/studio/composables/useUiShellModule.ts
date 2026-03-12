import {onBeforeUnmount, onMounted, watch} from 'vue';
import type {StudioRuntime} from './useStudioRuntime';

export interface UiShellModule {
  toggleTheme: () => void;
  loadUiThemePreference: () => void;
  persistUiThemePreference: () => void;
  handleWindowResize: () => void;
  startResizeLeftPane: (event: MouseEvent) => void;
  handleResizeLeftPane: (event: MouseEvent) => void;
  stopResizeLeftPane: () => void;
  startResizeBrowserPane: (event: MouseEvent) => void;
  handleResizeBrowserPane: (event: MouseEvent) => void;
  stopResizeBrowserPane: () => void;
  startResizeErPane: (event: MouseEvent) => void;
  handleResizeErPane: (event: MouseEvent) => void;
  stopResizeErPane: () => void;
  startResizeQueryPane: (event: MouseEvent) => void;
  handleResizeQueryPane: (event: MouseEvent) => void;
  stopResizeQueryPane: () => void;
  startResizeQueryEditorSections: (event: MouseEvent) => void;
  handleResizeQueryEditorSections: (event: MouseEvent) => void;
  stopResizeQueryEditorSections: () => void;
}

interface UiShellDeps {
  handleBrowserClipboardKeydown: (event: KeyboardEvent) => void;
  handleErRelationDeleteKeydown: (event: KeyboardEvent) => void;
}

export function useUiShellModule(runtime: StudioRuntime, deps: UiShellDeps): UiShellModule {
  const QUERY_EDITOR_SECTION_MIN_HEIGHT = 180;
  const QUERY_RESULT_SECTION_MIN_HEIGHT = 240;
  const QUERY_EDITOR_SECTION_SPLITTER_HEIGHT = 8;
  const QUERY_EDITOR_PANE_TITLE_HEIGHT = 40;

  function clampQueryEditorSectionHeight(paneHeight?: number) {
    const resolvedPaneHeight = paneHeight ?? runtime.queryEditorPaneRef.value?.clientHeight ?? 0;
    if (!resolvedPaneHeight) {
      runtime.queryEditorSectionHeight.value = Math.max(
        QUERY_EDITOR_SECTION_MIN_HEIGHT,
        runtime.queryEditorSectionHeight.value,
      );
      return;
    }
    const maxHeight = Math.max(
      QUERY_EDITOR_SECTION_MIN_HEIGHT,
      resolvedPaneHeight
      - QUERY_EDITOR_PANE_TITLE_HEIGHT
      - QUERY_EDITOR_SECTION_SPLITTER_HEIGHT
      - QUERY_RESULT_SECTION_MIN_HEIGHT,
    );
    runtime.queryEditorSectionHeight.value = Math.min(
      maxHeight,
      Math.max(QUERY_EDITOR_SECTION_MIN_HEIGHT, runtime.queryEditorSectionHeight.value),
    );
  }

  function toggleTheme() {
    runtime.uiTheme.value = runtime.uiTheme.value === 'dark' ? 'light' : 'dark';
  }

  function loadUiThemePreference() {
    if (typeof window === 'undefined') {
      return;
    }
    try {
      const raw = window.localStorage.getItem(runtime.uiThemeStorageKey);
      if (raw === 'light' || raw === 'dark') {
        runtime.uiTheme.value = raw;
        return;
      }
      runtime.uiTheme.value = 'light';
    } catch {
      runtime.uiTheme.value = 'light';
    }
  }

  function persistUiThemePreference() {
    if (typeof window === 'undefined') {
      return;
    }
    try {
      window.localStorage.setItem(runtime.uiThemeStorageKey, runtime.uiTheme.value);
    } catch {
      // ignore
    }
  }

  function handleWindowResize() {
    runtime.viewportHeight.value = window.innerHeight;
    runtime.viewportWidth.value = window.innerWidth;
    window.requestAnimationFrame(() => {
      clampQueryEditorSectionHeight();
    });
  }

  function handleWindowKeydown(event: KeyboardEvent) {
    deps.handleErRelationDeleteKeydown(event);
    deps.handleBrowserClipboardKeydown(event);
  }

  function startResizeLeftPane(event: MouseEvent) {
    if (runtime.viewportWidth.value < 1200) {
      return;
    }
    event.preventDefault();
    runtime.leftPaneResizeState.resizing = true;
    runtime.leftPaneResizeState.startX = event.clientX;
    runtime.leftPaneResizeState.startWidth = runtime.leftPaneWidth.value;
    window.addEventListener('mousemove', handleResizeLeftPane);
    window.addEventListener('mouseup', stopResizeLeftPane);
  }

  function handleResizeLeftPane(event: MouseEvent) {
    if (!runtime.leftPaneResizeState.resizing) {
      return;
    }
    const delta = event.clientX - runtime.leftPaneResizeState.startX;
    const next = runtime.leftPaneResizeState.startWidth + delta;
    runtime.leftPaneWidth.value = Math.min(420, Math.max(220, next));
  }

  function stopResizeLeftPane() {
    if (!runtime.leftPaneResizeState.resizing) {
      return;
    }
    runtime.leftPaneResizeState.resizing = false;
    window.removeEventListener('mousemove', handleResizeLeftPane);
    window.removeEventListener('mouseup', stopResizeLeftPane);
  }

  function startResizeBrowserPane(event: MouseEvent) {
    if (runtime.activeWorkbenchTab.value !== runtime.browserTabKey && !runtime.activeKnowledgeTab.value) {
      return;
    }
    event.preventDefault();
    runtime.browserPaneResizeState.resizing = true;
    runtime.browserPaneResizeState.startX = event.clientX;
    runtime.browserPaneResizeState.startWidth = runtime.browserRightPaneWidth.value;
    window.addEventListener('mousemove', handleResizeBrowserPane);
    window.addEventListener('mouseup', stopResizeBrowserPane);
  }

  function handleResizeBrowserPane(event: MouseEvent) {
    if (!runtime.browserPaneResizeState.resizing) {
      return;
    }
    const delta = runtime.browserPaneResizeState.startX - event.clientX;
    const next = runtime.browserPaneResizeState.startWidth + delta;
    runtime.browserRightPaneWidth.value = Math.min(760, Math.max(280, next));
  }

  function stopResizeBrowserPane() {
    if (!runtime.browserPaneResizeState.resizing) {
      return;
    }
    runtime.browserPaneResizeState.resizing = false;
    window.removeEventListener('mousemove', handleResizeBrowserPane);
    window.removeEventListener('mouseup', stopResizeBrowserPane);
  }

  function startResizeErPane(event: MouseEvent) {
    if (!runtime.activeErTab.value) {
      return;
    }
    event.preventDefault();
    runtime.erPaneResizeState.resizing = true;
    runtime.erPaneResizeState.startX = event.clientX;
    runtime.erPaneResizeState.startWidth = runtime.erRightPaneWidth.value;
    window.addEventListener('mousemove', handleResizeErPane);
    window.addEventListener('mouseup', stopResizeErPane);
  }

  function handleResizeErPane(event: MouseEvent) {
    if (!runtime.erPaneResizeState.resizing) {
      return;
    }
    const delta = runtime.erPaneResizeState.startX - event.clientX;
    const next = runtime.erPaneResizeState.startWidth + delta;
    runtime.erRightPaneWidth.value = Math.min(860, Math.max(320, next));
  }

  function stopResizeErPane() {
    if (!runtime.erPaneResizeState.resizing) {
      return;
    }
    runtime.erPaneResizeState.resizing = false;
    window.removeEventListener('mousemove', handleResizeErPane);
    window.removeEventListener('mouseup', stopResizeErPane);
  }

  function startResizeQueryPane(event: MouseEvent) {
    if ((!runtime.activeQueryTab.value && !runtime.activeTableEditorTab.value && !runtime.activeTableDataTab.value) || runtime.viewportWidth.value < 1200) {
      return;
    }
    event.preventDefault();
    runtime.queryPaneResizeState.resizing = true;
    runtime.queryPaneResizeState.startX = event.clientX;
    runtime.queryPaneResizeState.startWidth = runtime.queryRightPaneWidth.value;
    window.addEventListener('mousemove', handleResizeQueryPane);
    window.addEventListener('mouseup', stopResizeQueryPane);
  }

  function handleResizeQueryPane(event: MouseEvent) {
    if (!runtime.queryPaneResizeState.resizing) {
      return;
    }
    const delta = runtime.queryPaneResizeState.startX - event.clientX;
    const next = runtime.queryPaneResizeState.startWidth + delta;
    runtime.queryRightPaneWidth.value = Math.min(820, Math.max(320, next));
  }

  function stopResizeQueryPane() {
    if (!runtime.queryPaneResizeState.resizing) {
      return;
    }
    runtime.queryPaneResizeState.resizing = false;
    window.removeEventListener('mousemove', handleResizeQueryPane);
    window.removeEventListener('mouseup', stopResizeQueryPane);
  }

  function startResizeQueryEditorSections(event: MouseEvent) {
    if (!runtime.activeQueryTab.value || !runtime.queryEditorPaneRef.value) {
      return;
    }
    event.preventDefault();
    clampQueryEditorSectionHeight();
    runtime.queryEditorSectionResizeState.resizing = true;
    runtime.queryEditorSectionResizeState.startY = event.clientY;
    runtime.queryEditorSectionResizeState.startHeight = runtime.queryEditorSectionHeight.value;
    runtime.queryEditorSectionResizeState.paneHeight = runtime.queryEditorPaneRef.value.clientHeight;
    window.addEventListener('mousemove', handleResizeQueryEditorSections);
    window.addEventListener('mouseup', stopResizeQueryEditorSections);
  }

  function handleResizeQueryEditorSections(event: MouseEvent) {
    if (!runtime.queryEditorSectionResizeState.resizing) {
      return;
    }
    const delta = event.clientY - runtime.queryEditorSectionResizeState.startY;
    const maxHeight = Math.max(
      QUERY_EDITOR_SECTION_MIN_HEIGHT,
      runtime.queryEditorSectionResizeState.paneHeight
      - QUERY_EDITOR_PANE_TITLE_HEIGHT
      - QUERY_EDITOR_SECTION_SPLITTER_HEIGHT
      - QUERY_RESULT_SECTION_MIN_HEIGHT,
    );
    const next = runtime.queryEditorSectionResizeState.startHeight + delta;
    runtime.queryEditorSectionHeight.value = Math.min(
      maxHeight,
      Math.max(QUERY_EDITOR_SECTION_MIN_HEIGHT, next),
    );
  }

  function stopResizeQueryEditorSections() {
    if (!runtime.queryEditorSectionResizeState.resizing) {
      return;
    }
    runtime.queryEditorSectionResizeState.resizing = false;
    window.removeEventListener('mousemove', handleResizeQueryEditorSections);
    window.removeEventListener('mouseup', stopResizeQueryEditorSections);
  }

  onMounted(() => {
    window.addEventListener('resize', handleWindowResize);
    window.addEventListener('keydown', handleWindowKeydown);
    loadUiThemePreference();
  });

  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleWindowResize);
    window.removeEventListener('keydown', handleWindowKeydown);
    window.removeEventListener('mousemove', handleResizeLeftPane);
    window.removeEventListener('mouseup', stopResizeLeftPane);
    window.removeEventListener('mousemove', handleResizeBrowserPane);
    window.removeEventListener('mouseup', stopResizeBrowserPane);
    window.removeEventListener('mousemove', handleResizeErPane);
    window.removeEventListener('mouseup', stopResizeErPane);
    window.removeEventListener('mousemove', handleResizeQueryPane);
    window.removeEventListener('mouseup', stopResizeQueryPane);
    window.removeEventListener('mousemove', handleResizeQueryEditorSections);
    window.removeEventListener('mouseup', stopResizeQueryEditorSections);
  });

  watch(
    () => runtime.uiTheme.value,
    () => {
      persistUiThemePreference();
    },
  );

  return {
    toggleTheme,
    loadUiThemePreference,
    persistUiThemePreference,
    handleWindowResize,
    startResizeLeftPane,
    handleResizeLeftPane,
    stopResizeLeftPane,
    startResizeBrowserPane,
    handleResizeBrowserPane,
    stopResizeBrowserPane,
    startResizeErPane,
    handleResizeErPane,
    stopResizeErPane,
    startResizeQueryPane,
    handleResizeQueryPane,
    stopResizeQueryPane,
    startResizeQueryEditorSections,
    handleResizeQueryEditorSections,
    stopResizeQueryEditorSections,
  };
}
