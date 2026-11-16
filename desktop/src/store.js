'use strict';
// Per-user settings, encrypted with the OS (Windows DPAPI) via Electron's safeStorage when
// available, because playlist and guide URLs usually contain provider logins.
const fs = require('node:fs');
const path = require('node:path');

const DEFAULTS = { playlist: null, guide: null, lastChannelUrl: '', volume: 1 };

function createStore(dir, safeStorage) {
  const file = path.join(dir, 'settings.bin');
  const canEncrypt = () => { try { return !!safeStorage && safeStorage.isEncryptionAvailable(); } catch { return false; } };
  return {
    load() {
      try {
        const raw = fs.readFileSync(file);
        const json = raw[0] === 0x7b ? raw.toString('utf8') : safeStorage.decryptString(raw);
        return { ...DEFAULTS, ...JSON.parse(json) };
      } catch { return { ...DEFAULTS }; }
    },
    save(settings) {
      fs.mkdirSync(dir, { recursive: true });
      const json = JSON.stringify(settings);
      const data = canEncrypt() ? safeStorage.encryptString(json) : Buffer.from(json, 'utf8');
      const tmp = file + '.tmp';
      fs.writeFileSync(tmp, data);
      fs.renameSync(tmp, file);
    },
  };
}

module.exports = { createStore, DEFAULTS };
