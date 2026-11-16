'use strict';
// Mock Xtream Codes server for tests. start(overrides) -> { url, port, close(), hits }
const http = require('node:http');

const USER = 'bob';
const PASS = 's3cr3t&pw';

function defaults() {
  return {
    user_info: { auth: 1, status: 'Active', exp_date: String(Math.floor(Date.now() / 1000) + 86400 * 30), active_cons: '1', max_connections: '2', allowed_output_formats: ['m3u8', 'ts'] },
    server_info: { url: 'x', port: '80', timezone: 'America/New_York' },
    live_categories: [{ category_id: '1', category_name: 'News', parent_id: 0 }, { category_id: 2, category_name: 'Sports', parent_id: 0 }],
    live_streams: [
      { num: 2, name: 'Sports One', stream_id: 102, stream_icon: 'http://i/2.png', epg_channel_id: 'sports1.us', category_id: '2', tv_archive: 1, tv_archive_duration: '3' },
      { num: 1, name: 'News 24', stream_id: '101', stream_icon: '', epg_channel_id: '', category_id: '1', tv_archive: 0, tv_archive_duration: 0 },
      { num: 3, name: 'Orphan', stream_id: 103, category_id: '99' },
    ],
    vod_categories: [{ category_id: '10', category_name: 'Action' }, { category_id: '11', category_name: 'Drama' }],
    vod_streams: [
      { stream_id: '501', name: 'Big Explosion (2019)', stream_icon: 'http://i/501.jpg', rating: '7.5', added: '1700000000', category_id: '10', container_extension: 'mkv' },
      { stream_id: 502, name: 'Quiet Drama', category_id: '11', added: '1600000000' },
      { stream_id: 503, name: 'Mystery Box', category_id: '777', container_extension: 'mp4', added: '1800000000' },
    ],
    vod_info: { 501: { info: { movie_image: 'http://i/501.jpg', plot: 'Things explode.', cast: 'A, B', duration_secs: 7200, duration: '02:00:00', releasedate: '2019-05-01', rating: '7.5', backdrop_path: ['http://i/bd.jpg'] }, movie_data: { stream_id: 501, name: 'Big Explosion (2019)', container_extension: 'mkv' } }, 502: [] },
    series_categories: [{ category_id: '20', category_name: 'Shows' }],
    series: [{ series_id: '900', name: 'Long Show', cover: 'http://i/900.jpg', category_id: '20', rating_5based: '4', releaseDate: '2015-01-01', last_modified: '1700000000' }],
    series_info: { 900: {
      info: { name: 'Long Show', cover: 'http://i/900.jpg', plot: 'A show.', rating_5based: '4', releaseDate: '2015-01-01', backdrop_path: [] },
      seasons: [{ season_number: 1, name: 'Season 1' }],
      episodes: { 1: [{ id: '7002', episode_num: '2', title: 'Two', container_extension: 'mp4', season: 1, info: { duration_secs: 1800 } }, { id: 7001, episode_num: 1, title: 'One', container_extension: 'mkv', season: 1, info: [] }], 2: [{ id: '7003', episode_num: 1, title: 'S2E1', container_extension: 'mp4', season: 2, info: {} }] },
    } },
    epg: { epg_listings: [{ title: Buffer.from('Evening News').toString('base64'), description: Buffer.from('Headlines').toString('base64'), start_timestamp: '1700000000', stop_timestamp: '1700003600' }] },
  };
}

async function start(over = {}) {
  const data = { ...defaults(), ...over };
  const hits = [];
  const server = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    hits.push(req.url);
    const send = (body, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(typeof body === 'string' ? body : JSON.stringify(body)); };
    if (u.pathname !== '/player_api.php') return send('nope', 404);
    if (data.rawResponse) return send(data.rawResponse);
    if (data.status) return send('denied', data.status);
    const q = u.searchParams;
    if (q.get('username') !== USER || q.get('password') !== PASS) return send({ user_info: { auth: 0 } });
    switch (q.get('action')) {
      case null: return send({ user_info: data.user_info, server_info: data.server_info });
      case 'get_live_categories': return send(data.live_categories);
      case 'get_live_streams': return send(data.live_streams);
      case 'get_vod_categories': return send(data.vod_categories);
      case 'get_vod_streams': return send(q.get('category_id') ? data.vod_streams.filter((m) => String(m.category_id) === q.get('category_id')) : data.vod_streams);
      case 'get_vod_info': return send(data.vod_info[q.get('vod_id')] || []);
      case 'get_series_categories': return send(data.series_categories);
      case 'get_series': return send(data.series);
      case 'get_series_info': return send(data.series_info[q.get('series_id')] || []);
      case 'get_short_epg': return send(data.epg);
      default: return send([]);
    }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  return { url: `http://127.0.0.1:${port}`, port, hits, close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(r); }) };
}

module.exports = { start, USER, PASS };
