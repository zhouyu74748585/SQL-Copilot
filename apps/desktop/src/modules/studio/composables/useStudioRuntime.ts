import {createStudioBrowserRuntime} from './useStudioRuntime/browserRuntime';
import {createStudioQueryRuntime} from './useStudioRuntime/queryRuntime';
import {setupStudioRuntimeLifecycle} from './useStudioRuntime/lifecycle';
import {createStudioRuntimeState} from './useStudioRuntime/state';

export function useStudioRuntime() {
  const state = createStudioRuntimeState();
  const browserRuntime = createStudioBrowserRuntime(state);
  const queryRuntime = createStudioQueryRuntime(state);

  const runtime = {
    ...state,
    ...browserRuntime,
    ...queryRuntime,
  };

  setupStudioRuntimeLifecycle(runtime);
  return runtime;
}

export type StudioRuntime = ReturnType<typeof useStudioRuntime>;
