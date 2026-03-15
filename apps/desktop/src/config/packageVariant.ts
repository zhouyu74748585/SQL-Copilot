export type RagProviderType = 'LOCAL_ONNX' | 'ONLINE_OPENAI_COMPAT';

export const ragLocalOnnxEnabled = true;

export const ragProviderOptions: Array<{ label: string; value: RagProviderType }> = [
  { label: '本地 ONNX', value: 'LOCAL_ONNX' },
  { label: '在线 OpenAI 兼容', value: 'ONLINE_OPENAI_COMPAT' },
];

export function normalizeRagProviderByPackage(value?: string): RagProviderType {
  if (value === 'ONLINE_OPENAI_COMPAT') {
    return 'ONLINE_OPENAI_COMPAT';
  }
  return 'LOCAL_ONNX';
}
