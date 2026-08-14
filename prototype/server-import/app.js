const state = { screen: 'detail', format: 'audio', source: 'web', servers: 'configured', scenario: 'new', library: 'Night shelf', menuOpen: false };
const appScreen = document.querySelector('#appScreen');
const formatSelect = document.querySelector('#formatSelect');
const sourceSelect = document.querySelector('#sourceSelect');
const serversSelect = document.querySelector('#serversSelect');
const scenarioSelect = document.querySelector('#scenarioSelect');

const details = {
  audio: { label: 'Audiobook', action: 'Listen', progress: '42% listened', structure: '3 chapters · 30:54' },
  epub: { label: 'EPUB', action: 'Read', progress: '42% read', structure: '12 chapters · 2.4 MB' },
};

function media() { return details[state.format]; }
function setScreen(screen) { state.screen = screen; state.menuOpen = false; render(); }
function canUpload() { return state.source === 'web' && state.servers === 'configured'; }

function topBar(title, back = true, detail = false) {
  const overflow = detail ? `<div class="overflow-wrap"><button class="overflow-button" aria-label="More actions" data-action="toggle-menu">⋮</button>${menuMarkup()}</div>` : '';
  return `<div class="top-app-bar">${back ? '<button class="back-button" data-action="back">‹</button>' : ''}<span class="top-title ${title.length > 22 ? 'small' : ''}">${title}</span>${overflow}</div>`;
}

function menuMarkup() {
  if (!state.menuOpen) return '';
  return canUpload() ? '<div class="overflow-menu"><button data-action="add">Upload to…</button></div>' : '<div class="overflow-menu"><span class="menu-disabled">No upload available</span></div>';
}

function detailScreen() {
  const d = media();
  return `<div class="screen">${topBar('A Map of Quiet Stars', true, true)}
    <div class="detail-scroll">
      <div class="source-label">${state.source === 'web' ? 'Chitanka · Web Source' : 'Audiobookshelf · Server Source'}</div>
      <div class="cover-wrap"><div class="cover"><div class="cover-copy"><strong>A MAP<br>OF QUIET<br>STARS</strong><small>MIRA VALE</small></div></div></div>
      <div class="detail-actions"><button class="${state.format === 'audio' ? 'listen-button' : 'read-button'}">${d.action}</button><button class="circle-action">♡</button><button class="circle-action">♧</button><button class="circle-action download">↓</button></div>
      <h1 class="detail-title">A Map of Quiet Stars</h1><p class="detail-author">By <span>Mira Vale</span></p><p class="detail-series">The Quiet Sky · #1</p><div class="book-progress"><span></span></div><p class="progress-label">${d.progress}</p>
      <div class="detail-divider"></div><h3 class="detail-section-title">Details</h3><div class="detail-meta"><div><strong>Format</strong>${d.label}</div><div><strong>Published</strong>2024</div><div><strong>Language</strong>English</div><div><strong>Structure</strong>${d.structure}</div></div>
    </div></div>`;
}

function destinationScreen() {
  return `<div class="screen sheet-screen"><div class="sheet-scrim"><div class="bottom-sheet"><div class="sheet-handle"></div><h2 class="sheet-title">Upload to…</h2><p class="sheet-copy">Choose the server library to receive this Web Source item. The selected library is checked for a compatible item.</p>
    <div class="form-row"><span class="form-icon">▣</span><div><small>Server</small><strong>Audiobookshelf</strong></div><span class="form-arrow">›</span></div>
    <div class="form-row"><span class="form-icon">▤</span><div><small>Library</small><strong>${state.library}</strong></div><span class="form-arrow">›</span></div>
    <button class="sheet-button" data-action="continue">Check this library</button><button class="sheet-cancel" data-action="back">Cancel</button>
  </div></div></div>`;
}

function reviewScreen() {
  const d = media();
  const isNew = state.scenario === 'new';
  const blocked = state.scenario === 'annotations';
  const headline = isNew ? 'Ready to upload' : blocked ? 'Overwrite blocked' : 'Overwrite existing file?';
  const copy = isNew ? `No compatible item was found in ${state.library}. Riffle will send the complete item representation to Audiobookshelf.` : blocked ? 'The existing item has annotations and this replacement is not structurally identical.' : `A compatible ${d.label.toLowerCase()} was found in ${state.library}. Only the file will change.`;
  return `<div class="screen review-screen">${topBar('Review import')}<div class="review-content"><h2>${headline}</h2><p class="review-copy">${copy}</p>
    <div class="review-card"><div class="review-item"><div class="mini-cover">A MAP<br>OF QUIET<br>STARS</div><div><strong>A Map of Quiet Stars</strong><small>${d.label} · Mira Vale</small></div></div><div class="review-lines"><div class="review-line"><span>Files</span><strong>${d.label === 'Audiobook' ? '3 ordered MP3 chapters' : 'EPUB · 12 chapters'}</strong></div><div class="review-line"><span>Metadata &amp; cover</span><strong>${isNew ? 'Transfer' : 'Keep existing'}</strong></div><div class="review-line"><span>Progress</span><strong>${isNew ? 'Transfer if supported' : 'Keep existing'}</strong></div>${d.label === 'Audiobook' ? '<div class="review-line"><span>Chapter order</span><strong>Preserve exactly</strong></div>' : '<div class="review-line"><span>Annotations</span><strong>Protected</strong></div>'}</div></div>
    ${blocked ? '<div class="review-note warning"><span class="note-symbol">!</span><span>Annotations are protected. This overwrite cannot continue because the replacement EPUB could move their anchors.</span></div>' : `<div class="review-note"><span class="note-symbol">✓</span><span>${isNew ? 'ABS will receive the source files, metadata, cover, and chapter structure.' : 'ABS metadata and existing progress remain untouched.'}</span></div>`}
    <div class="review-buttons"><button class="outlined" data-action="back">Back</button><button class="filled" data-action="${blocked ? 'blocked' : isNew ? 'upload' : 'overwrite'}" ${blocked ? 'disabled' : ''}>${isNew ? 'Upload to ABS' : 'Overwrite file'}</button></div>
  </div></div>`;
}

function resultScreen() {
  const overwrite = state.scenario === 'compatible';
  const d = media();
  return `<div class="screen review-screen">${topBar('Import complete')}<div class="review-content"><div class="result-icon">✓</div><h2 class="result-title">${overwrite ? 'File replaced' : 'Item uploaded'}</h2><p class="result-copy">${overwrite ? 'The existing ABS item keeps its metadata and progress.' : 'Audiobookshelf now owns the complete item representation.'}</p><ul class="result-list"><li><span>${overwrite ? 'Replacement file' : 'Files and structure'}</span><strong>Done</strong></li><li><span>${overwrite ? 'Server metadata' : 'Metadata and cover'}</span><strong>${overwrite ? 'Preserved' : 'Transferred'}</strong></li><li><span>${overwrite ? 'Existing progress' : `${d.label} progress`}</span><strong>${overwrite ? 'Preserved' : 'Transferred'}</strong></li></ul><button class="try-again" data-action="detail">Open item in Riffle</button></div></div>`;
}

function render() {
  if (state.screen === 'detail') appScreen.innerHTML = detailScreen();
  if (state.screen === 'destination') appScreen.innerHTML = destinationScreen();
  if (state.screen === 'review') appScreen.innerHTML = reviewScreen();
  if (state.screen === 'result') appScreen.innerHTML = resultScreen();
  bindActions();
}

function bindActions() {
  document.querySelectorAll('[data-action]').forEach((button) => button.addEventListener('click', () => {
    const action = button.dataset.action;
    if (action === 'toggle-menu') { state.menuOpen = !state.menuOpen; render(); }
    if (action === 'add' && canUpload()) setScreen('destination');
    if (action === 'continue') setScreen('review');
    if (action === 'back') setScreen(state.screen === 'review' ? 'destination' : 'detail');
    if (action === 'upload' || action === 'overwrite') simulateTransfer(action);
    if (action === 'detail') { setScreen('detail'); showToast('The imported item is now in your server library.'); }
  }));
}

function simulateTransfer(action) {
  const original = appScreen.innerHTML;
  const button = document.querySelector('.filled');
  if (button) { button.disabled = true; button.textContent = action === 'upload' ? 'Uploading…' : 'Replacing…'; }
  setTimeout(() => { setScreen('result'); }, 850);
}

formatSelect.addEventListener('change', (event) => { state.format = event.target.value; state.screen = 'detail'; render(); });
sourceSelect.addEventListener('change', (event) => { state.source = event.target.value; state.screen = 'detail'; render(); });
serversSelect.addEventListener('change', (event) => { state.servers = event.target.value; state.screen = 'detail'; render(); });
scenarioSelect.addEventListener('change', (event) => { state.scenario = event.target.value; state.screen = 'destination'; render(); });
render();

function showToast(message) { const toast = document.querySelector('#toast'); toast.textContent = message; toast.classList.add('visible'); setTimeout(() => toast.classList.remove('visible'), 2600); }
