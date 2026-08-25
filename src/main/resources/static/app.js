'use strict';

// --- api ---------------------------------------------------------------------

async function errorText(response) {
  try {
    const body = await response.json();
    return body.message || body.error || response.statusText;
  } catch {
    return response.statusText;
  }
}

const api = {
  async get(path) {
    const r = await fetch(path);
    if (!r.ok) throw new Error(await errorText(r));
    return r.json();
  },
  async post(path, data) {
    const r = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(compact(data)),
    });
    if (!r.ok) throw new Error(await errorText(r));
    return r.json();
  },
};

/** Drop blanks so optional params arrive absent rather than as empty strings. */
function compact(obj) {
  return Object.fromEntries(
    Object.entries(obj).filter(([, v]) => v !== null && v !== undefined && v !== '')
  );
}

function formValues(form) {
  return Object.fromEntries(new FormData(form).entries());
}

// --- state -------------------------------------------------------------------

const LAST_GAME_KEY = 'ti4.lastGame';

let gameId = null;
let state = null;
let catalogue = [];

const el = (id) => document.getElementById(id);

// --- boot --------------------------------------------------------------------

document.addEventListener('DOMContentLoaded', async () => {
  wireUp();
  try {
    catalogue = await api.get('/api/objectives');
    await loadGames();
  } catch (e) {
    alert('Could not reach the server: ' + e.message);
  }
});

async function loadGames() {
  const games = await api.get('/api/games');
  const select = el('gameSelect');
  select.innerHTML = '';

  for (const g of games) {
    const opt = document.createElement('option');
    opt.value = g.id;
    opt.textContent = g.name;
    select.append(opt);
  }

  if (games.length === 0) {
    el('main').hidden = true;
    el('noGame').hidden = false;
    return;
  }

  const remembered = Number(localStorage.getItem(LAST_GAME_KEY));
  const chosen = games.some((g) => g.id === remembered) ? remembered : games[0].id;
  select.value = String(chosen);
  await selectGame(chosen);
}

async function selectGame(id) {
  gameId = Number(id);
  localStorage.setItem(LAST_GAME_KEY, String(gameId));
  await refresh();
}

async function refresh() {
  state = await api.get('/api/state?game=' + gameId);
  el('noGame').hidden = true;
  el('main').hidden = false;
  render();
}

// --- render ------------------------------------------------------------------

function render() {
  renderPlayers();
  renderObjectives();
  renderLedger();
  el('vpTarget').textContent = 'First to ' + state.game.vpTarget + ' VP';
}

function renderPlayers() {
  const host = el('players');
  host.innerHTML = '';

  if (state.players.length === 0) {
    host.innerHTML = '<p class="empty">No players yet. Use <strong>Add player</strong>.</p>';
    return;
  }

  const best = Math.max(...state.players.map((p) => p.total));

  for (const p of state.players) {
    const card = document.createElement('div');
    card.className = 'player-card' + (p.color ? ' c-' + p.color : '');
    if (p.total === best && best > 0) card.classList.add('leader');

    const over = p.secretCount > state.game.maxSecrets;
    card.innerHTML = `
      <div class="name">${escapeHtml(p.name)}</div>
      <div class="faction">${escapeHtml(p.faction || '')}</div>
      <div class="score">${p.total}</div>
      <div class="secrets${over ? ' over' : ''}">
        ${p.secretCount}/${state.game.maxSecrets} secrets${over ? ' — over cap' : ''}
      </div>`;
    host.append(card);
  }
}

function renderObjectives() {
  const host = el('objectives');
  host.innerHTML = '';
  el('noObjectives').hidden = state.revealed.length > 0;

  for (const o of state.revealed) {
    const card = document.createElement('div');
    card.className = 'obj-card' + (o.image ? ' has-image' : '');

    // onerror drops the element and the has-image class, so a wrong filename
    // falls back to the text layout instead of leaving a broken-image icon.
    const image = o.image
      ? `<img src="/images/${encodeURIComponent(o.image)}" alt="${escapeHtml(o.name)}"
             onerror="this.closest('.obj-card').classList.remove('has-image'); this.remove()">`
      : '';

    const round = o.round ? ` · round ${o.round}` : '';

    card.innerHTML = `
      ${image}
      <div class="obj-body">
        <div class="obj-head">
          <span class="obj-name">${escapeHtml(o.name)}</span>
          <span class="badge stage-${o.stage}">${o.stage} · ${o.points}VP${round}</span>
        </div>
        <p class="obj-req">${escapeHtml(o.requirement || '')}</p>
      </div>
      <div class="scorers"></div>
      <div class="obj-actions">
        <button class="tiny danger" data-unreveal="${o.id}">Remove from game</button>
      </div>`;

    const scorers = card.querySelector('.scorers');
    for (const p of state.players) {
      const on = o.scoredBy.includes(p.id);
      const chip = document.createElement('button');
      chip.className = 'chip' + (on ? ' on' : '');
      chip.textContent = p.name;
      chip.title = on ? 'Scored — click to undo' : 'Mark as scored';
      chip.addEventListener('click', () => toggleScore(p.id, o.id));
      scorers.append(chip);
    }

    card.querySelector('[data-unreveal]').addEventListener('click', () => unreveal(o));
    host.append(card);
  }
}

function renderLedger() {
  const host = el('ledger');
  host.innerHTML = '';

  if (state.ledger.length === 0) {
    host.innerHTML = '<li class="muted">Nothing scored yet.</li>';
    return;
  }

  const nameOf = (id) => {
    const p = state.players.find((x) => x.id === id);
    return p ? p.name : 'unknown';
  };

  for (const row of [...state.ledger].reverse()) {
    const li = document.createElement('li');
    const what = row.objectiveName || row.label || '(no label)';
    li.innerHTML = `
      <span class="who">${escapeHtml(nameOf(row.playerId))}</span>
      <span class="kind-tag">${row.kind}</span>
      <span class="what">${escapeHtml(what)}</span>
      <span class="pts">+${row.points}</span>
      <button class="tiny danger" title="Remove this entry">✕</button>`;
    li.querySelector('button').addEventListener('click', () => deleteEntry(row));
    host.append(li);
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])
  );
}

// --- actions -----------------------------------------------------------------

async function guard(fn) {
  try {
    await fn();
  } catch (e) {
    alert(e.message);
  }
}

const toggleScore = (playerId, objectiveId) =>
  guard(async () => {
    await api.post('/api/score', { gameId, playerId, objectiveId });
    await refresh();
  });

const unreveal = (o) =>
  guard(async () => {
    const scored = o.scoredBy.length;
    const extra = scored
      ? `\n\nThis also deletes ${scored} scoring ${scored === 1 ? 'entry' : 'entries'} against it.`
      : '';
    if (!confirm(`Remove "${o.name}" from this game?${extra}`)) return;
    await api.post('/api/unreveal', { gameId, objectiveId: o.id });
    await refresh();
  });

const deleteEntry = (row) =>
  guard(async () => {
    const what = row.objectiveName || row.label || 'this entry';
    if (!confirm(`Remove "${what}" (+${row.points})?`)) return;
    await api.post('/api/points/delete', { id: row.id });
    await refresh();
  });

// --- dialog wiring -----------------------------------------------------------

function wireUp() {
  el('gameSelect').addEventListener('change', (e) => guard(() => selectGame(e.target.value)));

  el('newGameBtn').addEventListener('click', () => el('newGameDlg').showModal());
  el('addPlayerBtn').addEventListener('click', () => el('addPlayerDlg').showModal());
  el('revealBtn').addEventListener('click', openRevealDialog);
  el('addPointsBtn').addEventListener('click', openPointsDialog);

  onDialogOk('newGameDlg', 'newGameForm', async (v) => {
    const { id } = await api.post('/api/games', v);
    await loadGames();
    el('gameSelect').value = String(id);
    await selectGame(id);
  });

  onDialogOk('addPlayerDlg', 'addPlayerForm', async (v) => {
    await api.post('/api/players', { ...v, gameId });
    await refresh();
  });

  onDialogOk('revealDlg', 'revealForm', async (v) => {
    await api.post('/api/reveal', { ...v, gameId });
    await refresh();
  });

  onDialogOk('addPointsDlg', 'addPointsForm', async (v) => {
    await api.post('/api/points', { ...v, gameId });
    await refresh();
  });

  onDialogOk('addCardDlg', 'addCardForm', async (v) => {
    await api.post('/api/objectives', v);
    catalogue = await api.get('/api/objectives');
    fillObjectiveSelect();
    el('revealDlg').showModal();
  });

  el('addCardBtn').addEventListener('click', () => {
    el('revealDlg').close('cancel');
    el('addCardDlg').showModal();
  });

  el('objFilter').addEventListener('input', fillObjectiveSelect);

  // Stage drives the usual point value; still editable for odd cards.
  el('cardStage').addEventListener('change', (e) => {
    el('addCardForm').elements.points.value = e.target.value === 'II' ? 2 : 1;
  });

  el('pointsKind').addEventListener('change', updatePointsDialog);
  el('pointsPlayer').addEventListener('change', updatePointsDialog);
}

/** Runs handler only when the dialog was closed with the OK button. */
function onDialogOk(dialogId, formId, handler) {
  const dialog = el(dialogId);
  dialog.addEventListener('close', () => {
    if (dialog.returnValue !== 'ok') return;
    const form = el(formId);
    const values = formValues(form);
    guard(async () => {
      await handler(values);
      form.reset();
    });
  });
}

function openRevealDialog() {
  fillObjectiveSelect();
  el('revealDlg').showModal();
}

function fillObjectiveSelect() {
  const filter = el('objFilter').value.trim().toLowerCase();
  const revealedIds = new Set(state ? state.revealed.map((o) => o.id) : []);
  const select = el('objectiveSelect');
  select.innerHTML = '';

  const available = catalogue
    .filter((o) => !revealedIds.has(o.id))
    .filter((o) => !filter || (o.name + ' ' + (o.requirement || '')).toLowerCase().includes(filter));

  for (const o of available) {
    const opt = document.createElement('option');
    opt.value = o.id;
    opt.textContent = `[${o.stage}] ${o.name} — ${o.points}VP`;
    select.append(opt);
  }

  if (available.length === 0) {
    const opt = document.createElement('option');
    opt.disabled = true;
    opt.textContent = filter ? 'No match' : 'Everything is already revealed';
    select.append(opt);
  }
}

function openPointsDialog() {
  const select = el('pointsPlayer');
  select.innerHTML = '';
  for (const p of state.players) {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = p.name;
    select.append(opt);
  }
  updatePointsDialog();
  el('addPointsDlg').showModal();
}

/**
 * Warns about the secret cap without blocking. The cap exists in the rules but
 * arguing with the app mid-game is worse than a wrong number on screen.
 */
function updatePointsDialog() {
  const kind = el('pointsKind').value;
  const playerId = Number(el('pointsPlayer').value);
  const player = state.players.find((p) => p.id === playerId);
  const warning = el('secretWarning');

  el('pointsLabel').placeholder =
    kind === 'secret' ? 'Become a Martyr' : 'Custodians token';

  if (kind === 'secret' && player && player.secretCount >= state.game.maxSecrets) {
    warning.hidden = false;
    warning.textContent =
      `${player.name} already holds ${player.secretCount} secret objectives ` +
      `(cap is ${state.game.maxSecrets}). Adding another is allowed, but check ` +
      `the board first.`;
  } else {
    warning.hidden = true;
  }
}
