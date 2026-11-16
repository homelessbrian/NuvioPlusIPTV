'use strict';
const { contextBridge, ipcRenderer } = require('electron');

const OK = /^[a-z]+:[A-Za-z]+$/;
const subs = new Map();

contextBridge.exposeInMainWorld('nuvio', {
  // Generic bridge: invoke("<feature>:<action>", ...args) -> Promise. Main registers the handlers.
  invoke: (channel, ...args) => (OK.test(channel) ? ipcRenderer.invoke(channel, ...args) : Promise.reject(new Error('bad channel'))),
  // Events pushed by main (send(channel, payload)). Returns an unsubscribe function.
  on: (channel, cb) => {
    if (!OK.test(channel)) throw new Error('bad channel');
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on(channel, h);
    return () => ipcRenderer.removeListener(channel, h);
  },
});
