import type {AppLocale} from '../i18n/messages';
import {translateTextForLocale} from '../i18n/messages';

export type RagProviderType = 'LOCAL_ONNX' | 'ONLINE_OPENAI_COMPAT';

export const ragLocalOnnxEnabled = true;

export function getRagProviderOptions(locale: AppLocale): Array<{ label: string; value: RagProviderType }> {
  return [
    {label: translateTextForLocale('本地 ONNX', locale), value: 'LOCAL_ONNX'},
    {label: translateTextForLocale('在线 OpenAI 兼容', locale), value: 'ONLINE_OPENAI_COMPAT'},
  ];
}

export function normalizeRagProviderByPackage(value?: string): RagProviderType {
  if (value === 'ONLINE_OPENAI_COMPAT') {
    return 'ONLINE_OPENAI_COMPAT';
  }
  return 'LOCAL_ONNX';
}
