'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('nuvio', {
  getSettings: () => ipcRenderer.invoke('settings:get'),
  saveSettings: (s) => ipcRenderer.invoke('settings:save', s),
  pickFile: (kind) => ipcRenderer.invoke('dialog:pick', kind),
  load: () => ipcRenderer.invoke('library:load'),
  nowNext: (tvgIds) => ipcRenderer.invoke('guide:nowNext', tvgIds),
  schedule: (tvgId) => ipcRenderer.invoke('guide:schedule', tvgId),
});
