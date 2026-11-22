package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import org.json.JSONObject

/**
 * Live TV fork: the Live TV, Sports and On Demand rows on the add-ons phone page (Home layout tab).
 * Add a row (the same choices as the TV), Edit (name, what it shows, and the same options as the
 * TV's row options) and Remove; moving and turning rows on/off is the page's own catalog list.
 */
internal object LiveRowsWebPage {

    /** The Add a row button, shown above Home's rows once the TV says rows can be set up. */
    fun addButtonHtml(context: Context): String =
        """<div class="add-section" id="liveAddRow" style="display:none">
        <button class="btn" onclick="openAddRow()" style="width:100%">${context.getString(R.string.home_rows_add_button)}</button>
        <div class="catalog-meta" style="margin-top:0.4rem;text-align:center">${context.getString(R.string.home_rows_add_button_hint)}</div>
      </div>"""

    /** The sheet both Add a row and Edit use. */
    fun sheetHtml(): String = """
  <div class="import-overlay" id="liveSheet" onclick="if (event.target === this) closeLiveSheet()">
    <div class="import-modal live-modal">
      <div class="live-head">
        <button class="btn-icon" id="liveBack" onclick="liveSheetBack()" aria-label="Back">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
        </button>
        <div style="flex:1;min-width:0">
          <div class="live-title" id="liveTitle"></div>
          <div class="catalog-meta" id="liveSubtitle"></div>
        </div>
      </div>
      <div class="live-body" id="liveBody"></div>
      <div style="display:flex;gap:0.75rem;margin-top:1rem">
        <button class="btn" onclick="closeLiveSheet()" style="flex:1" id="liveClose"></button>
      </div>
    </div>
  </div>
"""

    fun css(): String = """
  .live-modal { max-width: 560px; max-height: 88vh; display: flex; flex-direction: column; }
  .live-head { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.75rem; }
  .live-title { font-size: 1.1rem; font-weight: 700; }
  .live-body { overflow-y: auto; flex: 1; margin: 0 -0.25rem; padding: 0 0.25rem; }
  .live-heading { font-size: 0.7rem; letter-spacing: 0.08em; text-transform: uppercase; color: rgba(255,255,255,0.45); margin: 1rem 0 0.4rem; }
  .live-choice { display: flex; align-items: center; gap: 0.75rem; width: 100%; text-align: left; background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08); border-radius: 12px; padding: 0.75rem 0.9rem; margin-bottom: 0.45rem; color: #fff; font-family: inherit; cursor: pointer; -webkit-tap-highlight-color: transparent; }
  .live-choice:active { background: rgba(255,255,255,0.1); }
  .live-choice .live-icon { font-size: 1.1rem; width: 1.5rem; text-align: center; flex-shrink: 0; }
  .live-choice .live-text { flex: 1; min-width: 0; }
  .live-choice .live-name { font-size: 0.92rem; font-weight: 600; }
  .live-choice .live-desc { font-size: 0.78rem; color: rgba(255,255,255,0.5); margin-top: 0.15rem; }
  .live-choice .live-value { font-size: 0.78rem; color: rgba(255,255,255,0.5); flex-shrink: 0; }
  .live-choice .live-arrow { color: rgba(255,255,255,0.35); flex-shrink: 0; }
  .live-field { margin-bottom: 0.9rem; }
  .live-field label.live-label { display: block; font-size: 0.8rem; color: rgba(255,255,255,0.6); margin-bottom: 0.35rem; }
  .live-field input[type="text"], .live-field select { width: 100%; background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.12); border-radius: 10px; padding: 0.7rem 0.85rem; color: #fff; font-family: inherit; font-size: 0.9rem; }
  .live-field select option { background: #111; }
  .live-switch { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.9rem; }
  .live-switch .live-text { flex: 1; }
  .live-switch .live-name { font-size: 0.9rem; }
  .live-switch .live-desc { font-size: 0.75rem; color: rgba(255,255,255,0.45); margin-top: 0.1rem; }
  .badge-live { display: inline-block; font-size: 0.6rem; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; padding: 0.15rem 0.45rem; border-radius: 6px; margin-left: 0.45rem; vertical-align: middle; background: rgba(95,208,226,0.15); color: rgba(150,225,238,0.95); }
  .live-remove { width: 100%; border-color: rgba(207,102,121,0.35) !important; color: #CF6679 !important; margin-top: 0.5rem; }
"""

    /** The page's script for these rows, with its words in the TV's language. */
    fun script(context: Context): String {
        val res = context.resources
        fun s(id: Int) = context.getString(id)
        fun count(id: Int) = JSONObject().apply {
            put("one", res.getQuantityString(id, 1, 1))
            put("other", res.getQuantityString(id, 987654, 987654).replace("987654", "{n}"))
        }
        val words = JSONObject().apply {
            put("addTitle", s(R.string.home_rows_add_title))
            put("addSubtitle", s(R.string.home_rows_add_subtitle))
            put("pickSubtitle", s(R.string.home_rows_add_pick_subtitle))
            put("sectionLive", s(R.string.home_rows_section_live))
            put("sectionSports", s(R.string.home_rows_section_sports))
            put("sectionVod", s(R.string.home_rows_section_vod))
            put("favorites", s(R.string.home_rows_pick_favorites))
            put("favoritesHint", s(R.string.home_rows_pick_favorites_hint))
            put("recent", s(R.string.home_rows_pick_recent))
            put("recentHint", s(R.string.home_rows_pick_recent_hint))
            put("playlistGroup", s(R.string.home_rows_pick_playlist_group))
            put("playlistGroupHint", s(R.string.home_rows_pick_playlist_group_hint))
            put("playlistGroupTitle", s(R.string.home_rows_pick_playlist_group_title))
            put("myGroup", s(R.string.home_rows_pick_my_group))
            put("myGroupHint", s(R.string.home_rows_pick_my_group_hint))
            put("myGroupTitle", s(R.string.home_rows_pick_my_group_title))
            put("noGroups", s(R.string.iptv_quick_channels_no_groups))
            put("sportsAll", s(R.string.home_rows_pick_sports_all))
            put("sportsAllHint", s(R.string.home_rows_pick_sports_all_hint))
            put("sportsOne", s(R.string.home_rows_pick_sports_one))
            put("sportsOneHint", s(R.string.home_rows_pick_sports_one_hint))
            put("sportsOneTitle", s(R.string.home_rows_pick_sports_one_title))
            put("sportsHeading", s(R.string.home_rows_sports_heading))
            put("leaguesHeading", s(R.string.home_rows_leagues_heading))
            put("vodGroup", s(R.string.home_rows_pick_vod_group))
            put("vodGroupHint", s(R.string.home_rows_pick_vod_group_hint))
            put("vodGroupTitle", s(R.string.home_rows_pick_vod_group_title))
            put("vodNone", s(R.string.home_rows_pick_vod_none))
            put("vodRecent", s(R.string.home_rows_pick_vod_recent))
            put("vodRecentHint", s(R.string.home_rows_pick_vod_recent_hint))
            put("vodContinue", s(R.string.home_rows_vod_continue_title))
            put("vodContinueHint", s(R.string.home_rows_pick_vod_continue_hint))
            put("movies", s(R.string.home_rows_vod_movies))
            put("series", s(R.string.home_rows_vod_series))
            put("typeLive", s(R.string.home_rows_type_live))
            put("typeSports", s(R.string.home_rows_type_sports))
            put("typeVod", s(R.string.home_rows_type_vod))
            put("descChannels", s(R.string.home_rows_desc_channels))
            put("descSports", s(R.string.home_rows_desc_sports))
            put("descVod", s(R.string.home_rows_desc_vod))
            put("hiddenEmpty", s(R.string.home_rows_hidden_empty))
            put("favoritesTitle", s(R.string.home_rows_favorites_title))
            put("recentTitle", s(R.string.home_rows_recent_title))
            put("channelsTitle", s(R.string.home_rows_channels_title))
            put("sportsTitle", s(R.string.home_rows_sports_title))
            put("sportsOneRowTitle", s(R.string.home_rows_sports_one_title))
            put("vodRecentMovies", s(R.string.home_rows_vod_recent_movies_title))
            put("vodRecentSeries", s(R.string.home_rows_vod_recent_series_title))
            put("edit", s(R.string.home_rows_edit))
            put("back", s(R.string.home_rows_web_back))
            put("done", s(R.string.home_rows_web_done))
            put("cancel", s(R.string.collections_cancel))
            put("optName", s(R.string.home_rows_option_name))
            put("optNameHint", s(R.string.home_rows_option_name_hint))
            put("optChannels", s(R.string.home_rows_option_channels))
            put("optSports", s(R.string.home_rows_option_sports))
            put("optVod", s(R.string.home_rows_option_vod))
            put("optSort", s(R.string.home_rows_option_sort))
            put("sortYours", s(R.string.home_rows_sort_yours))
            put("sortNumber", s(R.string.home_rows_sort_number))
            put("sortEnding", s(R.string.home_rows_sort_ending))
            put("optNext", s(R.string.home_rows_option_next))
            put("optHideEmpty", s(R.string.home_rows_option_hide_empty))
            put("optHideEmptyHint", s(R.string.home_rows_option_hide_empty_hint))
            put("optOk", s(R.string.home_rows_option_ok))
            put("okFull", s(R.string.home_rows_option_ok_full))
            put("okGuide", s(R.string.home_rows_option_ok_guide))
            put("okPage", s(R.string.home_rows_option_ok_page))
            put("okPlays", s(R.string.home_rows_option_ok_plays_game))
            put("optSoon", s(R.string.home_rows_option_soon))
            put("optSoonHint", s(R.string.home_rows_option_soon_hint))
            put("optLimit", s(R.string.home_rows_option_limit))
            put("optShape", s(R.string.home_rows_option_shape))
            put("shapeAuto", s(R.string.home_rows_shape_auto))
            put("shapePortrait", s(R.string.home_rows_shape_portrait))
            put("shapeLandscape", s(R.string.home_rows_shape_landscape))
            put("remove", s(R.string.home_rows_option_remove))
            put("channelsCount", count(R.plurals.iptv_quick_channels_count))
            put("titlesCount", count(R.plurals.home_rows_vod_titles_count))
        }.toString().replace("</", "<\\/")
        return "var liveI18n = $words;\n" + SCRIPT
    }

    private val SCRIPT = """
var liveRows = null;
var originalLiveRows = null;
var liveChoices = { favorites: 0, groups: [], sports: [], vodGroups: [] };
var liveSheet = null;

var LIVE_KEY_PREFIX = 'livetv_';
var LIVE_FAV = '__fav__';
var LIVE_RECENT = '__recent__';

function liveRowsLoaded(state) {
  liveRows = Array.isArray(state.liveRows) ? state.liveRows : null;
  originalLiveRows = liveRows ? JSON.parse(JSON.stringify(liveRows)) : null;
  liveChoices = state.liveRowChoices || liveChoices;
  var add = document.getElementById('liveAddRow');
  if (add) add.style.display = liveRows ? 'block' : 'none';
}

function liveRowsRestore() {
  liveRows = originalLiveRows ? JSON.parse(JSON.stringify(originalLiveRows)) : null;
}

function liveRowsForSave() {
  return liveRows;
}

function isLiveKey(key) { return !!key && key.indexOf(LIVE_KEY_PREFIX) === 0; }

/** Rows that keep their own place when Home follows the add-ons' order. */
function isFreeRow(c) { return !!c && (c.isCollection || c.isLiveRow); }

function liveRowById(id) {
  if (!liveRows) return null;
  for (var i = 0; i < liveRows.length; i++) if (liveRows[i].id === id) return liveRows[i];
  return null;
}

function liveRowOfCatalog(c) { return c && c.isLiveRow ? liveRowById(c.key.substring(LIVE_KEY_PREFIX.length)) : null; }

function liveCount(forms, n) { return n === 1 ? forms.one : String(forms.other).replace('{n}', n); }

function liveFill(template, a, b) {
  return String(template).replace('%1${'$'}s', a).replace('%2${'$'}d', b).replace('%1${'$'}d', a);
}

function liveTypeName(kind) {
  if (kind === 'sports') return liveI18n.typeSports;
  if (kind === 'ondemand') return liveI18n.typeVod;
  return liveI18n.typeLive;
}

/** The row's own name when you haven't named it (as the TV works it out). */
function liveDefaultName(r) {
  if (r.kind === 'sports') {
    if (!r.source) return liveI18n.sportsTitle;
    var sp = liveChoices.sports.filter(function(c) { return c.source === r.source; })[0];
    return sp ? liveFill(liveI18n.sportsOneRowTitle, sp.name) : liveI18n.sportsTitle;
  }
  if (r.kind === 'ondemand') {
    if (r.source === 'recent:movie') return liveI18n.vodRecentMovies;
    if (r.source === 'recent:series') return liveI18n.vodRecentSeries;
    if (r.source === 'continue') return liveI18n.vodContinue;
    var v = liveChoices.vodGroups.filter(function(c) { return c.source === r.source; })[0];
    return v ? v.name : (r.label || liveI18n.typeVod);
  }
  if (r.source === LIVE_FAV) return liveI18n.favoritesTitle;
  if (r.source === LIVE_RECENT) return liveI18n.recentTitle;
  var g = liveChoices.groups.filter(function(c) { return c.id === r.source; })[0];
  return g ? g.name : liveI18n.channelsTitle;
}

function liveShownName(r) { return r.title ? r.title : (r.name || liveDefaultName(r)); }

/** Name, badge and the line under it in Home's row list. */
function liveCatalogName(c) {
  var r = liveRowOfCatalog(c);
  return escapeHtml(r ? liveShownName(r) : c.catalogName) + '<span class="badge-live">' + escapeHtml(liveTypeName(c.type)) + '</span>';
}

function liveCatalogMeta(c) {
  var r = liveRowOfCatalog(c);
  if (!r) return '';
  var line;
  if (r.kind === 'sports') line = liveFill(liveI18n.descSports, r.startingSoon ? liveI18n.sportsAllHint : liveI18n.typeSports, r.limit);
  else if (r.kind === 'ondemand') line = liveFill(liveI18n.descVod, liveI18n.typeVod, r.limit);
  else line = liveFill(liveI18n.descChannels, liveI18n.typeLive, r.limit);
  var html = escapeHtml(line);
  if (r.hiddenEmpty && !c.isDisabled) html += '<br>' + escapeHtml(liveI18n.hiddenEmpty);
  return html;
}

function liveEditButton(index) {
  return '<button class="btn btn-toggle" style="margin-right:0.4rem" onclick="openEditRow(' + index + ')">' + escapeHtml(liveI18n.edit) + '</button>';
}

// ------------------------------------------------------------------ the sheet

function openLiveSheet(title, subtitle, html, canGoBack, closeLabel) {
  document.getElementById('liveTitle').textContent = title;
  document.getElementById('liveSubtitle').textContent = subtitle || '';
  document.getElementById('liveBody').innerHTML = html;
  document.getElementById('liveBody').scrollTop = 0;
  document.getElementById('liveBack').style.visibility = canGoBack ? 'visible' : 'hidden';
  document.getElementById('liveClose').textContent = closeLabel || liveI18n.cancel;
  document.getElementById('liveSheet').classList.add('visible');
}

function closeLiveSheet() {
  document.getElementById('liveSheet').classList.remove('visible');
  liveSheet = null;
  renderCatalogs();
}

function liveSheetBack() {
  if (liveSheet && liveSheet.mode === 'pick') openAddRow();
}

function liveChoiceHtml(icon, name, desc, value, opensList, onclick) {
  return '<button class="live-choice" type="button" onclick="' + onclick + '">' +
    '<span class="live-icon">' + (icon || '') + '</span>' +
    '<span class="live-text"><div class="live-name">' + escapeHtml(name) + '</div>' +
      (desc ? '<div class="live-desc">' + escapeHtml(desc) + '</div>' : '') + '</span>' +
    (value ? '<span class="live-value">' + escapeHtml(value) + '</span>' : '') +
    '<span class="live-arrow">' + (opensList ? '&#8250;' : '+') + '</span>' +
  '</button>';
}

function liveHeading(text) { return '<div class="live-heading">' + escapeHtml(text) + '</div>'; }

// ------------------------------------------------------------------ Add a row

function openAddRow() {
  if (!liveRows) return;
  liveSheet = { mode: 'add' };
  var h = liveHeading(liveI18n.sectionLive) +
    liveChoiceHtml('&#9733;', liveI18n.favorites, liveI18n.favoritesHint, liveCount(liveI18n.channelsCount, liveChoices.favorites || 0), false, "addLiveRow('channels','" + LIVE_FAV + "')") +
    liveChoiceHtml('&#8634;', liveI18n.recent, liveI18n.recentHint, '', false, "addLiveRow('channels','" + LIVE_RECENT + "')") +
    liveChoiceHtml('&#9776;', liveI18n.playlistGroup, liveI18n.playlistGroupHint, '', true, "openLivePick('playlist')") +
    liveChoiceHtml('&#128193;', liveI18n.myGroup, liveI18n.myGroupHint, '', true, "openLivePick('mine')") +
    liveHeading(liveI18n.sectionSports) +
    liveChoiceHtml('&#128250;', liveI18n.sportsAll, liveI18n.sportsAllHint, '', false, "addLiveRow('sports','')") +
    liveChoiceHtml('&#127942;', liveI18n.sportsOne, liveI18n.sportsOneHint, '', true, "openLivePick('sports')") +
    liveHeading(liveI18n.sectionVod) +
    liveChoiceHtml('&#127916;', liveI18n.vodGroup, liveI18n.vodGroupHint, '', true, "openLivePick('vod')") +
    liveChoiceHtml('&#10024;', liveI18n.vodRecent, liveI18n.vodRecentHint, '', true, "openLivePick('recent')") +
    liveChoiceHtml('&#9654;', liveI18n.vodContinue, liveI18n.vodContinueHint, '', false, "addLiveRow('ondemand','continue')");
  openLiveSheet(liveI18n.addTitle, liveI18n.addSubtitle, h, false, liveI18n.cancel);
}

function openLivePick(what) {
  liveSheet = { mode: 'pick', what: what };
  var h = '';
  var title = '';
  if (what === 'playlist' || what === 'mine') {
    title = what === 'playlist' ? liveI18n.playlistGroupTitle : liveI18n.myGroupTitle;
    var groups = liveChoices.groups.filter(function(g) { return what === 'playlist' ? !!g.playlist : !g.playlist; });
    if (!groups.length) h = '<div class="empty-state" style="display:block">' + escapeHtml(liveI18n.noGroups) + '</div>';
    var last = null;
    groups.forEach(function(g, i) {
      if (g.playlist && g.playlist !== last) { last = g.playlist; h += liveHeading(g.playlist); }
      h += liveChoiceHtml('', g.name, '', liveCount(liveI18n.channelsCount, g.count), false, 'addLiveGroup(' + liveChoices.groups.indexOf(g) + ')');
    });
  } else if (what === 'sports') {
    title = liveI18n.sportsOneTitle;
    var sports = liveChoices.sports.filter(function(c) { return !c.isLeague; });
    var leagues = liveChoices.sports.filter(function(c) { return c.isLeague; });
    if (sports.length) h += liveHeading(liveI18n.sportsHeading);
    sports.forEach(function(c) { h += liveChoiceHtml(c.icon, c.name, '', '', false, 'addLiveSport(' + liveChoices.sports.indexOf(c) + ')'); });
    if (leagues.length) h += liveHeading(liveI18n.leaguesHeading);
    leagues.forEach(function(c) { h += liveChoiceHtml(c.icon, c.name, '', '', false, 'addLiveSport(' + liveChoices.sports.indexOf(c) + ')'); });
  } else if (what === 'vod') {
    title = liveI18n.vodGroupTitle;
    var movies = liveChoices.vodGroups.filter(function(c) { return !c.series; });
    var series = liveChoices.vodGroups.filter(function(c) { return c.series; });
    if (!movies.length && !series.length) h = '<div class="empty-state" style="display:block">' + escapeHtml(liveI18n.vodNone) + '</div>';
    if (movies.length) h += liveHeading(liveI18n.movies);
    movies.forEach(function(c) { h += liveChoiceHtml('', c.name, c.playlist || '', liveCount(liveI18n.titlesCount, c.count), false, 'addLiveVod(' + liveChoices.vodGroups.indexOf(c) + ')'); });
    if (series.length) h += liveHeading(liveI18n.series);
    series.forEach(function(c) { h += liveChoiceHtml('', c.name, c.playlist || '', liveCount(liveI18n.titlesCount, c.count), false, 'addLiveVod(' + liveChoices.vodGroups.indexOf(c) + ')'); });
  } else if (what === 'recent') {
    title = liveI18n.vodRecent;
    h = liveChoiceHtml('&#127916;', liveI18n.vodRecentMovies, '', '', false, "addLiveRow('ondemand','recent:movie')") +
      liveChoiceHtml('&#128250;', liveI18n.vodRecentSeries, '', '', false, "addLiveRow('ondemand','recent:series')");
  }
  openLiveSheet(title, liveI18n.pickSubtitle, h, true, liveI18n.cancel);
}

function addLiveGroup(i) { var g = liveChoices.groups[i]; if (g) addLiveRow('channels', g.id); }
function addLiveSport(i) { var c = liveChoices.sports[i]; if (c) addLiveRow('sports', c.source); }
function addLiveVod(i) { var c = liveChoices.vodGroups[i]; if (c) addLiveRow('ondemand', c.source, c.name); }

function newLiveId() {
  return Date.now().toString(36) + Math.floor(Math.random() * 1296).toString(36);
}

/** A new row, with the TV's defaults, at the top of Home. */
function addLiveRow(kind, source, label) {
  if (!liveRows) return;
  var r = {
    id: newLiveId(), kind: kind, source: source, title: '', name: '',
    sort: 'yours', limit: kind === 'ondemand' ? 30 : 20,
    showNext: false, hidePlaceholders: false, opensGuide: false, playsGame: false, startingSoon: true,
    shape: 'auto', after: '', label: label || '', hiddenEmpty: false
  };
  r.key = LIVE_KEY_PREFIX + r.id;
  r.name = liveDefaultName(r);
  liveRows.push(r);
  catalogs.unshift({ key: r.key, disableKey: r.key, catalogName: r.name, addonName: '', type: kind, isDisabled: false, isLiveRow: true });
  closeLiveSheet();
  var list = document.getElementById('catalogList');
  if (list) list.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// ------------------------------------------------------------------ Edit

function liveSelect(id, value, options) {
  return '<select id="' + id + '" onchange="liveEditChanged()">' + options.map(function(o) {
    return '<option value="' + escapeAttr(o[0]) + '"' + (o[0] === value ? ' selected' : '') + '>' + escapeHtml(o[1]) + '</option>';
  }).join('') + '</select>';
}

function liveSwitch(id, checked, name, desc) {
  return '<div class="live-switch"><div class="live-text"><div class="live-name">' + escapeHtml(name) + '</div>' +
    (desc ? '<div class="live-desc">' + escapeHtml(desc) + '</div>' : '') + '</div>' +
    '<label class="toggle-switch"><input type="checkbox" id="' + id + '"' + (checked ? ' checked' : '') + ' onchange="liveEditChanged()">' +
    '<span class="toggle-track"></span><span class="toggle-thumb"></span></label></div>';
}

function liveField(label, control) {
  return '<div class="live-field"><label class="live-label">' + escapeHtml(label) + '</label>' + control + '</div>';
}

function liveSourceOptions(r) {
  if (r.kind === 'ondemand') {
    return [['recent:movie', liveI18n.vodRecentMovies], ['recent:series', liveI18n.vodRecentSeries], ['continue', liveI18n.vodContinue]]
      .concat(liveChoices.vodGroups.map(function(g) { return [g.source, [g.name, g.series ? liveI18n.series : liveI18n.movies, g.playlist].filter(Boolean).join(' · ')]; }));
  }
  if (r.kind === 'sports') {
    return [['', liveI18n.sportsAll]].concat(liveChoices.sports.map(function(c) { return [c.source, c.icon + '  ' + c.name]; }));
  }
  return [[LIVE_FAV, liveI18n.favorites], [LIVE_RECENT, liveI18n.recent]]
    .concat(liveChoices.groups.map(function(g) { return [g.id, g.playlist ? g.name + ' · ' + g.playlist : g.name]; }));
}

function openEditRow(index) {
  var c = catalogs[index];
  var r = liveRowOfCatalog(c);
  if (!r) return;
  liveSheet = { mode: 'edit', id: r.id };
  var sources = liveSourceOptions(r);
  if (!sources.some(function(o) { return o[0] === r.source; })) sources.unshift([r.source, liveShownName(r)]);
  var limits = [10, 20, 30, 50];
  if (limits.indexOf(r.limit) < 0) limits.push(r.limit);
  var h = liveField(liveI18n.optName, '<input type="text" id="liveName" value="' + escapeAttr(r.title || '') + '" placeholder="' + escapeAttr(liveDefaultName(r)) + '" oninput="liveEditChanged()">') +
    '<div class="catalog-meta" style="margin:-0.5rem 0 0.9rem">' + escapeHtml(liveI18n.optNameHint) + '</div>' +
    liveField(r.kind === 'ondemand' ? liveI18n.optVod : (r.kind === 'sports' ? liveI18n.optSports : liveI18n.optChannels), liveSelect('liveSource', r.source, sources));
  if (r.kind === 'sports') {
    h += liveSwitch('liveSoon', r.startingSoon, liveI18n.optSoon, liveI18n.optSoonHint) +
      liveField(liveI18n.optOk, liveSelect('liveOk', r.playsGame ? 'play' : 'page', [['page', liveI18n.okPage], ['play', liveI18n.okPlays]]));
  } else if (r.kind === 'channels') {
    h += liveField(liveI18n.optSort, liveSelect('liveSort', r.sort, [['yours', liveI18n.sortYours], ['number', liveI18n.sortNumber], ['ending', liveI18n.sortEnding]])) +
      liveSwitch('liveNext', r.showNext, liveI18n.optNext, '') +
      liveSwitch('liveHideEmpty', r.hidePlaceholders, liveI18n.optHideEmpty, liveI18n.optHideEmptyHint) +
      liveField(liveI18n.optOk, liveSelect('liveOk', r.opensGuide ? 'guide' : 'full', [['full', liveI18n.okFull], ['guide', liveI18n.okGuide]]));
  }
  h += liveField(liveI18n.optLimit, liveSelect('liveLimit', String(r.limit), limits.map(function(n) { return [String(n), String(n)]; })));
  if (r.kind !== 'ondemand') {
    h += liveField(liveI18n.optShape, liveSelect('liveShape', r.shape, [['auto', liveI18n.shapeAuto], ['portrait', liveI18n.shapePortrait], ['landscape', liveI18n.shapeLandscape]]));
  }
  h += '<button class="btn live-remove" type="button" onclick="removeLiveRow()">' + escapeHtml(liveI18n.remove) + '</button>';
  openLiveSheet(liveShownName(r), liveTypeName(r.kind), h, false, liveI18n.done);
}

function liveVal(id) { var el = document.getElementById(id); return el ? el.value : null; }
function liveChecked(id, fallback) { var el = document.getElementById(id); return el ? el.checked : fallback; }

/** Every change in the Edit sheet goes straight into the row (saved with the page's Save). */
function liveEditChanged() {
  if (!liveSheet || liveSheet.mode !== 'edit') return;
  var r = liveRowById(liveSheet.id);
  if (!r) return;
  var source = liveVal('liveSource');
  if (source !== null && source !== r.source) {
    r.source = source;
    if (r.kind === 'ondemand') {
      var v = liveChoices.vodGroups.filter(function(c) { return c.source === source; })[0];
      r.label = v ? v.name : '';
    }
  }
  r.title = (liveVal('liveName') || '').trim();
  if (r.kind === 'sports') {
    r.startingSoon = liveChecked('liveSoon', r.startingSoon);
    r.playsGame = liveVal('liveOk') === 'play';
  } else if (r.kind === 'channels') {
    r.sort = liveVal('liveSort') || r.sort;
    r.showNext = liveChecked('liveNext', r.showNext);
    r.hidePlaceholders = liveChecked('liveHideEmpty', r.hidePlaceholders);
    r.opensGuide = liveVal('liveOk') === 'guide';
  }
  var limit = parseInt(liveVal('liveLimit'), 10);
  if (!isNaN(limit)) r.limit = limit;
  if (r.kind !== 'ondemand') r.shape = liveVal('liveShape') || r.shape;
  r.name = r.title || liveDefaultName(r);
  var nameInput = document.getElementById('liveName');
  if (nameInput) nameInput.placeholder = liveDefaultName(r);
  document.getElementById('liveTitle').textContent = liveShownName(r);
  catalogs.forEach(function(c) { if (c.key === r.key) c.catalogName = r.name; });
}

function removeLiveRow() {
  if (!liveSheet || liveSheet.mode !== 'edit') return;
  var id = liveSheet.id;
  liveRows = liveRows.filter(function(r) { return r.id !== id; });
  catalogs = catalogs.filter(function(c) { return c.key !== LIVE_KEY_PREFIX + id; });
  closeLiveSheet();
}
"""
}
