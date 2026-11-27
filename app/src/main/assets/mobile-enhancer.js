(function () {
  var workRoot = window._ocWorkspace || '/storage/emulated/0/OpenCode';

  if (!window._ocFetchPatched) {
    window._ocFetchPatched = true;
    var origFetch = window.fetch;
    window.fetch = function () {
      var args = arguments;
      var url = args[0];
      var strUrl = typeof url === 'string' ? url : (url && url.url ? url.url : '');
      return origFetch.apply(this, args).then(function (res) {
        if (strUrl.indexOf('/path') !== -1) {
          return res.clone().json().then(function (data) {
            data.home = workRoot;
            if (!data.directory || data.directory.indexOf('server-home') !== -1) {
              data.directory = workRoot;
            }
            if (!data.worktree || data.worktree.indexOf('server-home') !== -1) {
              data.worktree = workRoot;
            }
            return new Response(JSON.stringify(data), {
              status: res.status,
              statusText: res.statusText,
              headers: res.headers
            });
          }).catch(function () { return res; });
        }
        if (strUrl.indexOf('/file') !== -1 && strUrl.indexOf('/file/content') === -1 && strUrl.indexOf('/file/status') === -1) {
          return res.clone().json().then(function (data) {
            if (Array.isArray(data)) {
              var clean = data.filter(function (item) {
                var n = item.name || '';
                var p = item.path || '';
                if (n.indexOf('.') === 0) return false;
                if (n === 'android-sdk' || n === 'toolchain') return false;
                if (p.indexOf('/.') !== -1 || p.indexOf('server-home') !== -1) return false;
                return true;
              });
              return new Response(JSON.stringify(clean), {
                status: res.status,
                statusText: res.statusText,
                headers: res.headers
              });
            }
            return res;
          }).catch(function () { return res; });
        }
        return res;
      });
    };
  }

  if (!document.getElementById('oc-mobile-enhancer-css')) {
    var st = document.createElement('style');
    st.id = 'oc-mobile-enhancer-css';
    st.textContent =
      '.oc-anchored-footer{position:sticky!important;bottom:0!important;background:#000000!important;z-index:50!important;}' +
      '[data-directory-path*="/."],[data-directory-path*="~/."],[data-directory-path*="server-home"],[data-directory-path*="android-sdk"],[data-directory-path*="toolchain"]{display:none!important;}' +
      '#oc-create-project-row:hover{background:rgba(255,255,255,0.08)!important;}' +
      '#oc-create-project-row:active{background:rgba(255,255,255,0.15)!important;}' +
      'button,a,[role="button"],#oc-create-project-row{touch-action:manipulation!important;}' +
      'div[role="dialog"],[data-slot="dialog-content"]{max-width:calc(100vw - 16px)!important;max-height:calc(100dvh - 24px)!important;overflow-y:auto!important;}' +
      'form[data-component="prompt-input-v2"]>div.flex.h-11,form[data-component="prompt-input"]>div.flex.h-11{height:auto!important;min-height:44px;padding-top:6px;padding-bottom:6px!important;}' +
      'form[data-component="prompt-input-v2"] div.min-w-0.flex-1,form[data-component="prompt-input"] div.min-w-0.flex-1{flex-wrap:wrap!important;row-gap:6px!important;}' +
      'form[data-component="prompt-input-v2"] [data-action="prompt-submit"],form[data-component="prompt-input-v2"] button[type="submit"],form[data-component="prompt-input"] [data-action="prompt-submit"],form[data-component="prompt-input"] button[type="submit"]{flex-shrink:0!important;}';
    document.head.appendChild(st);
  }

  function isEsUi() {
    var lang = (document.documentElement.lang || navigator.language || '').toLowerCase();
    var bodyTxt = document.body ? (document.body.textContent || '').slice(0, 600).toLowerCase() : '';
    return lang.indexOf('es') === 0 || bodyTxt.indexOf('proyecto') !== -1 || bodyTxt.indexOf('ajustes') !== -1;
  }

  window._ocOnProjectReady = function () {
    window._ocBusyProj = false;
    var lbl = document.getElementById('oc-create-label');
    if (lbl) lbl.textContent = isEsUi() ? 'Crear proyecto:' : 'Create project:';
  };

  window._ocOnProjectError = function (msg) {
    window._ocBusyProj = false;
    var lbl = document.getElementById('oc-create-label');
    if (lbl) lbl.textContent = (isEsUi() ? 'Error: ' : 'Error: ') + (msg || 'retry');
    setTimeout(function () {
      var l = document.getElementById('oc-create-label');
      if (l) l.textContent = isEsUi() ? 'Crear proyecto:' : 'Create project:';
    }, 2200);
  };

  function updateToolchainPill() {
    if (!window.AndroidBridge || !window.AndroidBridge.getRuntimeStatus) return;
    try {
      var st = JSON.parse(window.AndroidBridge.getRuntimeStatus());
      var pill = document.getElementById('oc-toolchain-pill');
      if (st.factoryReady) {
        if (pill) pill.remove();
        return;
      }
      if (!pill) {
        pill = document.createElement('div');
        pill.id = 'oc-toolchain-pill';
        pill.style.cssText = 'position:fixed;right:10px;bottom:66px;z-index:9999;background:rgba(16,185,129,0.16);border:1px solid rgba(16,185,129,0.45);color:#A7F3D0;font-size:11px;font-weight:600;padding:5px 10px;border-radius:999px;pointer-events:none;backdrop-filter:blur(6px);';
        document.body.appendChild(pill);
      }
      var prefix = isEsUi() ? 'SDK & Gradle: ' : 'SDK & Gradle: ';
      pill.textContent = prefix + (st.progress || 40) + '%';
    } catch (e) {}
  }

  function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function anchorSettingsAndHelp() {
    var btns = document.querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      var aria = (b.getAttribute('aria-label') || '').toLowerCase();
      var isSettings = b.querySelector('[name="settings-gear"],[data-icon="settings-gear"]') ||
        aria.indexOf('ajuste') !== -1 || aria.indexOf('setting') !== -1;
      if (isSettings && b.parentElement && !b.parentElement.classList.contains('oc-anchored-footer')) {
        b.parentElement.classList.add('oc-anchored-footer');
      }
    }
  }

  function getDrawer() {
    return document.querySelector('div[class*="fixed top-10 bottom-0 start-0"]') ||
      document.querySelector('[data-slot="sidebar"]') ||
      document.querySelector('nav');
  }

  function renderSidebarProjects(forceRefresh) {
    if (!window.AndroidBridge || !window.AndroidBridge.listProjects) return;
    var drawer = getDrawer();
    if (!drawer) return;

    var isVisible = drawer.classList.contains('translate-x-0') || drawer.offsetWidth > 0;
    if (!isVisible && !forceRefresh) return;

    var now = Date.now();
    if (forceRefresh || !window._ocProjectsCache || (now - (window._ocProjectsCacheAt || 0) > 2500)) {
      try {
        var raw = window.AndroidBridge.listProjects();
        window._ocProjectsCache = raw ? JSON.parse(raw) : [];
        window._ocProjectsCacheAt = now;
      } catch (e) {
        return;
      }
    }
    var list = window._ocProjectsCache || [];

    var allTexts = drawer.querySelectorAll('div,p,span');
    for (var i = 0; i < allTexts.length; i++) {
      var el = allTexts[i];
      var t = (el.textContent || '').trim().toLowerCase();
      if (t.indexOf('aún no hay nada') !== -1 || t.indexOf('no hay nada que mostrar') !== -1 ||
          t.indexOf('nothing here') !== -1 || t.indexOf('create a session') !== -1 || t.indexOf('crea una sesión') !== -1) {
        var emptyBox = el.closest('div.flex.flex-col') || el.parentElement;
        if (emptyBox && list.length > 0) {
          emptyBox.style.display = 'none';
        }
      }
    }

    var targetContainer = document.getElementById('oc-sidebar-projects-container');
    if (!targetContainer) {
      targetContainer = document.createElement('div');
      targetContainer.id = 'oc-sidebar-projects-container';
      targetContainer.style.cssText = 'display:flex;flex-direction:column;gap:4px;padding:4px 0;width:100%;';

      var addBtn = null;
      var btns = drawer.querySelectorAll('button,a,div[role="button"]');
      for (var b = 0; b < btns.length; b++) {
        var txt = (btns[b].textContent || '').toLowerCase();
        if (txt.indexOf('añadir proyecto') !== -1 || txt.indexOf('add project') !== -1 || txt.indexOf('open project') !== -1) {
          addBtn = btns[b];
          break;
        }
      }
      if (addBtn && addBtn.parentElement) {
        addBtn.parentElement.insertAdjacentElement('afterend', targetContainer);
      } else {
        var searchInput = drawer.querySelector('input');
        if (searchInput && searchInput.parentElement) {
          var formBox = searchInput.closest('form') || searchInput.parentElement;
          if (formBox && formBox.parentElement) {
            formBox.parentElement.insertAdjacentElement('afterend', targetContainer);
          }
        }
      }
    }

    var currentSig = list.map(function (p) { return p.name; }).join('|');
    if (targetContainer.dataset.sig !== currentSig) {
      targetContainer.dataset.sig = currentSig;
      targetContainer.innerHTML = '';

      var header = document.createElement('div');
      header.style.cssText = 'font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.05em;color:rgba(255,255,255,0.45);padding:6px 4px 2px 4px;';
      header.textContent = (isEsUi() ? 'Proyectos (' : 'Projects (') + list.length + ')';
      targetContainer.appendChild(header);

      for (var j = 0; j < list.length; j++) {
        (function (proj) {
          var item = document.createElement('div');
          item.className = 'w-full flex items-center justify-between rounded-lg px-2.5 py-2 cursor-pointer transition-colors';
          item.style.cssText = 'background:rgba(255,255,255,0.04);margin-bottom:4px;border:1px solid rgba(255,255,255,0.06);';
          item.innerHTML = '<div class="flex items-center gap-x-2.5 grow min-w-0">' +
            '<div class="shrink-0 size-5 flex items-center justify-center text-text-weak">' +
            '<svg class="size-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
            '<path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z"/></svg></div>' +
            '<span class="text-13-medium text-text-strong font-medium truncate">' + escapeHtml(proj.name) + '</span></div>' +
            '<span class="text-xs text-text-weak opacity-40">&rarr;</span>';
          item.onclick = function (e) {
            e.preventDefault();
            e.stopPropagation();
            if (window._ocBusyProj) return;
            window._ocBusyProj = true;
            setTimeout(function () { window._ocBusyProj = false; }, 4000);
            item.style.opacity = '0.6';
            if (window.AndroidBridge && window.AndroidBridge.openProject) {
              window.AndroidBridge.openProject(proj.name);
            }
          };
          targetContainer.appendChild(item);
        })(list[j]);
      }
    }
  }

  function isActualProjectDialog(dialog) {
    if (!dialog) return false;
    var titleEl = dialog.querySelector('[data-slot="dialog-title"],h1,h2,h3,header');
    var title = (titleEl ? titleEl.textContent : '').toLowerCase();
    var badWords = ['ajuste', 'setting', 'configura', 'preferenc', 'atajo', 'shortcut', 'permis', 'permission', 'modelo', 'model', 'proveedor', 'provider', 'servidor', 'server', 'cuenta', 'account', 'notifica', 'sonido', 'sound', 'tema', 'theme', 'fork', 'bifurc', 'ramific', 'mcp', 'comando', 'command', 'session', 'sesión', 'sesion'];
    for (var i = 0; i < badWords.length; i++) {
      if (title.indexOf(badWords[i]) !== -1) return false;
    }
    if (dialog.querySelector('[role="tablist"],[data-slot="tabs"]')) return false;
    var searchInput = dialog.querySelector('input:not([type="checkbox"]):not([type="radio"]):not([type="hidden"])');
    if (!searchInput) return false;
    var placeholder = (searchInput.placeholder || '').toLowerCase();
    var allText = (dialog.textContent || '').slice(0, 400).toLowerCase();
    return title.indexOf('proyecto') !== -1 || title.indexOf('project') !== -1 ||
      title.indexOf('carpeta') !== -1 || title.indexOf('folder') !== -1 ||
      placeholder.indexOf('carpeta') !== -1 || placeholder.indexOf('folder') !== -1 ||
      placeholder.indexOf('proyecto') !== -1 || placeholder.indexOf('project') !== -1 ||
      placeholder.indexOf('directorio') !== -1 || placeholder.indexOf('search') !== -1 ||
      placeholder.indexOf('buscar') !== -1 ||
      searchInput.getAttribute('data-slot') === 'list-search-input' ||
      !!dialog.querySelector('[data-directory-path]') ||
      allText.indexOf('proyecto') !== -1 || allText.indexOf('project') !== -1;
  }

  function triggerCreateProjectFromInput(searchInput, e) {
    if (!searchInput) return;
    if (e) { e.preventDefault(); e.stopPropagation(); }
    if (window._ocBusyProj) return;
    window._ocBusyProj = true;
    setTimeout(function () { window._ocBusyProj = false; }, 1500);
    var val = (searchInput.value || '').trim();
    var pName = (val || ('project-' + Math.floor(Math.random() * 1000))).replace(/[^a-zA-Z0-9._-]/g, '-').replace(/^\.+/, '') || 'new-project';
    try { searchInput.blur(); } catch (err) {}
    var lbl = document.getElementById('oc-create-label');
    if (lbl) lbl.textContent = isEsUi() ? 'Abriendo…' : 'Opening…';
    if (window.AndroidBridge && window.AndroidBridge.createProject) {
      window._ocProjectsCacheAt = 0;
      window.AndroidBridge.createProject(pName);
    }
  }

  function isEnterLikeEvent(e) {
    return e && (e.key === 'Enter' || e.code === 'Enter' || e.code === 'NumpadEnter' || e.keyCode === 13 || e.which === 13);
  }

  function isProjectDialogInput(el) {
    if (!el || el.tagName !== 'INPUT') return false;
    var dlg = el.closest('dialog,[role="dialog"],[data-slot="dialog-content"],[data-dialog-layer],[data-component="dialog"]');
    if (dlg && isActualProjectDialog(dlg)) return true;
    if (el.getAttribute('data-slot') === 'list-search-input' && !!document.getElementById('oc-create-project-row')) return true;
    return false;
  }

  function findOpenProjectInput() {
    var active = document.activeElement;
    if (isProjectDialogInput(active)) return active;
    var last = window._ocLastProjectInput;
    if (last && document.body && document.body.contains(last) && isProjectDialogInput(last)) return last;
    var dialogs = document.querySelectorAll('dialog,[role="dialog"],[data-slot="dialog-content"],[data-dialog-layer],[data-component="dialog"]');
    for (var i = 0; i < dialogs.length; i++) {
      if (isActualProjectDialog(dialogs[i])) {
        var inp = dialogs[i].querySelector('input:not([type="checkbox"]):not([type="radio"]):not([type="hidden"])');
        if (inp && inp.offsetParent !== null) return inp;
      }
    }
    return null;
  }

  window._ocHandleImeEnter = function () {
    var target = findOpenProjectInput();
    if (target) {
      triggerCreateProjectFromInput(target);
      return true;
    }
    return false;
  };

  function bindProjectSearchInput(searchInput) {
    if (!searchInput) return;
    window._ocLastProjectInput = searchInput;
    // Keep default Android Enter key behavior (never force enterKeyHint='go', which suppresses KEYCODE_ENTER outside <form>)
    try { searchInput.removeAttribute('enterkeyhint'); } catch (e) {}
    if (!searchInput._ocHooked) {
      searchInput._ocHooked = true;
      searchInput.addEventListener('focus', function () {
        window._ocLastProjectInput = searchInput;
      }, true);
      var onKey = function (e) {
        if (isEnterLikeEvent(e)) {
          triggerCreateProjectFromInput(searchInput, e);
        }
      };
      searchInput.addEventListener('keydown', onKey, true);
      searchInput.addEventListener('keypress', onKey, true);
      searchInput.addEventListener('keyup', onKey, true);
      searchInput.addEventListener('beforeinput', function (e) {
        if (e && (e.inputType === 'insertLineBreak' || e.inputType === 'insertParagraph'
            || (e.data && e.data.indexOf('\n') !== -1))) {
          triggerCreateProjectFromInput(searchInput, e);
        }
      }, true);
      searchInput.addEventListener('input', function () {
        var prev = document.getElementById('oc-create-name-preview');
        if (prev) prev.textContent = searchInput.value.trim() || (isEsUi() ? 'nuevo-proyecto' : 'new-project');
      });
    }
    var form = searchInput.closest('form');
    if (form && !form._ocSubHooked) {
      form._ocSubHooked = true;
      form.addEventListener('submit', function (e) {
        triggerCreateProjectFromInput(searchInput, e);
      }, true);
    }
  }

  if (!window._ocGlobalEnterHooked) {
    window._ocGlobalEnterHooked = true;
    var globalEnter = function (e) {
      if (!isEnterLikeEvent(e)) return;
      var active = document.activeElement;
      if (isProjectDialogInput(active)) {
        triggerCreateProjectFromInput(active, e);
      }
    };
    document.addEventListener('keydown', globalEnter, true);
    document.addEventListener('keypress', globalEnter, true);
    document.addEventListener('keyup', globalEnter, true);
  }

  function enhanceOpenProjectDialog(dialog) {
    var searchInput = dialog.querySelector('input');
    if (!searchInput) return;

    bindProjectSearchInput(searchInput);

    var pathElements = dialog.querySelectorAll('[data-directory-path]');
    for (var i = 0; i < pathElements.length; i++) {
      var val = pathElements[i].getAttribute('data-directory-path') || '';
      if (val.indexOf('/.') !== -1 || val.indexOf('~/.') !== -1 || val.indexOf('server-home') !== -1 || val.indexOf('toolchain') !== -1 || val.indexOf('android-sdk') !== -1) {
        var row = pathElements[i].closest('.rounded-md') || pathElements[i].parentElement;
        if (row) row.style.display = 'none';
      }
    }

    if (!document.getElementById('oc-create-project-row')) {
      var es = isEsUi();
      var createRow = document.createElement('div');
      createRow.id = 'oc-create-project-row';
      createRow.className = 'w-full flex items-center justify-between rounded-md px-2 py-2 cursor-pointer transition-colors';
      createRow.style.cssText = 'border-bottom:1px solid rgba(255,255,255,0.08);margin:6px 0;';
      var curVal = (searchInput.value || '').trim();
      createRow.innerHTML = '<div class="flex items-center gap-x-3 grow min-w-0">' +
        '<div class="shrink-0 size-5 flex items-center justify-center text-text-weak">' +
        '<svg class="size-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
        '<path d="M12 5v14M5 12h14"/></svg></div>' +
        '<div class="flex items-center text-14-regular min-w-0">' +
        '<span id="oc-create-label" class="text-text-strong whitespace-nowrap font-medium">' + (es ? 'Crear proyecto:' : 'Create project:') + '</span>' +
        '<span id="oc-create-name-preview" class="whitespace-nowrap font-medium ml-1.5 overflow-hidden text-ellipsis truncate text-text-strong">' + escapeHtml(curVal || (es ? 'nuevo-proyecto' : 'new-project')) + '</span>' +
        '</div></div>' +
        '<span class="text-12-regular text-text-weak opacity-60 px-1.5 py-0.5 rounded border border-white/10">Enter &#8629;</span>';

      createRow.onclick = function (e) {
        triggerCreateProjectFromInput(searchInput, e);
      };

      // Anchor right after the search bar wrapper so SolidJS <Show> never unmounts it when typing an unknown folder name
      var searchWrap = dialog.querySelector('[data-slot="list-search-wrapper"]');
      if (searchWrap && searchWrap.parentElement) {
        searchWrap.insertAdjacentElement('afterend', createRow);
      } else {
        var listContainer = dialog.querySelector('[data-slot="list-scroll"],[data-component="list"],.px-3');
        if (listContainer) {
          listContainer.insertAdjacentElement('afterbegin', createRow);
        } else {
          var parentBox = searchInput.closest('form') || searchInput.parentElement;
          if (parentBox && parentBox.parentElement) parentBox.parentElement.appendChild(createRow);
        }
      }
    }
  }

  function enhanceSettingsDialog(dialog) {
    var bad = dialog.querySelector('#oc-create-project-row');
    if (bad) bad.remove();
  }

  function handleDialogs() {
    var existingRow = document.getElementById('oc-create-project-row');
    if (existingRow) {
      var parentDialog = existingRow.closest('dialog,[role="dialog"],[data-slot="dialog-content"],[data-dialog-layer]');
      if (!parentDialog || !isActualProjectDialog(parentDialog)) {
        existingRow.remove();
      }
    }
    var dialogs = document.querySelectorAll('dialog,[role="dialog"],[data-slot="dialog-content"],[data-dialog-layer]');
    for (var i = 0; i < dialogs.length; i++) {
      var d = dialogs[i];
      if (isActualProjectDialog(d)) enhanceOpenProjectDialog(d);
      else enhanceSettingsDialog(d);
    }
  }

  function checkFirstPaintReady() {
    if (window._ocUiReadyNotified) return;
    var root = document.getElementById('root') || document.body;
    if (root && root.children && root.children.length > 0 && (document.body.innerText || '').trim().length > 0) {
      window._ocUiReadyNotified = true;
      if (window.AndroidBridge && window.AndroidBridge.notifyUiReady) {
        window.AndroidBridge.notifyUiReady();
      }
    }
  }

  function tick(forceProjects) {
    anchorSettingsAndHelp();
    renderSidebarProjects(forceProjects);
    handleDialogs();
    updateToolchainPill();
    checkFirstPaintReady();
  }

  if (!window._ocPillInterval) {
    window._ocPillInterval = setInterval(updateToolchainPill, 900);
  }

  if (!window._ocClickHooked) {
    window._ocClickHooked = true;
    document.addEventListener('click', function (e) {
      var menu = e.target.closest('button[aria-label*="sidebar" i],button[aria-label*="menu" i]');
      if (menu) {
        setTimeout(function () { tick(true); }, 120);
      }
    }, true);
  }

  var scheduled = false;
  function scheduleTick() {
    if (scheduled) return;
    scheduled = true;
    setTimeout(function () {
      scheduled = false;
      tick(false);
    }, 100);
  }

  function ensureObserver() {
    if (document.body) {
      tick(true);
      if (!window._ocObserver) {
        window._ocObserver = new MutationObserver(scheduleTick);
        window._ocObserver.observe(document.body, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeFilter: ['class', 'aria-expanded', 'role']
        });
      }
    } else {
      setTimeout(ensureObserver, 50);
    }
  }
  ensureObserver();
})();
