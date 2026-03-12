import {computed, nextTick, onBeforeUnmount, onMounted, readonly, ref, watch} from 'vue';
import {Modal, message} from 'ant-design-vue';
import type {Locale} from 'ant-design-vue/es/locale';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import enUS from 'ant-design-vue/es/locale/en_US';
import {localeSelectOptions, localeStorageKey, translateTextForLocale} from './messages';
import type {AppLocale} from './messages';

const currentLocale = ref<AppLocale>(resolveInitialLocale());
const antLocale = computed<Locale>(() => (currentLocale.value === 'zh-CN' ? zhCN : enUS));

let runtimeInstalled = false;

function resolveInitialLocale(): AppLocale {
  if (typeof window === 'undefined') {
    return 'zh-CN';
  }
  const stored = window.localStorage.getItem(localeStorageKey);
  if (stored === 'zh-CN' || stored === 'en-US') {
    return stored;
  }
  return (window.navigator.language || '').toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US';
}

function persistLocale(locale: AppLocale) {
  if (typeof window === 'undefined') {
    return;
  }
  window.localStorage.setItem(localeStorageKey, locale);
  document.documentElement.lang = locale;
}

persistLocale(currentLocale.value);

watch(currentLocale, (locale) => {
  persistLocale(locale);
});

export function translateText(text: string): string {
  return translateTextForLocale(text, currentLocale.value);
}

export function setLocale(locale: AppLocale) {
  currentLocale.value = locale;
}

function translateUnknownContent(content: unknown): unknown {
  return typeof content === 'string' ? translateText(content) : content;
}

function patchMessageApi() {
  const messageApi = message as unknown as Record<string, (...args: unknown[]) => unknown>;
  for (const key of ['info', 'success', 'warning', 'error', 'loading', 'open']) {
    const original = messageApi[key];
    if (typeof original !== 'function') {
      continue;
    }
    messageApi[key] = ((...args: unknown[]) => {
      if (args.length === 0) {
        return original();
      }
      const [first, ...rest] = args;
      if (typeof first === 'string') {
        return original(translateText(first), ...rest);
      }
      if (first && typeof first === 'object') {
        const config = first as Record<string, unknown>;
        return original({
          ...config,
          content: translateUnknownContent(config.content),
        }, ...rest);
      }
      return original(first, ...rest);
    }) as typeof original;
  }
}

function patchModalApi() {
  const modalApi = Modal as unknown as Record<string, (...args: unknown[]) => unknown>;
  for (const key of ['confirm', 'info', 'success', 'warning', 'error']) {
    const original = modalApi[key];
    if (typeof original !== 'function') {
      continue;
    }
    modalApi[key] = ((config: unknown) => {
      if (!config || typeof config !== 'object') {
        return original(config);
      }
      const source = config as Record<string, unknown>;
      return original({
        ...source,
        title: translateUnknownContent(source.title),
        content: translateUnknownContent(source.content),
        okText: translateUnknownContent(source.okText),
        cancelText: translateUnknownContent(source.cancelText),
      });
    }) as typeof original;
  }
}

function shouldSkipElement(element: Element | null): boolean {
  if (!element) {
    return true;
  }
  return Boolean(
    element.closest('script, style, code, pre, textarea, .monaco-editor, .view-lines, .inputarea, [data-i18n-ignore="true"]'),
  );
}

function translateTextNode(node: Text) {
  if (shouldSkipElement(node.parentElement)) {
    return;
  }
  const original = node.textContent ?? '';
  if (!original.trim()) {
    return;
  }
  const translated = translateText(original);
  if (translated !== original) {
    node.textContent = translated;
  }
}

function translateElementAttributes(element: Element) {
  if (shouldSkipElement(element)) {
    return;
  }
  for (const attributeName of ['title', 'placeholder', 'aria-label']) {
    const source = element.getAttribute(attributeName);
    if (!source) {
      continue;
    }
    const translated = translateText(source);
    if (translated !== source) {
      element.setAttribute(attributeName, translated);
    }
  }
}

function applyDomTranslations(root: Node) {
  if (root.nodeType === Node.TEXT_NODE) {
    translateTextNode(root as Text);
    return;
  }
  if (root.nodeType !== Node.ELEMENT_NODE) {
    return;
  }

  const element = root as Element;
  translateElementAttributes(element);

  const walker = document.createTreeWalker(element, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
  let current: Node | null = walker.currentNode;
  while (current) {
    if (current.nodeType === Node.TEXT_NODE) {
      translateTextNode(current as Text);
    } else if (current.nodeType === Node.ELEMENT_NODE) {
      translateElementAttributes(current as Element);
    }
    current = walker.nextNode();
  }
}

export function useDomI18n() {
  let observer: MutationObserver | null = null;

  onMounted(() => {
    if (typeof window === 'undefined') {
      return;
    }

    void nextTick(() => {
      if (document.body) {
        applyDomTranslations(document.body);
      }
    });

    observer = new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        if (mutation.type === 'childList') {
          mutation.addedNodes.forEach((node) => applyDomTranslations(node));
          continue;
        }
        if (mutation.type === 'characterData') {
          applyDomTranslations(mutation.target);
          continue;
        }
        if (mutation.type === 'attributes' && mutation.target instanceof Element) {
          translateElementAttributes(mutation.target);
        }
      }
    });

    observer.observe(document.body, {
      subtree: true,
      childList: true,
      characterData: true,
      attributes: true,
      attributeFilter: ['title', 'placeholder', 'aria-label'],
    });
  });

  watch(currentLocale, () => {
    if (typeof window === 'undefined') {
      return;
    }
    void nextTick(() => {
      if (document.body) {
        applyDomTranslations(document.body);
      }
    });
  });

  onBeforeUnmount(() => {
    observer?.disconnect();
  });
}

export function installAppI18nRuntime() {
  if (runtimeInstalled) {
    return;
  }
  patchMessageApi();
  patchModalApi();
  runtimeInstalled = true;
}

export function useAppI18n() {
  return {
    currentLocale: readonly(currentLocale),
    antLocale,
    localeSelectOptions,
    setLocale,
    translateText,
    useDomI18n,
  };
}

export type {AppLocale};
