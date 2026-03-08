<template>
  <StudioShell :controller="controller" />
</template>

<script setup lang="ts">
import StudioShell from './modules/studio/components/StudioShell.vue';
import {useStudioController} from './modules/studio/composables/useStudioController';

const controller = useStudioController();
</script>
