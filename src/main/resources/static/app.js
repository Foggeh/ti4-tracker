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
let pointSources = [];

/**
 * Sentinel option value meaning "let me type a label that is not listed".
 * Deliberately not whitespace: a space here was mangled into a NUL byte once,
 * and a value that looks like a space is impossible to spot when it breaks.
 */
const CUSTOM_LABEL = '__custom__';

const el = (id) => document.getElementById(id);

// --- boot --------------------------------------------------------------------

document.addEventListener('DOMContentLoaded', async () => {
  wireUp();
  try {
    catalogue = await api.get('/api/objectives');
    pointSources = await api.get('/api/point-sources');
    await loadFactions();
    await loadGames();
  } catch (e) {
    alert('Could not reach the server: ' + e.message);
  }
});

const EXPANSION_LABELS = {
  base: 'Base game',
  pok: 'Prophecy of Kings',
  codex: 'Codex',
  'thunders-edge': "Thunder's Edge",
  homebrew: 'Homebrew',
};

/** Fills the add-player faction dropdown, grouped by expansion. */
async function loadFactions() {
  const factions = await api.get('/api/factions');
  const select = el('factionSelect');

  // Preserve source order within a group, but group in a sensible sequence
  // rather than whatever order the file happens to use.
  const order = Object.keys(EXPANSION_LABELS);
  const groups = [...new Set(factions.map((f) => f.expansion))].sort(
    (a, b) => (order.indexOf(a) + 1 || 99) - (order.indexOf(b) + 1 || 99)
  );

  for (const expansion of groups) {
    const group = document.createElement('optgroup');
    group.label = EXPANSION_LABELS[expansion] || expansion;
    for (const f of factions.filter((x) => x.expansion === expansion)) {
      const opt = document.createElement('option');
      opt.value = f.name;
      opt.textContent = f.name;
      group.append(opt);
    }
    select.append(group);
  }
}

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

    // Removable only while nothing has been scored on it. Kept clickable rather
    // than disabled so a tap explains why -- there is no hover on a phone.
    const locked = o.scoredBy.length > 0;

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
        <button class="tiny ${locked ? 'locked' : 'danger'}" data-unreveal="${o.id}"
                title="${locked
                  ? 'Scored by ' + o.scoredBy.length + ' player(s) — unscore them first'
                  : 'Take this objective back off the board'}">
          Remove from game${locked ? ' 🔒' : ''}
        </button>
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

/**
 * In-app replacement for window.confirm(), which some browsers suppress: it
 * returns false without prompting, so a guarded action silently does nothing.
 *
 * Both buttons are type="button" and resolve explicitly, so this depends on
 * neither the dialog `close` event nor form submission.
 */
function askConfirm({ title, body, okLabel = 'Confirm', danger = false }) {
  const dialog = el('confirmDlg');
  const okBtn = el('confirmOk');
  const cancelBtn = el('confirmCancel');

  el('confirmTitle').textContent = title;
  el('confirmBody').textContent = body || '';
  okBtn.textContent = okLabel;
  okBtn.classList.toggle('destructive', danger);

  return new Promise((resolve) => {
    const finish = (result) => {
      okBtn.removeEventListener('click', onOk);
      cancelBtn.removeEventListener('click', onCancel);
      dialog.removeEventListener('cancel', onEscape);
      if (dialog.open) {
        dialog.close();
      }
      resolve(result);
    };
    const onOk = () => finish(true);
    const onCancel = () => finish(false);
    const onEscape = () => finish(false);

    okBtn.addEventListener('click', onOk);
    cancelBtn.addEventListener('click', onCancel);
    dialog.addEventListener('cancel', onEscape);
    dialog.showModal();
    cancelBtn.focus(); // destructive action should not be the default
  });
}

const toggleScore = (playerId, objectiveId) =>
  guard(async () => {
    await api.post('/api/score', { gameId, playerId, objectiveId });
    await refresh();
  });

const unreveal = (o) =>
  guard(async () => {
    // The button is disabled in this case; this is the belt to the server's
    // braces, in case a stale page is clicked after someone else scored it.
    const n = o.scoredBy.length;
    if (n > 0) {
      await askConfirm({
        title: 'Cannot remove this objective',
        body:
          `${n === 1 ? '1 player has' : n + ' players have'} scored "${o.name}". ` +
          `Unscore ${n === 1 ? 'them' : 'them all'} first — tap the highlighted ` +
          'name on the card — then remove it.',
        okLabel: 'OK',
      });
      return;
    }

    const ok = await askConfirm({
      title: 'Remove this objective?',
      body:
        `"${o.name}" will be taken off the board for this game. ` +
        'Nothing has been scored on it, so no points change. ' +
        'You can reveal it again afterwards.',
      okLabel: 'Remove',
      danger: true,
    });
    if (!ok) return;

    await api.post('/api/unreveal', { gameId, objectiveId: o.id });
    await refresh();
  });

const deleteEntry = (row) =>
  guard(async () => {
    const what = row.objectiveName || row.label || 'this entry';
    const who = state.players.find((p) => p.id === row.playerId);
    const ok = await askConfirm({
      title: 'Remove this score?',
      body:
        `${who ? who.name : 'This player'} loses ` +
        `${row.points} ${Math.abs(row.points) === 1 ? 'point' : 'points'} from "${what}".`,
      okLabel: 'Remove',
      danger: true,
    });
    if (!ok) return;
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
    // Re-read so a label just typed is offered next time without a reload.
    pointSources = await api.get('/api/point-sources');
    await refresh();
  });

  onDialogOk('addCardDlg', 'addCardForm', async (v) => {
    await api.post('/api/objectives', v);
    catalogue = await api.get('/api/objectives');
    fillObjectiveSelect();
    el('revealDlg').showModal();
  });

  el('addCardBtn').addEventListener('click', () => {
    closeDialog(el('revealDlg'));
    el('addCardDlg').showModal();
  });

  // Cancel closes its dialog explicitly rather than submitting it. As a submit
  // button it tripped HTML5 validation on the required fields and refused to
  // close at all -- the original bug.
  document.querySelectorAll('dialog [data-cancel]').forEach((btn) => {
    btn.addEventListener('click', () => closeDialog(btn.closest('dialog')));
  });

  // Escape fires `cancel`, which is well supported even where `close` is not.
  document.querySelectorAll('dialog').forEach((dialog) => {
    dialog.addEventListener('cancel', () => {
      const form = dialog.querySelector('form');
      if (form) {
        form.reset();
      }
    });
  });

  el('objFilter').addEventListener('input', fillObjectiveSelect);

  // Stage drives the usual point value; still editable for odd cards.
  el('cardStage').addEventListener('change', (e) => {
    el('addCardForm').elements.points.value = e.target.value === 'II' ? 2 : 1;
  });

  el('pointsKind').addEventListener('change', () => {
    fillLabelOptions();
    updatePointsDialog();
  });
  el('pointsPlayer').addEventListener('change', updatePointsDialog);
  el('pointsLabelSelect').addEventListener('change', onLabelChoice);
}

/**
 * Runs handler when the dialog's form is submitted, i.e. the OK button.
 *
 * Deliberately listens for the form's `submit` rather than the dialog's `close`.
 * The close event is not fired by every engine -- one browser tested here never
 * fires it, not even for an explicit close() -- which would silently break every
 * dialog in the app. `submit` is dependable, and `method="dialog"` still closes
 * the dialog for us.
 *
 * Only the OK button can submit: Cancel is type="button", so it never reaches
 * here and never trips HTML5 validation on a required field.
 */
function onDialogOk(dialogId, formId, handler) {
  const form = el(formId);
  form.addEventListener('submit', () => {
    const values = formValues(form);
    guard(async () => {
      await handler(values);
      form.reset();
    });
  });
}

/** Discard a dialog without submitting it, clearing whatever was typed. */
function closeDialog(dialog) {
  const form = dialog.querySelector('form');
  dialog.close('cancel');
  if (form) {
    form.reset();
  }
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
  fillLabelOptions();
  updatePointsDialog();
  el('addPointsDlg').showModal();
}

/**
 * Rebuilds the Label dropdown for the currently selected Kind.
 *
 * Seeded options and ones learned from previous games are grouped separately, so
 * it is clear which came from the card list and which the app picked up from use.
 */
function fillLabelOptions() {
  const kind = el('pointsKind').value;
  const select = el('pointsLabelSelect');
  const custom = el('pointsLabel');
  select.innerHTML = '';

  const forKind = pointSources.filter((s) => s.kind === kind);

  const blank = document.createElement('option');
  blank.value = '';
  blank.textContent = forKind.length ? '— pick one —' : '— nothing listed yet —';
  select.append(blank);

  const addGroup = (label, items) => {
    if (!items.length) return;
    const group = document.createElement('optgroup');
    group.label = label;
    for (const s of items) {
      const opt = document.createElement('option');
      opt.value = s.name;
      opt.textContent = s.name;
      opt.dataset.points = s.points;
      group.append(opt);
    }
    select.append(group);
  };

  // Group headings come from the data, in first-seen order, so the CSV controls
  // both the grouping and the sequence. 40 secrets split by phase is navigable;
  // one flat list of 40 is not.
  const headings = [];
  for (const s of forKind) {
    const heading = s.group || (kind === 'secret' ? 'Secret objectives' : 'Point sources');
    if (!headings.includes(heading)) headings.push(heading);
  }
  for (const heading of headings) {
    addGroup(heading, forKind.filter((s) => (s.group || (kind === 'secret'
      ? 'Secret objectives' : 'Point sources')) === heading));
  }

  const other = document.createElement('option');
  other.value = CUSTOM_LABEL;
  other.textContent = '— something else —';
  select.append(other);

  select.value = '';
  custom.value = '';
  custom.hidden = true;
}

/** Mirrors the chosen option into the submitted field, or opens it for typing. */
function onLabelChoice() {
  const select = el('pointsLabelSelect');
  const custom = el('pointsLabel');

  if (select.value === CUSTOM_LABEL) {
    custom.hidden = false;
    custom.value = '';
    custom.focus();
    return;
  }

  custom.hidden = true;
  custom.value = select.value;

  // Most sources have a usual value; prefill it but leave it editable.
  const chosen = select.selectedOptions[0];
  if (chosen && chosen.dataset.points) {
    el('addPointsForm').elements.points.value = chosen.dataset.points;
  }
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
