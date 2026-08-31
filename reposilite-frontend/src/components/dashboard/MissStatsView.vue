<!--
  ~ Copyright (c) 2020-2026 dzikoysk
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useSession } from '../../store/session'
import { createErrorToast } from '../../helpers/toast'
import ViewHeader from '../util/ViewHeader.vue'

const { client } = useSession()

const repository = ref('maven-public')
const showAll = ref(false)
const threshold = ref(9)
const dedupeSeconds = ref(60)
const retentionDays = ref(7)
const entries = ref([])
const isLoading = ref(false)

function loadSettings() {
  client.value.maven.missStatsSettings()
    .then(response => {
      threshold.value = response.data.missShortCircuitThreshold
      dedupeSeconds.value = response.data.missStatsDedupeSeconds
      retentionDays.value = response.data.missStatsRetentionDays
    })
    .catch(error => createErrorToast(error))
}

function loadEntries() {
  if (!repository.value) return
  isLoading.value = true
  client.value.maven.missStatsList(repository.value, showAll.value)
    .then(response => {
      entries.value = response.data.entries || []
      threshold.value = response.data.threshold
      dedupeSeconds.value = response.data.dedupeSeconds
      retentionDays.value = response.data.retentionDays
    })
    .catch(error => createErrorToast(error))
    .finally(() => { isLoading.value = false })
}

function saveSettings() {
  client.value.maven.missStatsUpdateSettings({
    missShortCircuitThreshold: Number(threshold.value),
    missStatsDedupeSeconds: Number(dedupeSeconds.value),
    missStatsRetentionDays: Number(retentionDays.value),
  })
    .then(() => loadEntries())
    .catch(error => createErrorToast(error))
}

function resetEntry(path) {
  client.value.maven.missStatsResetEntry(repository.value, path)
    .then(() => loadEntries())
    .catch(error => createErrorToast(error))
}

function clearRepository() {
  if (!confirm(`Clear all miss stats for ${repository.value}?`)) return
  client.value.maven.missStatsClearRepository(repository.value)
    .then(() => loadEntries())
    .catch(error => createErrorToast(error))
}

watch([repository, showAll], () => loadEntries())

onMounted(() => {
  loadSettings()
  loadEntries()
})
</script>

<template>
  <div class="container mx-auto px-4 py-6">
    <ViewHeader
      title="404 Miss"
      description="Artifacts that repeatedly 404 skip upstream mirror probes after the threshold."
    />

    <div class="bg-white dark:bg-gray-900 rounded-lg shadow p-4 mb-4 flex flex-wrap gap-4 items-end">
      <label class="flex flex-col text-sm">
        Repository
        <input v-model="repository" class="input mt-1" type="text">
      </label>
      <label class="flex flex-col text-sm">
        Threshold
        <input v-model.number="threshold" class="input mt-1 w-24" type="number" min="1">
      </label>
      <label class="flex flex-col text-sm">
        Dedupe (s)
        <input v-model.number="dedupeSeconds" class="input mt-1 w-24" type="number" min="0">
      </label>
      <label class="flex flex-col text-sm">
        Retention (d)
        <input v-model.number="retentionDays" class="input mt-1 w-24" type="number" min="1">
      </label>
      <button class="btn btn-primary" type="button" @click="saveSettings">
        Save settings
      </button>
      <label class="flex items-center gap-2 text-sm">
        <input v-model="showAll" type="checkbox">
        Show all misses
      </label>
      <button class="btn btn-secondary" type="button" @click="clearRepository">
        Clear repository
      </button>
      <button class="btn btn-secondary" type="button" @click="loadEntries">
        Refresh
      </button>
    </div>

    <div v-if="isLoading" class="text-gray-500">
      Loading…
    </div>
    <div v-else-if="entries.length === 0" class="text-gray-500">
      No miss records (for current filter).
    </div>
    <ul v-else class="divide-y divide-gray-200 dark:divide-gray-700 bg-white dark:bg-gray-900 rounded-lg shadow">
      <li
        v-for="entry in entries"
        :key="entry.path"
        class="flex items-center gap-3 px-4 py-3 text-sm"
      >
        <span
          class="font-mono flex-1 break-all"
          :class="{ 'text-red-600 dark:text-red-400': entry.shortCircuited }"
        >
          /{{ entry.path }}
        </span>
        <span class="tabular-nums w-16 text-right">{{ entry.count }}×</span>
        <span class="text-gray-500 w-40 truncate" :title="entry.lastMissAt">{{ entry.lastMissAt }}</span>
        <button class="btn btn-sm" type="button" @click="resetEntry(entry.path)">
          Reset
        </button>
      </li>
    </ul>
  </div>
</template>
