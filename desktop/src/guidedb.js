'use strict';
// SQLite programme guide store (node:sqlite). See docs/guide.md.
const fs = require('node:fs');
const path = require('node:path');
const { createXmltvParser } = require('./xmltv');

const SCHEMA_VERSION = 1;
const BACK_MS = 6 * 3600e3;
const AHEAD_MS = 7 * 24 * 3600e3;
const STALE_MS = 12 * 3600e3;
const MAX_DESC = 4000;

// Same idea as the Android SportsCatalog.norm: lowercase, '&' -> 'and', accents stripped,
// everything that is not a letter or digit becomes one space.
function norm(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, ' and ')
    .normalize('NFD').replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
}

const keyOf = (id) => String(id == null ? '' : id).trim().toLowerCase();
const rowToProg = (r) => ({ start: r.start, stop: r.stop, title: r.title, desc: r.descr });
const rowToHit = (r) => ({ tvgId: r.cid, start: r.start, stop: r.stop, title: r.title, desc: r.descr });

class GuideDb {
  constructor(file) {
    const { DatabaseSync } = require('node:sqlite');
    this.file = file;
    this.memory = file === ':memory:';
    if (!this.memory) fs.mkdirSync(path.dirname(file), { recursive: true });
    this.db = new DatabaseSync(file);
    if (!this.memory) { this.db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;'); }
    this._init();
    this.importing = null;
  }

  _init() {
    const db = this.db;
    const v = db.prepare('PRAGMA user_version').get().user_version;
    if (v !== SCHEMA_VERSION) {
      db.exec('DROP TABLE IF EXISTS fts; DROP TABLE IF EXISTS listings; DROP TABLE IF EXISTS channels; DROP TABLE IF EXISTS meta;');
    }
    db.exec(`
      CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
      CREATE TABLE IF NOT EXISTS channels (key TEXT PRIMARY KEY, id TEXT NOT NULL, name TEXT NOT NULL DEFAULT '');
      CREATE TABLE IF NOT EXISTS listings (
        id INTEGER PRIMARY KEY, ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL,
        title TEXT NOT NULL DEFAULT '', descr TEXT NOT NULL DEFAULT '');
      CREATE UNIQUE INDEX IF NOT EXISTS listings_ch_start ON listings(ch, start);
      CREATE INDEX IF NOT EXISTS listings_stop ON listings(stop);
      CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(t, d, tokenize='unicode61 remove_diacritics 0');
      PRAGMA user_version = ${SCHEMA_VERSION};
    `);
    this.q = {
      nn: db.prepare('SELECT start, stop, title, descr FROM listings WHERE ch = ? AND stop > ? ORDER BY start LIMIT 3'),
      range: db.prepare('SELECT start, stop, title, descr FROM listings WHERE ch = ? AND start < ? AND stop > ? ORDER BY start'),
      meta: db.prepare('SELECT value FROM meta WHERE key = ?'),
      setMeta: db.prepare('INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'),
    };
  }

  close() { try { this.db.close(); } catch { /* already closed */ } }

  /** Changes whenever the stored guide is replaced (lets other features cache per guide). */
  revision() { return `${this.getMeta('signature') || ''}|${this.getMeta('loadedAt') || ''}`; }
  getMeta(key) { const r = this.q.meta.get(key); return r ? r.value : null; }
  setMeta(key, value) { this.q.setMeta.run(key, String(value)); }

  stats() {
    const programmes = this.db.prepare('SELECT COUNT(*) AS n FROM listings').get().n;
    const channels = this.db.prepare('SELECT COUNT(DISTINCT ch) AS n FROM listings').get().n;
    return { channels, programmes };
  }

  // True when the stored guide came from `signature` and is younger than maxAgeMs.
  isFresh(signature, now = Date.now(), maxAgeMs = STALE_MS) {
    if (this.getMeta('signature') !== signature) return false;
    const at = Number(this.getMeta('loadedAt'));
    return Number.isFinite(at) && now - at < maxAgeMs && now - at > -3600e3 && this.stats().programmes > 0;
  }

  // Drops listings that ended more than 6h ago.
  prune(now = Date.now()) {
    const cut = now - BACK_MS;
    this.db.exec('BEGIN');
    try {
      this.db.prepare('DELETE FROM fts WHERE rowid IN (SELECT id FROM listings WHERE stop <= ?)').run(cut);
      this.db.prepare('DELETE FROM listings WHERE stop <= ?').run(cut);
      this.db.exec('COMMIT');
    } catch (e) { this.db.exec('ROLLBACK'); throw e; }
  }

  /**
   * Replaces the whole guide with the XMLTV text produced by `chunks` (async iterable of strings).
   * Everything happens in one transaction on a separate connection, so readers keep seeing the old
   * guide until the commit and any failure (bad XML, network error, empty result) keeps it.
   * Returns { channels, programmes }.
   */
  async replaceFrom(chunks, { now = Date.now(), signature = '' } = {}) {
    if (this.importing) throw new Error('A guide import is already running.');
    const fromMs = now - BACK_MS;
    const toMs = now + AHEAD_MS;
    let w = this.db;
    let own = false;
    if (!this.memory) { const { DatabaseSync } = require('node:sqlite'); w = new DatabaseSync(this.file); own = true; w.exec('PRAGMA busy_timeout=30000'); }
    this.importing = true;
    try {
      w.exec('BEGIN IMMEDIATE');
      try {
        w.exec('DELETE FROM listings; DELETE FROM channels; DELETE FROM fts;');
        const insL = w.prepare('INSERT OR IGNORE INTO listings(ch, start, stop, title, descr) VALUES(?,?,?,?,?)');
        const insF = w.prepare('INSERT INTO fts(rowid, t, d) VALUES(?,?,?)');
        const insC = w.prepare('INSERT OR IGNORE INTO channels(key, id, name) VALUES(?,?,?)');
        const updC = w.prepare("UPDATE channels SET name = ? WHERE key = ? AND name = ''");
        const seen = new Set();
        let programmes = 0;
        const touch = (id) => { const k = keyOf(id); if (!k) return null; if (!seen.has(k)) { seen.add(k); insC.run(k, String(id).trim(), ''); } return k; };
        const parser = createXmltvParser({
          fromMs, toMs,
          onChannel(id, name) { const k = touch(id); if (k) updC.run(String(name).trim(), k); },
          onProgramme(p) {
            const k = touch(p.channel);
            if (!k) return;
            const desc = (p.desc || '').slice(0, MAX_DESC);
            const r = insL.run(k, p.start, p.stop, p.title || '', desc);
            if (r.changes) { insF.run(r.lastInsertRowid, norm(p.title), norm(desc)); programmes++; }
          },
        });
        for await (const c of chunks) parser.write(c);
        parser.end();
        if (!programmes) throw new Error('The guide has no programmes for the next days.');
        w.prepare('DELETE FROM channels WHERE key NOT IN (SELECT DISTINCT ch FROM listings)').run();
        const setM = w.prepare('INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value');
        setM.run('signature', signature);
        setM.run('loadedAt', String(now));
        w.exec('COMMIT');
      } catch (e) { try { w.exec('ROLLBACK'); } catch { /* none open */ } throw e; }
      if (own) { try { w.exec('PRAGMA wal_checkpoint(TRUNCATE)'); } catch { /* busy readers */ } }
    } finally {
      this.importing = null;
      if (own) { try { w.close(); } catch { /* ignore */ } }
    }
    return this.stats();
  }

  nowNext(tvgIds, at = Date.now()) {
    const out = {};
    for (const id of tvgIds || []) {
      const rows = this.q.nn.all(keyOf(id), at);
      let now = null; let next = null;
      for (const r of rows) {
        if (r.start <= at) { if (!now) now = rowToProg(r); } else { next = rowToProg(r); break; }
      }
      if (now || next) out[id] = { now, next };
    }
    return out;
  }

  schedule(tvgId, fromMs, toMs) {
    const from = Number.isFinite(fromMs) ? fromMs : -8.64e15;
    const to = Number.isFinite(toMs) ? toMs : 8.64e15;
    return this.q.range.all(keyOf(tvgId), to, from).map(rowToProg);
  }

  range(tvgIds, fromMs, toMs) {
    const out = {};
    for (const id of tvgIds || []) {
      const rows = this.q.range.all(keyOf(id), toMs, fromMs);
      if (rows.length) out[id] = rows.map(rowToProg);
    }
    return out;
  }

  // Free-text programme search (title + description), every word a prefix, upcoming and running only.
  search(query, limit = 100, now = Date.now()) {
    const toks = norm(query).split(' ').filter(Boolean).slice(0, 8);
    if (!toks.length) return [];
    const cap = Math.min(Math.max(1, limit | 0 || 100), 500);
    if (toks.every((t) => /^[a-z0-9]+$/.test(t))) {
      const m = '{t d} : (' + toks.map((t) => `"${t}"*`).join(' AND ') + ')';
      return this.db.prepare(
        `SELECT l.start, l.stop, l.title, l.descr, c.id AS cid FROM fts JOIN listings l ON l.id = fts.rowid
         JOIN channels c ON c.key = l.ch WHERE fts MATCH ? AND l.stop > ? ORDER BY l.start LIMIT ?`
      ).all(m, now, cap).map(rowToHit);
    }
    // Non-ASCII words: scan upcoming listings and prefix-match tokens in JS.
    const rows = this.db.prepare(
      'SELECT l.start, l.stop, l.title, l.descr, c.id AS cid FROM listings l JOIN channels c ON c.key = l.ch WHERE l.stop > ? ORDER BY l.start'
    ).iterate(now);
    const hits = [];
    for (const r of rows) {
      const words = norm(r.title + ' ' + r.descr).split(' ');
      if (toks.every((t) => words.some((w) => w.startsWith(t)))) { hits.push(rowToHit(r)); if (hits.length >= cap) break; }
    }
    return hits;
  }

  /**
   * Programmes overlapping [fromMs, toMs] whose normalised title (and description unless titleOnly)
   * contains ANY of the given words/phrases as whole words in sequence ("real madrid" matches
   * "Real Madrid v Barcelona" but not "Surreal Madrid"). Never skips a valid match: phrases that
   * cannot be expressed as an FTS query (non-ASCII letters) are checked by scanning the window.
   */
  findByWords(words, fromMs, toMs, { titleOnly = false, limit = 20000 } = {}) {
    const phrases = [...new Set((words || []).map(norm).filter(Boolean))];
    if (!phrases.length) return [];
    const hits = new Map();
    const add = (r) => hits.set(r.cid + '|' + r.start, rowToHit(r));
    const fts = phrases.filter((p) => /^[a-z0-9 ]+$/.test(p));
    const scan = phrases.filter((p) => !/^[a-z0-9 ]+$/.test(p));
    if (fts.length) {
      const m = (titleOnly ? 't' : '{t d}') + ' : (' + fts.map((p) => `"${p}"`).join(' OR ') + ')';
      for (const r of this.db.prepare(
        `SELECT l.start, l.stop, l.title, l.descr, c.id AS cid FROM fts JOIN listings l ON l.id = fts.rowid
         JOIN channels c ON c.key = l.ch WHERE fts MATCH ? AND l.stop > ? AND l.start < ? ORDER BY l.start LIMIT ?`
      ).all(m, fromMs, toMs, limit)) add(r);
    }
    if (scan.length) {
      const padded = scan.map((p) => ` ${p} `);
      for (const r of this.db.prepare(
        'SELECT l.start, l.stop, l.title, l.descr, c.id AS cid FROM listings l JOIN channels c ON c.key = l.ch WHERE l.stop > ? AND l.start < ?'
      ).iterate(fromMs, toMs)) {
        const text = ` ${norm(titleOnly ? r.title : r.title + ' ' + r.descr)} `;
        if (padded.some((p) => text.includes(p))) add(r);
      }
    }
    return [...hits.values()].sort((a, b) => a.start - b.start).slice(0, limit);
  }
}

let current = null;
function openGuideDb(file) { if (current) current.close(); current = new GuideDb(file); return current; }
function getDb() { return current; }
function setDb(db) { current = db; }

module.exports = { GuideDb, openGuideDb, getDb, setDb, norm, BACK_MS, AHEAD_MS, STALE_MS };
