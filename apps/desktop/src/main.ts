import {createApp} from 'vue';
import Antd from 'ant-design-vue';
import {loader} from '@guolao/vue-monaco-editor';
import * as monaco from 'monaco-editor';
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import jsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker';
import cssWorker from 'monaco-editor/esm/vs/language/css/css.worker?worker';
import htmlWorker from 'monaco-editor/esm/vs/language/html/html.worker?worker';
import tsWorker from 'monaco-editor/esm/vs/language/typescript/ts.worker?worker';
import 'ant-design-vue/dist/reset.css';
import App from './App.vue';
import './style.css';

const monacoEnvironment = {
  getWorker(_: unknown, label: string) {
    if (label === 'json') {
      return new jsonWorker();
    }
    if (label === 'css' || label === 'scss' || label === 'less') {
      return new cssWorker();
    }
    if (label === 'html' || label === 'handlebars' || label === 'razor') {
      return new htmlWorker();
    }
    if (label === 'typescript' || label === 'javascript') {
      return new tsWorker();
    }
    return new editorWorker();
  },
};

if (typeof self !== 'undefined') {
  (self as unknown as {MonacoEnvironment?: typeof monacoEnvironment}).MonacoEnvironment = monacoEnvironment;
}

loader.config({monaco});

createApp(App).use(Antd).mount('#app');
