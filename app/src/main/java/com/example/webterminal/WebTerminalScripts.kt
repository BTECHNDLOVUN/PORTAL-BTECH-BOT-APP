package com.example.webterminal

import org.json.JSONObject

object WebTerminalScripts {

    private const val DEEP_DOM_HELPERS = """
        function collectRoots(root, out) {
          if (!root) return;
          out.push(root);
          let all = [];
          try { all = root.querySelectorAll('*'); } catch (_) { return; }
          for (let i = 0; i < all.length; i++) {
            const el = all[i];
            if (el.shadowRoot) {
              collectRoots(el.shadowRoot, out);
            }
            if (el.tagName === 'IFRAME') {
              try {
                if (el.contentDocument) collectRoots(el.contentDocument, out);
              } catch (_) {}
            }
          }
        }

        function queryDeepAll(selector) {
          const roots = [];
          collectRoots(document, roots);
          const results = [];
          const seen = new Set();
          for (const r of roots) {
            try {
              const found = r.querySelectorAll(selector);
              for (const node of found) {
                if (!seen.has(node)) {
                  seen.add(node);
                  results.push(node);
                }
              }
            } catch (_) {}
          }
          return results;
        }

        function collectDeepText() {
          const roots = [];
          collectRoots(document, roots);
          let text = '';
          for (const r of roots) {
            try {
              const t = (r.body ? r.body.innerText : r.textContent) || '';
              if (t) text += '\n' + t;
            } catch (_) {}
          }
          return text;
        }

        function isVisible(el) {
          if (!el) return false;
          try {
            if (typeof el.checkVisibility === 'function') {
              if (!el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) return false;
            }
            const rect = el.getBoundingClientRect();
            if (!rect || rect.width <= 1 || rect.height <= 1) return false;
            let cur = el;
            while (cur) {
              if (cur.nodeType === 1) {
                if (cur.hidden) return false;
                const st = window.getComputedStyle(cur);
                if (!st || st.display === 'none' || st.visibility === 'hidden' || st.visibility === 'collapse') return false;
                if (parseFloat(st.opacity || '1') <= 0.05) return false;
                if (st.pointerEvents === 'none') return false;
              }
              if (cur.parentNode) {
                cur = cur.parentNode;
              } else if (cur.host) {
                cur = cur.host;
              } else {
                break;
              }
            }
            return !el.disabled;
          } catch (_) {
            return true;
          }
        }

        function hasActiveTradingWorkspace(lowerText) {
          const hasClassicMt =
            (lowerText.includes('balance') && (lowerText.includes('equity') || lowerText.includes('margin') || lowerText.includes('free margin'))) ||
            (lowerText.includes('market watch') && (lowerText.includes('new order') || lowerText.includes('trade'))) ||
            queryDeepAll('[data-testid*="trade"], [class*="trade-panel"], [class*="quotes"], [class*="positions"], [class*="market-watch"]').filter(isVisible).length > 0;
          if (hasClassicMt) return true;

          // Exness / MetaQuotes MT5 mobile & React WebTerminal views (Order Ticket / Quotes-Chart-Trade / Active Canvas)
          const hasMt5OrderTicket =
            (lowerText.includes('instant execution') || lowerText.includes('market execution')) &&
            lowerText.includes('volume') &&
            lowerText.includes('sell') &&
            lowerText.includes('buy');
          if (hasMt5OrderTicket) return true;

          const hasMt5Nav =
            lowerText.includes('quotes') &&
            lowerText.includes('chart') &&
            (lowerText.includes('trade') || lowerText.includes('history'));
          if (hasMt5Nav) return true;

          const onTerminalRoute = /\/terminal\/(?:chart|quotes|trade|history|orders|positions)/i.test(window.location.pathname || '');
          const hasChartCanvas = queryDeepAll('canvas').some(function(c) {
            try { return isVisible(c) && c.getBoundingClientRect().width > 120; } catch (_) { return false; }
          });
          return onTerminalRoute && hasChartCanvas && !lowerText.includes('trading accounts');
        }

        function serverMatches(candidate, target) {
          const a = (candidate || '').trim().toLowerCase();
          const b = (target || '').trim().toLowerCase();
          if (!a || !b) return false;
          if (a === b) return true;
          const norm = (s) => s.replace(/justmarkets/g, 'jmarket').replace(/jmarkets/g, 'jmarket').replace(/[^a-z0-9]/g, '');
          return norm(a) === norm(b);
        }

        function setNativeValue(el, value) {
          if (!el) return false;
          try { el.focus(); } catch (_) {}
          const proto = el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
          const desc = Object.getOwnPropertyDescriptor(proto, 'value');
          if (desc && desc.set) {
            desc.set.call(el, value);
          } else {
            el.value = value;
          }
          el.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
          el.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
          el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, composed: true }));
          return true;
        }

        function singleClick(el) {
          if (!el) return false;
          try { if (typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (_) {}
          try { if (typeof el.focus === 'function') el.focus(); } catch (_) {}
          try {
            if (typeof el.click === 'function') {
              el.click();
            } else {
              const rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
              const cx = rect ? (rect.left + rect.width / 2) : 0;
              const cy = rect ? (rect.top + rect.height / 2) : 0;
              el.dispatchEvent(new MouseEvent('click', {
                bubbles: true,
                cancelable: true,
                composed: true,
                view: window,
                clientX: cx,
                clientY: cy
              }));
            }
          } catch (_) {}
          return true;
        }

        function realClick(el) {
          return singleClick(el);
        }
    """

    /**
     * Generates JS that traverses DOM, Shadow DOM, and same-origin iframes to detect
     * MT4/MT5 WebTerminal login fields, handles MT5 "Trading accounts" intermediate screens,
     * fills login/password/server, submits the form, and polls for active workspace or errors.
     */
    fun buildAuthScript(
        login: String,
        password: String,
        server: String,
        platform: String,
    ): String {
        val safeLogin = JSONObject.quote(login)
        val safePass = JSONObject.quote(password)
        val safeServer = JSONObject.quote(server)
        val safePlatform = JSONObject.quote(platform)

        return """
            (function() {
              if (window.__btechAuthTimer) {
                clearTimeout(window.__btechAuthTimer);
                window.__btechAuthTimer = null;
              }

              const LOGIN = $safeLogin;
              const PASSWORD = $safePass;
              const SERVER = $safeServer;
              const PLATFORM = $safePlatform;

              $DEEP_DOM_HELPERS

              function notify(ok, msg) {
                if (window.BtechBridge && typeof window.BtechBridge.onAuthResult === 'function') {
                  window.BtechBridge.onAuthResult(!!ok, String(msg || ''));
                }
              }

              function logStep(msg) {
                if (window.BtechBridge && typeof window.BtechBridge.onTradeLog === 'function') {
                  window.BtechBridge.onTradeLog(String(msg), false, false);
                }
              }

              function findInputs() {
                const inputs = queryDeepAll('input, select').filter(isVisible);
                const passwordInput = inputs.find(el => (el.type || '').toLowerCase() === 'password');

                const loginInput = inputs.find(el => {
                  if (el === passwordInput) return false;
                  const type = (el.type || '').toLowerCase();
                  if (type === 'checkbox' || type === 'radio' || type === 'hidden' || type === 'submit') return false;
                  const meta = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
                  return meta.includes('login') || meta.includes('account') || meta.includes('user') || meta.includes('email') || type === 'number' || type === 'text';
                });

                const serverInput = inputs.find(el => {
                  if (el === passwordInput || el === loginInput) return false;
                  const meta = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
                  return meta.includes('server') || meta.includes('broker') || el.tagName === 'SELECT';
                });

                return { allCount: inputs.length, loginInput, passwordInput, serverInput };
              }

              function findActionButton(matchFn) {
                const buttons = queryDeepAll('button, [role="button"], a').filter(isVisible);
                const strict = buttons.find(el => {
                  const t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  return matchFn(t);
                });
                if (strict) return strict;
                const loose = queryDeepAll('div, span').filter(isVisible);
                return loose.find(el => {
                  const t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  return t.length <= 40 && matchFn(t);
                });
              }

              function clickPlatformOrConnectTrigger() {
                const disclaimerBtn = findActionButton(t => t === 'accept' || t === 'i agree' || t === 'agree' || t === 'continue');
                if (disclaimerBtn) {
                  realClick(disclaimerBtn);
                  return true;
                }

                // Switch to MT5 / MT4 tab if present on multi-platform webtrader
                const platformTab = findActionButton(t => {
                  const u = t.toUpperCase();
                  return u === PLATFORM || u === ('METATRADER ' + PLATFORM.replace('MT', ''));
                });
                if (platformTab) {
                  realClick(platformTab);
                }

                // Prefer real <button> "Connect to account" (e.g. Exness MT5 Trading accounts card) over top tabs
                const primaryButtons = queryDeepAll('button, [role="button"]').filter(isVisible);
                const realBtn = primaryButtons.find(el => {
                  const t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  return t === 'connect to account' || t === 'log in' || t === 'login' || t === 'sign in';
                });
                if (realBtn) {
                  realClick(realBtn);
                  return true;
                }

                const openLoginBtn = findActionButton(t => t === 'connect to account' || t === 'log in' || t === 'login' || t === 'sign in');
                if (openLoginBtn) {
                  realClick(openLoginBtn);
                  return true;
                }
                return false;
              }

              function findSubmitButton() {
                const candidates = queryDeepAll('button, input[type="submit"], [role="button"]').filter(isVisible);
                return candidates.find(el => {
                  const txt = (el.innerText || el.value || el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  return txt === 'connect' || txt === 'ok' || txt.includes('connect to account') || txt === 'log in' || txt === 'login' || txt === 'sign in' || txt === 'submit';
                });
              }

              let attempts = 0;
              let submitted = false;
              let submittedAtAttempt = -1;
              let lastTriggerAttempt = -10;
              let lastPickerConnectAttempt = -10;

              function checkTerminalState() {
                const rawText = collectDeepText();
                const lower = rawText.toLowerCase();

                const errorTerms = [
                  'network connection failed',
                  'connection failed',
                  'authorization failed',
                  'invalid account',
                  'invalid login',
                  'invalid password',
                  'wrong password',
                  'account disabled',
                  'incorrect login',
                  'server not found',
                  'invalid server',
                  'invalid credentials',
                  'old version',
                  'trade is disabled'
                ];
                function extractMatchedSnippet(text, term) {
                  const lines = (text || '').split(/\r?\n/).map(l => l.trim()).filter(Boolean);
                  const hit = lines.find(l => l.toLowerCase().includes(term));
                  if (hit) return hit.slice(0, 220);
                  const idx = (text || '').toLowerCase().indexOf(term);
                  if (idx >= 0) return text.slice(Math.max(0, idx - 40), idx + 140).replace(/\s+/g, ' ').trim();
                  return term;
                }
                for (const term of (submitted ? errorTerms : [])) {
                  if (lower.includes(term)) {
                    const snippet = extractMatchedSnippet(rawText, term);
                    const curUrl = window.location.href || '';
                    if (term === 'network connection failed') {
                      return {
                        done: true,
                        ok: false,
                        msg: "The web terminal could not reach your broker's server. Check the server name and the broker's own terminal URL.\n\nTerminal text: \"" + snippet + "\"\nURL: " + curUrl
                      };
                    }
                    return {
                      done: true,
                      ok: false,
                      msg: 'Web Terminal reported: "' + snippet + '"\nURL: ' + curUrl
                    };
                  }
                }

                const { passwordInput } = findInputs();
                const isCloudflare =
                  (lower.includes('just a moment') ||
                    lower.includes('verify you are human') ||
                    lower.includes('checking your browser') ||
                    lower.includes('performing security verification') ||
                    document.title.toLowerCase().includes('just a moment')) &&
                  !passwordInput;

                if (isCloudflare && attempts >= 4) {
                  return {
                    done: true,
                    ok: false,
                    msg: 'Broker terminal showed a Cloudflare verification challenge.\nTerminal text: "' + (document.title || 'Just a moment') + '"\nURL: ' + (window.location.href || '')
                  };
                }

                const hasAccountsPicker = lower.includes('trading accounts');
                const hasSpinner = queryDeepAll('[role="progressbar"], [class*="spinner"], [class*="loader"], [class*="loading"]').some(isVisible);
                const isStillConnecting =
                  lower.includes('connecting...') ||
                  lower.includes('authorizing...') ||
                  lower.includes('waiting for update') ||
                  hasSpinner;

                // If MT5 landed on "Trading accounts:" after login with green "Connect to account" button,
                // click the account's "Connect to account" button once spinner clears so terminal opens chart.
                if (submitted && !passwordInput && hasAccountsPicker && !isStillConnecting && (attempts - lastPickerConnectAttempt) >= 6) {
                  const accountMatchNode = queryDeepAll('div, span, p').filter(isVisible).find(el => {
                    const t = (el.innerText || el.textContent || '').trim();
                    return t.includes(LOGIN) && t.length <= 100;
                  });
                  if (accountMatchNode) {
                    const connectBtn = queryDeepAll('button, [role="button"]').filter(isVisible).find(el => {
                      const t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                      return t === 'connect to account' || t === 'connect';
                    });
                    if (connectBtn) {
                      lastPickerConnectAttempt = attempts;
                      logStep('Selecting saved ' + PLATFORM + ' account ' + LOGIN + '...');
                      realClick(connectBtn);
                      return { done: false, lower: lower };
                    }
                  }
                }

                // After clicking Connect/OK, wait at least 5 ticks (~2.5s) for broker WebSocket handshake
                if (submitted && (attempts - submittedAtAttempt) < 5) {
                  return { done: false, lower: lower };
                }
                if (!submitted && attempts < 4) {
                  return { done: false, lower: lower };
                }

                // If login dialog remains/re-appears ~12s after submitting, report current terminal text & URL.
                if (submitted && (attempts - submittedAtAttempt) >= 24 && passwordInput) {
                  const visibleSample = rawText.split(/\r?\n/).map(l => l.trim()).filter(Boolean).slice(0, 4).join(' | ').slice(0, 200);
                  return {
                    done: true,
                    ok: false,
                    msg: 'Web Terminal still shows the login form after submitting.\nTerminal text: "' + (visibleSample || 'Login dialog visible') + '"\nURL: ' + (window.location.href || '')
                  };
                }

                const hasTradingWorkspace = hasActiveTradingWorkspace(lower);
                const hasLiveBalanceOrQuotes =
                  /\b(balance|equity)\b[\s:]*-?\d+[\d,]*\.\d{2}/i.test(rawText) ||
                  /\b\d[\d\s,]*\.\d{2}\s*(?:USD|EUR|GBP|ZAR|JPY|AUD|CAD|CHF|USC)\b/i.test(rawText) ||
                  /\b\d{1,6}\.\d{2,5}\b/.test(rawText);

                if (!passwordInput && !hasAccountsPicker && !isStillConnecting && hasTradingWorkspace && hasLiveBalanceOrQuotes) {
                  return { done: true, ok: true, msg: 'Authenticated on ' + PLATFORM + ' web terminal (' + SERVER + ')' };
                }

                // Fallback for React/Canvas MT5 where chart/nav or order screen is open cleanly after submit without quotes text in DOM
                const isMt5ReadyChart =
                  submitted &&
                  !passwordInput &&
                  !hasAccountsPicker &&
                  !isStillConnecting &&
                  (attempts - submittedAtAttempt) >= 7 &&
                  (
                    hasTradingWorkspace ||
                    /\/terminal\/(?:chart|quotes|trade|history)/i.test(window.location.pathname || '')
                  );
                if (isMt5ReadyChart) {
                  return { done: true, ok: true, msg: 'Authenticated on ' + PLATFORM + ' web terminal (' + SERVER + ')' };
                }

                // Also recognize if "Trading accounts" screen already connected and displays active account + currency balance without password prompt
                const isAccountScreenConnected =
                  submitted &&
                  !passwordInput &&
                  hasAccountsPicker &&
                  !isStillConnecting &&
                  (attempts - submittedAtAttempt) >= 12 &&
                  lower.includes(LOGIN.toLowerCase()) &&
                  lower.includes('remove') &&
                  /\d[\d\s,]*\.\d{2}\s*[A-Z]{3}\b/i.test(rawText);
                if (isAccountScreenConnected) {
                  return { done: true, ok: true, msg: 'Authenticated on ' + PLATFORM + ' web terminal (' + SERVER + ')' };
                }

                return { done: false, lower: lower };
              }

              function tick() {
                try {
                attempts++;
                const state = checkTerminalState();
                if (state.done) {
                  notify(state.ok, state.msg);
                  return;
                }

                const found = findInputs();

                if (!found.passwordInput && !submitted && attempts >= 2 && (attempts - lastTriggerAttempt) >= 4) {
                  const clicked = clickPlatformOrConnectTrigger();
                  if (clicked) {
                    lastTriggerAttempt = attempts;
                    logStep('Opening ' + PLATFORM + ' login dialog on Web Terminal...');
                  }
                }

                if (!submitted && found.loginInput && found.passwordInput) {
                  logStep('Found ' + PLATFORM + ' login form (' + found.allCount + ' fields) — filling login ' + LOGIN);
                  setNativeValue(found.loginInput, LOGIN);
                  setNativeValue(found.passwordInput, PASSWORD);

                  function submitCredentials() {
                    const btn = findSubmitButton();
                    if (btn) {
                      submitted = true;
                      submittedAtAttempt = attempts;
                      logStep('Verifying handshake on ' + SERVER + '...');
                      realClick(btn);
                    }
                  }

                  if (found.serverInput) {
                    if (found.serverInput.tagName === 'SELECT') {
                      const opt = Array.from(found.serverInput.options).find(o =>
                        serverMatches(o.text, SERVER) || serverMatches(o.value, SERVER)
                      );
                      if (opt) setNativeValue(found.serverInput, opt.value);
                      submitCredentials();
                    } else {
                      setNativeValue(found.serverInput, SERVER);
                      realClick(found.serverInput);
                      setTimeout(() => {
                        const opts = queryDeepAll('li, div, span, option, [role="option"]').filter(isVisible);
                        const match = opts.find(o => serverMatches(o.innerText || o.textContent, SERVER));
                        if (match) realClick(match);
                        setTimeout(submitCredentials, 250);
                      }, 250);
                    }
                  } else {
                    // Check for custom chevron/combobox server selector on MT5 Web Terminal
                    const combo = queryDeepAll('[role="combobox"], [class*="select"], [class*="server"], button').filter(isVisible).find(el => {
                      const t = (el.innerText || el.textContent || '').toLowerCase();
                      const c = ((el.className || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
                      return c.includes('server') || t.includes('justmarkets') || t.includes('jmarket') || t.includes('exness') || t.includes('metaquotes') || t.includes('select server');
                    });
                    if (combo) {
                      realClick(combo);
                      setTimeout(() => {
                        const opts = queryDeepAll('li, div, span, [role="option"]').filter(isVisible);
                        const match = opts.find(o => serverMatches(o.innerText || o.textContent, SERVER));
                        if (match) realClick(match);
                        setTimeout(submitCredentials, 250);
                      }, 300);
                    } else {
                      submitCredentials();
                    }
                  }
                }

                if (attempts >= 90) {
                  const hasCrossOriginFrame = Array.from(document.querySelectorAll('iframe')).some(f => {
                    try { return !f.contentDocument; } catch (_) { return true; }
                  });
                  const sample = collectDeepText().split(/\r?\n/).map(l => l.trim()).filter(Boolean).slice(0, 4).join(' | ').slice(0, 200);
                  const curUrl = window.location.href || '';
                  const reason = hasCrossOriginFrame
                    ? 'Broker terminal loads login inside a cross-origin iframe.\nTerminal text: "' + (sample || 'iframe blocked') + '"\nURL: ' + curUrl
                    : 'Auto-login timed out waiting for trading workspace (' + found.allCount + ' input fields).\nTerminal text: "' + (sample || 'No readable terminal text') + '"\nURL: ' + curUrl;
                  notify(false, reason);
                  return;
                }

                window.__btechAuthTimer = setTimeout(tick, 500);
                } catch (err) {
                  notify(false, 'Web Terminal automation error: ' + (err && err.message ? err.message : err));
                }
              }

              logStep('Connected to ' + window.location.href + ' — scanning Web Terminal DOM...');
              window.__btechAuthTimer = setTimeout(tick, 700);
            })();
        """.trimIndent()
    }

    /**
     * Generates JS that traverses DOM/Shadow DOM/same-origin iframes to:
     * 1) Resolve symbol from Market Watch OR search/add non-subscribed symbols from the broker catalog,
     * 2) Extract base symbol before suffix and probe all common broker suffix variants (.m, m, .s, .c, .ecn, .pro, .raw, etc.),
     * 3) Populate MT4/MT5 Volume, SL, TP, Comment inputs (matching attributes OR nearby label containers),
     * 4) Locate MT4 "Buy by Market"/"Sell by Market" OR MT5 split "Buy"/"Sell" buttons,
     * 5) Execute COUNT trades sequentially ONE BY ONE, waiting for broker confirmation/ticket or error after each trade.
     */
    fun buildTradeScript(
        symbol: String,
        direction: String,
        lot: String,
        stopLoss: String,
        takeProfit: String,
        comment: String,
        tradeCount: Int,
    ): String {
        val safeSymbol = JSONObject.quote(symbol)
        val safeDir = JSONObject.quote(direction)
        val safeLot = JSONObject.quote(lot)
        val safeSl = JSONObject.quote(stopLoss)
        val safeTp = JSONObject.quote(takeProfit)
        val safeComment = JSONObject.quote(comment)

        return """
            (function() {
              const RUN_ID = (window.__btechTradeRunId || 0) + 1;
              window.__btechTradeRunId = RUN_ID;
              if (Array.isArray(window.__btechTradeTimers)) {
                window.__btechTradeTimers.forEach(function(id) { try { clearTimeout(id); } catch (_) {} });
              }
              window.__btechTradeTimers = [];

              function isCancelled() {
                return window.__btechTradeRunId !== RUN_ID;
              }

              function schedule(fn, delay) {
                const id = setTimeout(function() {
                  if (!isCancelled()) fn();
                }, delay);
                window.__btechTradeTimers.push(id);
                return id;
              }

              const SYMBOL = $safeSymbol;
              const DIRECTION = $safeDir;
              const LOT = $safeLot;
              const SL = $safeSl;
              const TP = $safeTp;
              const COMMENT = $safeComment;
              const COUNT = $tradeCount;

              $DEEP_DOM_HELPERS

              function setPhase(phaseKey, detail) {
                if (isCancelled()) return;
                if (window.BtechBridge && typeof window.BtechBridge.onTradePhase === 'function') {
                  window.BtechBridge.onTradePhase(String(phaseKey), String(detail || ''));
                }
              }

              function log(msg, err, done) {
                if (isCancelled()) return;
                if (window.BtechBridge && typeof window.BtechBridge.onTradeLog === 'function') {
                  window.BtechBridge.onTradeLog(String(msg), !!err, !!done);
                }
              }

              function visibleInputs() {
                return queryDeepAll('input').filter(isVisible);
              }

              function metaString(el) {
                return (
                  (el.name || '') + ' ' +
                  (el.id || '') + ' ' +
                  (el.placeholder || '') + ' ' +
                  (el.getAttribute('aria-label') || '') + ' ' +
                  (el.getAttribute('title') || '') + ' ' +
                  (el.className || '')
                ).toLowerCase();
              }

              function findByMeta(inputs, keywords) {
                return inputs.find(el => {
                  const meta = metaString(el);
                  return keywords.some(k => meta.includes(k));
                });
              }

              // Handles MT5 WebTerminal inputs whose placeholder/name are empty and labels ("Volume", "Stop Loss", "Take Profit", "Comment") sit above the stepper box
              function findOrderFieldInput(inputs, keywords, competingKeywords) {
                const byAttr = findByMeta(inputs, keywords);
                if (byAttr) return byAttr;

                for (const el of inputs) {
                  let cur = el.parentElement;
                  for (let depth = 0; depth < 4 && cur; depth++) {
                    const txt = (cur.innerText || cur.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                    if (txt && txt.length <= 70) {
                      const matchesTarget = keywords.some(k => txt.includes(k));
                      const hitsOther = competingKeywords.some(k => txt.includes(k));
                      if (matchesTarget && !hitsOther) {
                        return el;
                      }
                    }
                    cur = cur.parentElement;
                  }
                }
                return null;
              }

              function findSearchInput() {
                const inputs = visibleInputs();
                return findOrderFieldInput(
                  inputs,
                  ['search', 'symbol', 'filter', 'find', 'instrument'],
                  ['volume', 'stop loss', 'take profit', 'comment']
                ) || inputs.find(el => (el.type || '').toLowerCase() === 'search');
              }

              function dispatchEnter(el) {
                if (!el) return;
                try {
                  ['keydown', 'keypress', 'keyup'].forEach(ev => {
                    el.dispatchEvent(new KeyboardEvent(ev, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true }));
                  });
                } catch (_) {}
              }

              function findButton(keywords) {
                const btns = queryDeepAll('button, [role="button"], a').filter(isVisible);
                for (const k of keywords) {
                  const hit = btns.find(b => {
                    const t = ((b.innerText || b.textContent || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                    return t.includes(k);
                  });
                  if (hit) return hit;
                }
                return null;
              }

              function findStrictNewOrderButton() {
                const btns = queryDeepAll('button, [role="button"], input[type="button"], a').filter(isVisible);
                const strictKeys = ['new order', 'add order', 'create order', 'place order'];
                for (const k of strictKeys) {
                  const hit = btns.find(b => {
                    const t = ((b.innerText || b.value || b.textContent || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                    return t.includes(k);
                  });
                  if (hit) return hit;
                }
                return null;
              }

              function findSearchOrAddTrigger() {
                const strict = findButton(['add symbol', 'search symbol', 'add instrument', 'show all']);
                if (strict) return strict;
                const candidates = queryDeepAll('button, [role="button"], a, div, span').filter(isVisible);
                return candidates.find(el => {
                  const aria = ((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.className || '')).toLowerCase();
                  const txt = (el.innerText || el.textContent || '').trim();
                  return (aria.includes('search') || aria.includes('add-symbol') || aria.includes('plus') || txt === '+') && txt.length <= 24;
                });
              }

              function findOrderScope(nearInput) {
                if (!nearInput) return null;
                let cur = nearInput.parentElement;
                for (let depth = 0; depth < 7 && cur && cur !== document.body && cur !== document.documentElement; depth++) {
                  const txt = (cur.innerText || cur.textContent || '').toLowerCase();
                  const hasOrderWords = /\bbuy\b/i.test(txt) && /\bsell\b/i.test(txt);
                  const isEntireWorkspace = txt.includes('market watch') && txt.includes('balance');
                  if (hasOrderWords && !isEntireWorkspace) {
                    return cur;
                  }
                  cur = cur.parentElement;
                }
                return null;
              }

              // Matches MT4 "Buy by Market" / "Sell by Market" AND MT5 split "Buy" / "Sell" buttons strictly within order controls (never Trade History or Chart One-Click)
              function findOrderActionButton(dir, nearInput) {
                const target = dir === 'SELL' ? 'sell' : 'buy';
                const opposite = dir === 'SELL' ? 'buy' : 'sell';
                const scope = findOrderScope(nearInput);

                let candidates = [];
                if (scope) {
                  candidates = Array.from(scope.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"], a, div, span')).filter(isVisible);
                } else {
                  // Only search inside explicit dialogs/order panels when nearInput isn't passed
                  const dialogs = queryDeepAll('[role="dialog"], [role="alertdialog"], [class*="dialog"], [class*="modal"], [class*="window"], [class*="order-ticket"], [class*="order-panel"]').filter(isVisible);
                  if (dialogs.length > 0) {
                    dialogs.forEach(d => {
                      Array.from(d.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"], a, div, span'))
                        .filter(isVisible)
                        .forEach(c => candidates.push(c));
                    });
                  } else if (nearInput) {
                    candidates = queryDeepAll('button, [role="button"], input[type="button"], input[type="submit"], a, div, span').filter(isVisible);
                  } else {
                    return null;
                  }
                }

                const nearRect = nearInput ? nearInput.getBoundingClientRect() : null;
                let bestBtn = null;
                let bestScore = -1;

                for (const el of candidates) {
                  if (el.closest && el.closest('table, tr, [role="row"], [class*="history"], [class*="position"], [class*="deal"], [class*="toast"], [role="alert"]')) {
                    continue;
                  }
                  let rect = null;
                  try { rect = el.getBoundingClientRect(); } catch (_) {}
                  if (!rect || rect.width < 42 || rect.height < 22 || rect.width > window.innerWidth * 0.9 || rect.height > 140) {
                    continue;
                  }

                  const raw = ((el.innerText || el.value || el.textContent || '') + ' ' + (el.getAttribute('aria-label') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                  if (!raw || raw.length > 55) continue;
                  if (raw.includes(opposite)) continue;
                  if (!new RegExp('\\b' + target + '\\b', 'i').test(raw)) continue;

                  const isSemantic = (el.tagName === 'BUTTON' || el.getAttribute('role') === 'button' || el.tagName === 'A' || el.tagName === 'INPUT');
                  if (!isSemantic) {
                    if (el.children && el.children.length > 3) continue;
                    // If wrapped inside an actual button/role=button, skip the inner child so we score the parent button itself
                    if (el.closest && el.closest('button, [role="button"]')) continue;
                  }

                  let score = 0;
                  const exactMarket = raw === (target + ' by market');
                  const exactWord = raw === target;
                  const pricePlusWord = /^[\d\s.,+\-]*\b(sell|buy)\b[\d\s.,+\-]*$/i.test(raw);

                  if (exactMarket) score += 60;
                  else if (exactWord) score += 50;
                  else if (pricePlusWord) score += 40;
                  else if (raw.includes(target + ' by market') || raw.includes(target + ' now')) score += 35;
                  else score += 15;

                  if (isSemantic) score += 30;
                  try {
                    const st = window.getComputedStyle(el);
                    if (st && st.cursor === 'pointer') score += 10;
                    if (st && st.backgroundColor && st.backgroundColor !== 'rgba(0, 0, 0, 0)' && st.backgroundColor !== 'transparent') score += 8;
                  } catch (_) {}

                  if (nearRect && rect.top >= nearRect.top - 10) {
                    score += 15;
                  }

                  if (score > bestScore) {
                    bestScore = score;
                    bestBtn = el;
                  }
                }
                return bestBtn;
              }

              function extractBaseSymbol(sym) {
                const trimmed = (sym || '').trim();
                const dotIdx = trimmed.indexOf('.');
                if (dotIdx > 0) return trimmed.slice(0, dotIdx);
                const underIdx = trimmed.indexOf('_');
                if (underIdx > 0) return trimmed.slice(0, underIdx);
                const hashIdx = trimmed.indexOf('#');
                if (hashIdx > 0) return trimmed.slice(0, hashIdx);
                if (/^(?:GOLD|XAUUSD|XAGUSD|US30|US500|NAS100|USTEC|DE30|DE40|UK100|GER40|SPX500)[mc]$/i.test(trimmed)) {
                  return trimmed.slice(0, -1);
                }
                if (trimmed.length >= 7 && /[mc]$/i.test(trimmed)) {
                  return trimmed.slice(0, -1);
                }
                return trimmed;
              }

              const BASE_SYMBOL = extractBaseSymbol(SYMBOL);
              const SEARCH_QUERY = BASE_SYMBOL || SYMBOL;
              const escapedBase = BASE_SYMBOL.replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\$&');
              const VARIANT_REGEX = new RegExp('^' + escapedBase + '(?:\\.[a-zA-Z0-9]{1,8}|_[a-zA-Z0-9]{1,4}|[mc.#+]|pro|ecn|raw|std|micro|cent)?$', 'i');

              const SUFFIX_CANDIDATES = [
                SYMBOL,
                BASE_SYMBOL,
                BASE_SYMBOL + 'm',
                BASE_SYMBOL + '.m',
                BASE_SYMBOL + '.s',
                BASE_SYMBOL + '.c',
                BASE_SYMBOL + 'c',
                BASE_SYMBOL + '.ecn',
                BASE_SYMBOL + '.pro',
                BASE_SYMBOL + '.raw',
                BASE_SYMBOL + '.r',
                BASE_SYMBOL + '.micro',
                BASE_SYMBOL + '.std',
                BASE_SYMBOL + '.cent',
                BASE_SYMBOL + '.i',
                BASE_SYMBOL + '_i',
                BASE_SYMBOL + '.',
                BASE_SYMBOL + '#'
              ].filter(function(item, idx, arr) {
                return item && arr.findIndex(x => x.toLowerCase() === item.toLowerCase()) === idx;
              });

              function extractTokens(el) {
                const raw = (el.innerText || el.textContent || el.value || '').trim();
                if (!raw || raw.length > 90) return [];
                return raw.split(/[\s\n\r\t,/]+/).map(t => t.trim()).filter(Boolean);
              }

              function matchKind(text, allowVariant) {
                const t = (text || '').trim();
                if (!t) return null;
                if (t === SYMBOL) return { score: 4, symbol: t };
                if (t.toLowerCase() === SYMBOL.toLowerCase()) return { score: 3, symbol: t };
                if (t.toLowerCase() === BASE_SYMBOL.toLowerCase()) return { score: 2, symbol: t };
                if (allowVariant && VARIANT_REGEX.test(t)) return { score: 1, symbol: t };
                return null;
              }

              function findBestSymbolNode(allowVariant) {
                const candidates = queryDeepAll('td, div, span, li, button, a, [role="option"], [role="row"]').filter(isVisible);
                let best = null;
                for (const el of candidates) {
                  const raw = (el.innerText || el.textContent || '').trim();
                  const direct = matchKind(raw, allowVariant);
                  if (direct && (!best || direct.score > best.score)) {
                    best = { node: el, score: direct.score, symbol: direct.symbol };
                    if (direct.score === 4) break;
                  }
                  const tokens = extractTokens(el);
                  for (const tok of tokens) {
                    const m = matchKind(tok, allowVariant);
                    if (m && (!best || m.score > best.score)) {
                      best = { node: el, score: m.score, symbol: m.symbol };
                    }
                  }
                }
                return best;
              }

              function cleanSymbolLabel(raw) {
                const text = (raw || '').split(/[\r\n,;]+/)[0].trim();
                const tok = text.split(/\s+/)[0] || text;
                return tok.trim();
              }

              function findSymbolSelectOption(allowVariant) {
                const selects = queryDeepAll('select').filter(isVisible);
                for (const sel of selects) {
                  const opts = Array.from(sel.options || []);
                  const exact = opts.find(o => {
                    const txtSym = cleanSymbolLabel(o.text);
                    const valSym = cleanSymbolLabel(o.value);
                    return matchKind(txtSym, false) || matchKind(valSym, false);
                  });
                  if (exact) {
                    const sym = cleanSymbolLabel(exact.value) || cleanSymbolLabel(exact.text);
                    return { sel, opt: exact, symbol: sym };
                  }
                  if (allowVariant) {
                    const variant = opts.find(o => {
                      const txtSym = cleanSymbolLabel(o.text);
                      const valSym = cleanSymbolLabel(o.value);
                      return matchKind(txtSym, true) || matchKind(valSym, true);
                    });
                    if (variant) {
                      const sym = cleanSymbolLabel(variant.value) || cleanSymbolLabel(variant.text);
                      return { sel, opt: variant, symbol: sym };
                    }
                  }
                }
                return null;
              }

              function findCustomSymbolDropdown() {
                const triggers = queryDeepAll('[role="combobox"], [aria-haspopup="listbox"], button, [class*="select"], [class*="symbol"]').filter(isVisible);
                return triggers.find(el => {
                  const m = metaString(el);
                  const txt = (el.innerText || el.textContent || '').trim();
                  if (m.includes('symbol') || m.includes('instrument')) return true;
                  return txt.length >= 3 && txt.length <= 16 &&
                    /^(?:XAU|XAG|EUR|GBP|USD|AUD|NZD|CAD|CHF|JPY|BTC|ETH|US30|US500|DE30|DE40|GER40|USTEC|NAS100|GOLD)[A-Z0-9._#+]*$/i.test(txt) &&
                    (el.getAttribute('aria-expanded') !== null || el.tagName === 'BUTTON' || el.getAttribute('role') === 'combobox');
                });
              }

              function clickAddButtonInsideRow(symbolEl) {
                if (!symbolEl) return false;
                let row = symbolEl.closest('tr, li, [role="row"], [role="option"], div');
                for (let depth = 0; depth < 3 && row; depth++) {
                  const addBtns = Array.from(row.querySelectorAll('button, [role="button"], span, svg, a')).filter(isVisible);
                  const plusBtn = addBtns.find(b => {
                    const txt = (b.innerText || b.textContent || '').trim().toLowerCase();
                    const aria = ((b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '')).toLowerCase();
                    return txt === '+' || txt === 'add' || txt === 'show' || aria.includes('add') || aria.includes('watch') || aria.includes('show') || aria.includes('subscribe');
                  });
                  if (plusBtn && plusBtn !== symbolEl) {
                    realClick(plusBtn);
                    return true;
                  }
                  row = row.parentElement;
                }
                return false;
              }

              function sampleVisibleSymbols() {
                const text = collectDeepText();
                const matches = text.match(/\b(?:XAU|XAG|EUR|GBP|USD|AUD|NZD|CAD|CHF|JPY|BTC|ETH|US30|US500|DE30|DE40|GER40|USTEC|NAS100|GOLD)[A-Z0-9]{0,6}(?:\.[a-zA-Z0-9]+|[mc#])?\b/g) || [];
                const unique = [];
                for (const m of matches) {
                  if (!unique.includes(m)) unique.push(m);
                  if (unique.length >= 8) break;
                }
                return unique;
              }

              const BROKER_ERROR_TERMS = [
                'not enough money',
                'insufficient funds',
                'market closed',
                'market is closed',
                'off quotes',
                'requote',
                'trade is disabled',
                'trading disabled',
                'invalid stops',
                'invalid sl',
                'invalid tp',
                'invalid volume',
                'invalid price',
                'autotrading disabled',
                'order rejected',
                'failed to execute',
                'context is busy',
                'too many requests'
              ];

              function collectTradeRowText() {
                const rows = queryDeepAll('tr, [role="row"], [class*="position"], [class*="order"], [class*="deal"], [class*="trade-row"], [class*="toast"], [role="alert"], [role="dialog"], [class*="modal"]');
                let combined = '';
                for (const r of rows) {
                  try {
                    const t = (r.textContent || '').replace(/\s+/g, ' ').trim();
                    if (t && t.length <= 240) combined += '\n' + t;
                  } catch (_) {}
                }
                return combined;
              }

              function collectTicketIds(text) {
                const ids = new Set();
                const src = text || '';
                const hashMatches = src.match(/#\d{4,}/g) || [];
                for (const m of hashMatches) ids.add(m);
                const labeledMatches = src.match(/(?:\b(?:id|ticket|order|deal|position)\b[\s:#]*)\d{5,12}\b/gi) || [];
                for (const m of labeledMatches) {
                  const digits = (m.match(/\d{5,12}/) || [])[0];
                  if (digits) ids.add('#' + digits);
                }
                const rowText = collectTradeRowText();
                const rowLines = rowText.split(/\n+/);
                for (const line of rowLines) {
                  if (/\b(?:buy|sell)\b/i.test(line) || /\b(?:order|deal|position|ticket|opened|filled)\b/i.test(line)) {
                    const numIds = line.match(/\b\d{7,12}\b/g) || [];
                    for (const d of numIds) ids.add('#' + d);
                  }
                }
                return ids;
              }

              function countBrokerPositions() {
                const rows = queryDeepAll('tr, [role="row"], [class*="position-item"], [class*="position-row"], [class*="trade-item"], [class*="order-row"]');
                let count = 0;
                for (const r of rows) {
                  const t = (r.textContent || '').replace(/\s+/g, ' ').trim();
                  if (!t || t.length > 220) continue;
                  if (/\b(?:buy|sell)\b/i.test(t) && /\b\d+(?:\.\d+)?\b/.test(t) && !/\b(?:buy\s+limit|sell\s+limit|buy\s+stop|sell\s+stop)\b/i.test(t.slice(0, 12))) {
                    count++;
                  } else if (/\b(?:buy\s+limit|sell\s+limit|buy\s+stop|sell\s+stop)\b/i.test(t) && /\d+\.\d+/.test(t)) {
                    count++;
                  }
                }
                const badges = queryDeepAll('[role="tab"], button, a, span, div').filter(isVisible);
                for (const b of badges) {
                  const t = (b.innerText || b.textContent || '').replace(/\s+/g, ' ').trim();
                  if (t.length <= 28) {
                    const m = t.match(/^(?:positions?|trade|open\s+orders?|orders?)\s*[\(\[]?\s*(\d+)\s*[\)\]]?$/i);
                    if (m && m[1]) {
                      const n = parseInt(m[1], 10);
                      if (!isNaN(n) && n > count) count = n;
                    }
                  }
                }
                return count;
              }

              function readUsedMarginNumeric(rawText) {
                const m = (rawText || '').match(/(?:^|\n|\s)(?!free\s+)margin[\s:]*([+\-$€£¥A-Z]{0,4}\s*-?\d[\d\s,]*\.\d{2})/i);
                if (!m || !m[1]) return -1;
                const cleaned = m[1].replace(/[^\d.-]/g, '');
                const val = parseFloat(cleaned);
                return isNaN(val) ? -1 : val;
              }

              function countReceiptDoneHits(text) {
                const matches = (text || '').match(/\b(?:done|order\s+(?:has\s+been\s+)?(?:executed|placed|filled|opened))\b/gi);
                return matches ? matches.length : 0;
              }

              function extractDealSummary(text, prevTickets, tradeIdx) {
                const combinedText = (text || '') + '\n' + collectTradeRowText();
                const allTickets = collectTicketIds(combinedText);
                let newTicket = '';
                allTickets.forEach(function(t) {
                  if (!newTicket && !prevTickets.has(t)) newTicket = t;
                });
                const dirWord = DIRECTION === 'SELL' ? 'sell' : 'buy';
                const detailRe = new RegExp('(#\\d{4,})[^\\n]{0,35}\\b(buy|sell)\\b[^\\n]{0,35}\\b([\\d.]+)\\b[^\\n]{0,25}\\bat\\s+([\\d.,\\s]+)', 'i');
                const detailMatch = combinedText.match(detailRe);
                if (detailMatch && (!prevTickets.has(detailMatch[1]))) {
                  return 'Trade ' + tradeIdx + '/' + COUNT + ' confirmed · ' + detailMatch[1] + ' ' + detailMatch[2].toLowerCase() + ' ' + detailMatch[3] + ' ' + activeSymbolName + ' at ' + detailMatch[4].trim();
                }
                if (newTicket) {
                  return 'Trade ' + tradeIdx + '/' + COUNT + ' confirmed · ' + newTicket + ' ' + dirWord + ' ' + LOT + ' ' + activeSymbolName;
                }
                return 'Trade ' + tradeIdx + '/' + COUNT + ' confirmed · ' + dirWord + ' ' + LOT + ' ' + activeSymbolName;
              }

              let activeSymbolName = cleanSymbolLabel(SYMBOL) || SYMBOL;

              function populateOrderFields() {
                const selMatch = findSymbolSelectOption(true);
                if (selMatch) {
                  setNativeValue(selMatch.sel, selMatch.opt.value);
                  activeSymbolName = cleanSymbolLabel(selMatch.symbol) || activeSymbolName;
                }

                const inputs = visibleInputs();
                let volInput = findOrderFieldInput(inputs, ['volume', 'lot', 'size', 'qty'], ['stop loss', 'take profit', 'comment']);
                let slInput = findOrderFieldInput(inputs, ['sl', 'stop loss', 'stoploss'], ['volume', 'take profit', 'comment']);
                let tpInput = findOrderFieldInput(inputs, ['tp', 'take profit', 'takeprofit'], ['volume', 'stop loss', 'comment']);
                let commentInput = findOrderFieldInput(inputs, ['comment', 'note'], ['volume', 'stop loss', 'take profit']);

                // Positional fallback for unlabeled MT5 stepper fields when inside active Buy/Sell order sheet
                const testBuyOrSell = findOrderActionButton(DIRECTION, volInput);
                if (testBuyOrSell && (!volInput || (!slInput && SL) || (!tpInput && TP))) {
                  const actionTop = testBuyOrSell.getBoundingClientRect().top;
                  const orderInputs = inputs.filter(el => {
                    const r = el.getBoundingClientRect();
                    return r.bottom <= actionTop + 12 && (metaString(el).indexOf('search') < 0);
                  });
                  if (!volInput && orderInputs.length >= 1) {
                    volInput = orderInputs[0];
                  }
                  if (orderInputs.length >= 3) {
                    const rowPairs = orderInputs.filter(el => el !== volInput && el !== commentInput);
                    if (!slInput && rowPairs.length >= 1) slInput = rowPairs[0];
                    if (!tpInput && rowPairs.length >= 2) tpInput = rowPairs[1];
                  }
                  if (!commentInput && orderInputs.length >= 4) {
                    commentInput = orderInputs[orderInputs.length - 1];
                  }
                }

                if (volInput) setNativeValue(volInput, LOT);
                if (slInput && SL) setNativeValue(slInput, SL);
                if (tpInput && TP) setNativeValue(tpInput, TP);
                if (commentInput && COMMENT) setNativeValue(commentInput, COMMENT);

                return volInput;
              }

              function findReceiptConfirmButton() {
                const btns = queryDeepAll('button, [role="button"], input[type="button"], input[type="submit"], a').filter(isVisible);
                const exactHit = btns.find(b => {
                  const t = (b.innerText || b.value || b.textContent || b.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  return t === 'ok' || t === 'done' || t === 'close receipt';
                });
                if (!exactHit) return null;
                const inModal = !!(exactHit.closest && exactHit.closest('[role="dialog"], [role="alertdialog"], [class*="modal"], [class*="dialog"], [class*="receipt"], [class*="popup"], [class*="window"], [class*="order"], [class*="alert"]'));
                if (inModal) return exactHit;
                const bodyLower = collectDeepText().toLowerCase();
                if (/#\d{4,}/.test(bodyLower) && /\b(?:bought|sold|done|executed|opened|placed)\b/.test(bodyLower)) {
                  return exactHit;
                }
                return null;
              }

              function dismissReceiptDialogIfPresent() {
                const okBtn = findReceiptConfirmButton();
                if (okBtn) {
                  singleClick(okBtn);
                  return true;
                }
                return false;
              }

              function isButtonBusyOrDisabled(btn) {
                if (!btn || !btn.isConnected) return true;
                if (btn.disabled || btn.getAttribute('aria-disabled') === 'true') return true;
                const cls = ((btn.className || '') + ' ' + (btn.getAttribute('data-state') || '')).toLowerCase();
                if (cls.includes('disabled') || cls.includes('loading')) return true;
                const spinner = btn.querySelector ? btn.querySelector('[role="progressbar"], [class*="spinner"], [class*="loader"]') : null;
                return !!spinner;
              }

              function executeSequentialTrades() {
                let currentTrade = 0;
                let confirmedCount = 0;

                function runNextTrade() {
                  if (isCancelled()) return;
                  currentTrade++;
                  setPhase('PREPARE', 'Preparing trade ' + currentTrade + '/' + COUNT + ' (' + DIRECTION + ' ' + LOT + ' ' + activeSymbolName + ')...');
                  dismissReceiptDialogIfPresent();
                  ensureOrderPanelAndSubmit(0);
                }

                function ensureOrderPanelAndSubmit(openWaitTicks) {
                  if (isCancelled()) return;
                  dismissReceiptDialogIfPresent();

                  const volInput = populateOrderFields();
                  const actionBtn = findOrderActionButton(DIRECTION, volInput);

                  if (!actionBtn || !volInput) {
                    if (openWaitTicks === 0) {
                      const reopenBtn = findStrictNewOrderButton();
                      if (reopenBtn) {
                        singleClick(reopenBtn);
                      }
                    }
                    if (openWaitTicks < 14) {
                      schedule(function() {
                        ensureOrderPanelAndSubmit(openWaitTicks + 1);
                      }, 300);
                      return;
                    }
                    if (confirmedCount > 0) {
                      const partialErr = 'Order dialog did not reopen after ' + confirmedCount + '/' + COUNT + ' confirmed trade(s) on ' + activeSymbolName;
                      setPhase('ERROR', partialErr);
                      log(partialErr, true, true);
                    } else {
                      const msg = 'Order controls not ready (' + activeSymbolName + ' ' + DIRECTION + ' ' + LOT + '). Open New Order in Web Terminal to inspect.';
                      setPhase('ERROR', msg);
                      log(msg, true, true);
                    }
                    return;
                  }

                  const refreshedVol = populateOrderFields();
                  const finalBtn = findOrderActionButton(DIRECTION, refreshedVol) || actionBtn;

                  let readyTicks = 0;
                  function waitUntilReadyAndSubmitOnce() {
                    if (isCancelled()) return;
                    if (isButtonBusyOrDisabled(finalBtn)) {
                      if (readyTicks < 16) {
                        readyTicks++;
                        schedule(waitUntilReadyAndSubmitOnce, 300);
                        return;
                      }
                      const busyMsg = 'Order button stayed disabled for Trade ' + currentTrade + '/' + COUNT + ' (' + activeSymbolName + '). Check market hours or volume.';
                      setPhase('ERROR', busyMsg);
                      log(busyMsg, true, true);
                      return;
                    }

                    setPhase('PREPARED', 'Prepared Trade ' + currentTrade + '/' + COUNT + ' (' + DIRECTION + ' ' + LOT + ' ' + activeSymbolName + ')');

                    schedule(function() {
                      if (isCancelled()) return;
                      const baseText = collectDeepText();
                      const baseLower = baseText.toLowerCase();
                      const baseTickets = collectTicketIds(baseText);
                      const basePositions = countBrokerPositions();
                      const baseMargin = readUsedMarginNumeric(baseText);
                      const baseDoneHits = countReceiptDoneHits(baseText);
                      const hadReceiptBtnBefore = !!findReceiptConfirmButton();
                      const baseErrorSet = new Set(BROKER_ERROR_TERMS.filter(t => baseLower.includes(t)));

                      setPhase(
                        'TRADE_SUBMIT:' + currentTrade + ':' + COUNT,
                        'Trade ' + currentTrade + '/' + COUNT + ': executing ' + DIRECTION + ' ' + LOT + ' ' + activeSymbolName + '...'
                      );

                      // Fire EXACTLY ONE click per trade — NEVER re-click Buy/Sell automatically
                      singleClick(finalBtn);

                      setPhase(
                        'TRADE_CONFIRM:' + currentTrade + ':' + COUNT,
                        'Waiting for broker confirmation (Trade ' + currentTrade + '/' + COUNT + ')...'
                      );

                      let polls = 0;
                      const maxPolls = 60; // 18 seconds waiting for broker confirmation or rejection

                      function pollConfirmation() {
                        if (isCancelled()) return;
                        polls++;

                        const curText = collectDeepText();
                        const curLower = curText.toLowerCase();

                        // 1. Detect any NEW broker error message immediately
                        for (const term of BROKER_ERROR_TERMS) {
                          if (!baseErrorSet.has(term) && curLower.includes(term)) {
                            const lines = curText.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
                            const snippet = (lines.find(l => l.toLowerCase().includes(term)) || term).slice(0, 140);
                            const errMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected (' + confirmedCount + '/' + COUNT + ' opened): ' + snippet;
                            setPhase('ERROR', errMsg);
                            log(errMsg, true, true);
                            return;
                          }
                        }

                        // 2. Verify genuine broker execution
                        const curTickets = collectTicketIds(curText);
                        let hasNewTicket = false;
                        curTickets.forEach(t => {
                          if (!baseTickets.has(t)) hasNewTicket = true;
                        });

                        const curPositions = countBrokerPositions();
                        const positionsIncreased = curPositions > basePositions;

                        const curMargin = readUsedMarginNumeric(curText);
                        const marginIncreased = baseMargin >= 0 && curMargin > baseMargin + 0.01;

                        const curDoneHits = countReceiptDoneHits(curText);
                        const hasNewReceiptPhrase =
                          (/\border\s+(?:has\s+been\s+)?(?:executed|placed|filled|opened)\b/i.test(curText) && !/\border\s+(?:has\s+been\s+)?(?:executed|placed|filled|opened)\b/i.test(baseText)) ||
                          (/\b(?:bought|sold)\s+[\d.]+\b/i.test(curText) && !/\b(?:bought|sold)\s+[\d.]+\b/i.test(baseText)) ||
                          (curDoneHits > baseDoneHits);

                        const curReceiptBtn = findReceiptConfirmButton();
                        const actionRemovedFromScreen = !finalBtn.isConnected || !isVisible(finalBtn);
                        const receiptDialogVisible = !!curReceiptBtn && (!hadReceiptBtnBefore || actionRemovedFromScreen);

                        if (hasNewTicket || positionsIncreased || marginIncreased || hasNewReceiptPhrase || receiptDialogVisible) {
                          confirmedCount++;
                          const summary = extractDealSummary(curText, baseTickets, currentTrade);
                          setPhase('TRADE_CONFIRMED:' + currentTrade + ':' + COUNT, summary);
                          log(summary, false, false);
                          dismissReceiptDialogIfPresent();

                          if (currentTrade >= COUNT) {
                            const finalMsg = COUNT > 1
                              ? 'All ' + COUNT + '/' + COUNT + ' trades confirmed · ' + summary
                              : summary;
                            setPhase('DONE', finalMsg);
                            log(finalMsg, false, true);
                          } else {
                            // Wait cleanly for receipt dismissal & dialog settle before starting Trade N+1
                            schedule(runNextTrade, 900);
                          }
                          return;
                        }

                        if (polls >= maxPolls) {
                          const timeoutMsg = 'Trade ' + currentTrade + '/' + COUNT + ' confirmation timed out (' + confirmedCount + '/' + COUNT + ' confirmed). Check terminal before retrying.';
                          setPhase('ERROR', timeoutMsg);
                          log(timeoutMsg, true, true);
                          return;
                        }

                        schedule(pollConfirmation, 300);
                      }

                      schedule(pollConfirmation, 400);
                    }, 180);
                  }

                  waitUntilReadyAndSubmitOnce();
                }

                runNextTrade();
              }

              function waitForOrderControlsThenRun(tries) {
                if (isCancelled()) return;
                const inputs = visibleInputs();
                const vol = findOrderFieldInput(inputs, ['volume', 'lot', 'size', 'qty'], ['stop loss', 'take profit', 'comment']);
                const btn = findOrderActionButton(DIRECTION, vol);
                if (vol && btn) {
                  setPhase('SYMBOL_SELECTED', 'Selected ' + activeSymbolName);
                  schedule(executeSequentialTrades, 250);
                  return;
                }
                if (tries === 0) {
                  const newOrderBtn = findStrictNewOrderButton();
                  if (newOrderBtn) {
                    singleClick(newOrderBtn);
                  }
                }
                if (tries < 12) {
                  schedule(function() { waitForOrderControlsThenRun(tries + 1); }, 300);
                } else {
                  setPhase('SYMBOL_SELECTED', 'Selected ' + activeSymbolName);
                  schedule(executeSequentialTrades, 200);
                }
              }

              function tryOpenAndPlace(matchObj) {
                if (isCancelled()) return;
                activeSymbolName = cleanSymbolLabel(matchObj.symbol) || cleanSymbolLabel(SYMBOL) || SYMBOL;
                // Show "Symbol found" in the exact same symbol row briefly, then replace in-place with "Selecting symbol"
                setPhase('SYMBOL_FOUND', 'Symbol found: ' + activeSymbolName);
                if (activeSymbolName.toLowerCase() !== SYMBOL.toLowerCase()) {
                  log('Matched broker symbol ' + activeSymbolName + ' for requested ' + SYMBOL, false, false);
                } else {
                  log('Found broker symbol ' + activeSymbolName, false, false);
                }

                schedule(function() {
                  if (isCancelled()) return;
                  setPhase('SYMBOL_SELECTING', 'Selecting symbol ' + activeSymbolName + '...');
                  if (matchObj.node) {
                    const added = clickAddButtonInsideRow(matchObj.node);
                    schedule(function() {
                      if (isCancelled()) return;
                      singleClick(matchObj.node);
                      const tag = (matchObj.node.tagName || '').toUpperCase();
                      if (tag !== 'BUTTON' && tag !== 'A' && tag !== 'OPTION') {
                        try {
                          matchObj.node.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, composed: true }));
                        } catch (_) {}
                      }
                      schedule(function() { waitForOrderControlsThenRun(0); }, 350);
                    }, added ? 250 : 50);
                  } else {
                    const selMatch = findSymbolSelectOption(true);
                    if (selMatch) {
                      setNativeValue(selMatch.sel, selMatch.opt.value);
                    }
                    schedule(function() { waitForOrderControlsThenRun(0); }, 350);
                  }
                }, 450);
              }

              function probeCandidatesSequentially(candidateIdx, maxCandidates) {
                if (isCancelled()) return;
                if (candidateIdx >= maxCandidates) {
                  const seen = sampleVisibleSymbols();
                  const hint = seen.length > 0
                    ? ' Visible on terminal: ' + seen.join(', ') + '.'
                    : ' Verify ' + BASE_SYMBOL + ' exists on this broker account.';
                  const failMsg = 'Symbol ' + SYMBOL + ' (or suffix variants of ' + BASE_SYMBOL + ') was not found on this broker account.' + hint;
                  setPhase('ERROR', failMsg);
                  log(failMsg, true, true);
                  return;
                }

                const cand = SUFFIX_CANDIDATES[candidateIdx];
                setPhase('SYMBOL_SEARCHING', 'Trying broker symbol variant ' + cand + '...');
                let input = findSearchInput();
                if (!input) {
                  const trig = findSearchOrAddTrigger();
                  if (trig) singleClick(trig);
                  input = findSearchInput();
                }

                if (input) {
                  setNativeValue(input, cand);
                  dispatchEnter(input);
                } else {
                  const dd = findCustomSymbolDropdown();
                  if (dd) singleClick(dd);
                }

                schedule(function() {
                  const hit = findBestSymbolNode(false) || findBestSymbolNode(true);
                  const selHit = findSymbolSelectOption(true);
                  if (hit) {
                    tryOpenAndPlace(hit);
                    return;
                  }
                  if (selHit) {
                    tryOpenAndPlace({ node: null, symbol: selHit.symbol });
                    return;
                  }
                  probeCandidatesSequentially(candidateIdx + 1, maxCandidates);
                }, 260);
              }

              function startSymbolSearch() {
                if (isCancelled()) return;
                setPhase('SYMBOL_SEARCHING', 'Searching ' + SYMBOL + ' across broker symbols...');
                log('Searching symbol ' + SYMBOL + ' (base: ' + BASE_SYMBOL + ')...', false, false);

                // Fast check if an existing order form is already open with exact/variant symbol
                const inputs = visibleInputs();
                const alreadyVol = findOrderFieldInput(inputs, ['volume', 'lot', 'size', 'qty'], ['stop loss', 'take profit', 'comment']);
                const alreadyOrderBtn = alreadyVol ? findOrderActionButton(DIRECTION, alreadyVol) : null;
                const immediateExact = findBestSymbolNode(false) || findBestSymbolNode(true);
                const immediateSelect = findSymbolSelectOption(true);
                if (alreadyOrderBtn && (immediateExact || immediateSelect)) {
                  const matchedSym = immediateExact ? immediateExact.symbol : immediateSelect.symbol;
                  activeSymbolName = cleanSymbolLabel(matchedSym) || SYMBOL;
                  setPhase('SYMBOL_FOUND', 'Symbol found: ' + activeSymbolName);
                  schedule(function() {
                    setPhase('SYMBOL_SELECTING', 'Selecting ' + activeSymbolName + '...');
                    if (immediateSelect) {
                      setNativeValue(immediateSelect.sel, immediateSelect.opt.value);
                    }
                    schedule(function() {
                      setPhase('SYMBOL_SELECTED', 'Selected ' + activeSymbolName);
                      schedule(executeSequentialTrades, 200);
                    }, 300);
                  }, 420);
                  return;
                }

                // Switch to Quotes / Market Watch / Symbols search if needed so hidden symbols outside Market Watch can be found
                const quotesTab = findButton(['quotes', 'market watch', 'symbols']);
                if (quotesTab) singleClick(quotesTab);

                let searchInput = findSearchInput();
                if (!searchInput) {
                  const addSymTrigger = findSearchOrAddTrigger();
                  if (addSymTrigger) singleClick(addSymTrigger);
                  searchInput = findSearchInput();
                }

                if (searchInput) {
                  setNativeValue(searchInput, SEARCH_QUERY);
                  dispatchEnter(searchInput);
                } else if (immediateExact) {
                  tryOpenAndPlace(immediateExact);
                  return;
                } else if (immediateSelect) {
                  tryOpenAndPlace({ node: null, symbol: immediateSelect.symbol });
                  return;
                }

                schedule(function() {
                  const exactOrVariant = findBestSymbolNode(false) || findBestSymbolNode(true);
                  const selectMatch = findSymbolSelectOption(true);

                  if (exactOrVariant) {
                    tryOpenAndPlace(exactOrVariant);
                    return;
                  }
                  if (selectMatch) {
                    tryOpenAndPlace({ node: null, symbol: selectMatch.symbol });
                    return;
                  }

                  const newOrderOrAdd = findStrictNewOrderButton() || findButton(['add symbol', 'symbols']);
                  if (newOrderOrAdd) {
                    log('Opening Symbol / Order catalog for ' + BASE_SYMBOL + '...', false, false);
                    singleClick(newOrderOrAdd);
                  }

                  schedule(function() {
                    const modalSearch = findSearchInput();
                    if (modalSearch && modalSearch !== searchInput) {
                      setNativeValue(modalSearch, SEARCH_QUERY);
                      dispatchEnter(modalSearch);
                    } else {
                      const dropdownTrigger = findCustomSymbolDropdown();
                      if (dropdownTrigger) singleClick(dropdownTrigger);
                    }

                    schedule(function() {
                      const retryMatch = findBestSymbolNode(false) || findBestSymbolNode(true);
                      const retrySelect = findSymbolSelectOption(true);
                      if (retryMatch) {
                        tryOpenAndPlace(retryMatch);
                        return;
                      }
                      if (retrySelect) {
                        tryOpenAndPlace({ node: null, symbol: retrySelect.symbol });
                        return;
                      }
                      probeCandidatesSequentially(0, SUFFIX_CANDIDATES.length);
                    }, 550);
                  }, 550);
                }, 650);
              }

              let authPolls = 0;
              function checkAuthAndStart() {
                if (isCancelled()) return;
                const rawText = collectDeepText();
                const bodyLower = rawText.toLowerCase();
                const hasPwd = queryDeepAll('input[type="password"]').filter(isVisible).length > 0;
                const hasAccountsPicker = bodyLower.includes('trading accounts');
                const isCloudflare =
                  (bodyLower.includes('just a moment') ||
                    bodyLower.includes('verify you are human') ||
                    bodyLower.includes('checking your browser') ||
                    document.title.toLowerCase().includes('just a moment')) && !hasPwd;
                const workspaceActive = hasActiveTradingWorkspace(bodyLower);
                const hasSpinner = queryDeepAll('[role="progressbar"], [class*="spinner"], [class*="loader"], [class*="loading"]').some(isVisible);

                if (hasPwd || (hasAccountsPicker && !workspaceActive)) {
                  setPhase('AUTH', 'Logging in to Web Terminal...');
                  if (window.BtechBridge && typeof window.BtechBridge.onTradeRequiresAuth === 'function') {
                    window.BtechBridge.onTradeRequiresAuth();
                  } else {
                    const msg = 'Web terminal session is showing a login form. Open Web Terminal to log in first.';
                    setPhase('ERROR', msg);
                    log(msg, true, true);
                  }
                  return;
                }

                if (isCloudflare) {
                  if (authPolls < 12) {
                    authPolls++;
                    setPhase('AUTH', 'Waiting for security check...');
                    schedule(checkAuthAndStart, 500);
                    return;
                  }
                  const cfMsg = 'Broker terminal showed a security challenge. Open Web Terminal to complete check.';
                  setPhase('ERROR', cfMsg);
                  log(cfMsg, true, true);
                  return;
                }

                if (!workspaceActive) {
                  if (authPolls < 16) {
                    authPolls++;
                    setPhase('AUTH', 'Confirming active session inside terminal...');
                    schedule(checkAuthAndStart, 350);
                    return;
                  }
                  if (window.BtechBridge && typeof window.BtechBridge.onTradeRequiresAuth === 'function') {
                    window.BtechBridge.onTradeRequiresAuth();
                  } else {
                    const msg = 'Trading workspace did not load (' + (hasSpinner ? 'still loading' : 'not logged in') + '). Open Web Terminal to verify.';
                    setPhase('ERROR', msg);
                    log(msg, true, true);
                  }
                  return;
                }

                setPhase('AUTH_OK', 'Account authenticated inside Web Terminal');
                log('Confirmed inside Web Terminal workspace', false, false);
                schedule(startSymbolSearch, 250);
              }

              try {
                checkAuthAndStart();
              } catch (err) {
                const errMsg = 'Trade automation error: ' + (err && err.message ? err.message : err);
                setPhase('ERROR', errMsg);
                log(errMsg, true, true);
              }
            })();
        """.trimIndent()
    }

    /**
     * Extracts real-time Balance, Equity, Free Margin / Margin, and Floating Profit from the
     * connected MT4/MT5 Web Terminal DOM, Shadow DOM, and same-origin iframes.
     */
    fun buildBalanceTelemetryScript(): String {
        return """
            (function() {
              $DEEP_DOM_HELPERS

              function emit(payload) {
                if (window.BtechBridge && typeof window.BtechBridge.onBalanceUpdate === 'function') {
                  window.BtechBridge.onBalanceUpdate(JSON.stringify(payload));
                }
              }

              try {
                const rawText = collectDeepText();
                const lower = rawText.toLowerCase();
                const workspaceActive = hasActiveTradingWorkspace(lower);
                const visiblePwd = queryDeepAll('input[type="password"]').filter(isVisible).length > 0;

                if (!workspaceActive && visiblePwd) {
                  emit({
                    connected: false,
                    balance: '',
                    equity: '',
                    profit: '',
                    margin: '',
                    status: 'Login required on Web Terminal'
                  });
                  return;
                }

                function pickMetric(patterns) {
                  for (const re of patterns) {
                    const m = rawText.match(re);
                    if (m && m[1]) {
                      return m[1].replace(/\s+/g, ' ').trim();
                    }
                  }
                  return '';
                }

                const balance = pickMetric([
                  /\bbalance\b[\s:]*([+\-$€£¥]{0,3}\s*-?\d[\d\s,]{0,12}\.\d{2}(?:\s+[A-Z]{3}(?![a-z]))?)/i,
                  /\bbalance\b[^\d\-+]{1,18}(-?\d[\d\s,]{0,12}\.\d{2})/i
                ]);
                const equity = pickMetric([
                  /\bequity\b[\s:]*([+\-$€£¥]{0,3}\s*-?\d[\d\s,]{0,12}\.\d{2}(?:\s+[A-Z]{3}(?![a-z]))?)/i,
                  /\bequity\b[^\d\-+]{1,18}(-?\d[\d\s,]{0,12}\.\d{2})/i
                ]);
                const margin = pickMetric([
                  /\bfree\s*margin\b[\s:]*([+\-$€£¥]{0,3}\s*-?\d[\d\s,]{0,12}\.\d{2}(?:\s+[A-Z]{3}(?![a-z]))?)/i,
                  /\bmargin\b[\s:]*([+\-$€£¥]{0,3}\s*-?\d[\d\s,]{0,12}\.\d{2}(?:\s+[A-Z]{3}(?![a-z]))?)/i
                ]);
                const profit = pickMetric([
                  /\bfloating\s*p\/?l\b[\s:]*([+\-$€£¥]{0,2}\s*[+-]?\d[\d\s,]{0,9}\.\d{2}(?:\s+[A-Z]{3}(?![a-z]))?)/i,
                  /(?:^|\n)[^\n]*\bprofit\b[\s:]*([+\-$€£¥]{0,2}\s*[+-]?\d[\d\s,]{0,9}\.\d{2}(?:\s+[A-Z]{3}(?![a-z]))?)/i
                ]);

                if (balance || equity) {
                  emit({
                    connected: true,
                    balance: balance || '—',
                    equity: equity || '—',
                    profit: profit || '—',
                    margin: margin || '—',
                    status: 'Live Web Terminal telemetry'
                  });
                } else {
                  emit({
                    connected: workspaceActive,
                    balance: '',
                    equity: '',
                    profit: '',
                    margin: '',
                    status: workspaceActive ? 'Workspace open — waiting for balance readout' : 'Open or authenticate Web Terminal'
                  });
                }
              } catch (err) {
                emit({
                  connected: false,
                  balance: '',
                  equity: '',
                  profit: '',
                  margin: '',
                  status: 'Telemetry error: ' + (err && err.message ? err.message : err)
                });
              }
            })();
        """.trimIndent()
    }
}
