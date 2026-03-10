export type SqlCopilotPackageVariant = 'minimal' | 'medium' | 'full';
export type RagProviderType = 'LOCAL_ONNX' | 'ONLINE_OPENAI_COMPAT';

const DEFAULT_VARIANT: SqlCopilotPackageVariant = 'medium';

function normalizeVariant(raw: string | undefined): SqlCopilotPackageVariant {
  if (raw === 'minimal' || raw === 'medium' || raw === 'full') {
    return raw;
  }
  return DEFAULT_VARIANT;
}

const variant = normalizeVariant((import.meta.env.VITE_PACKAGE_VARIANT || '').trim().toLowerCase());

export const sqlCopilotPackageVariant: SqlCopilotPackageVariant = variant;
export const minimalPackage = variant === 'minimal';

export const ragProviderOptions: Array<{ label: string; value: RagProviderType }> = minimalPackage
  ? [{ label: '在线 OpenAI 兼容', value: 'ONLINE_OPENAI_COMPAT' }]
  : [
      { label: '本地 ONNX', value: 'LOCAL_ONNX' },
      { label: '在线 OpenAI 兼容', value: 'ONLINE_OPENAI_COMPAT' },
    ];

export function normalizeRagProviderByPackage(value?: string): RagProviderType {
  if (minimalPackage) {
    return 'ONLINE_OPENAI_COMPAT';
  }
  if (value === 'ONLINE_OPENAI_COMPAT') {
    return 'ONLINE_OPENAI_COMPAT';
  }
  return 'LOCAL_ONNX';
}
