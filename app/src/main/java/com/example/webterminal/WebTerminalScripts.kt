package com.example.webterminal

import org.json.JSONObject

object WebTerminalScripts {

    private const val DEEP_DOM_HELPERS = """
        function getViewFor(el) {
          try {
            return (el && el.ownerDocument && el.ownerDocument.defaultView) || window;
          } catch (_) {
            return window;
          }
        }

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

        function queryDeepAll(selector, scopeRoot) {
          const roots = [];
          collectRoots(scopeRoot || document, roots);
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

        function isRenderedVisible(el) {
          if (!el || el.hidden) return false;
          try {
            const rect = el.getBoundingClientRect();
            if (!rect || rect.width <= 1 || rect.height <= 1) return false;
            const view = getViewFor(el);
            if (typeof el.checkVisibility === 'function') {
              if (!el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) return false;
              const ownStyle = view.getComputedStyle(el);
              if (ownStyle && parseFloat(ownStyle.opacity || '1') <= 0.05) return false;
              return true;
            }
            let cur = el;
            while (cur) {
              if (cur.nodeType === 1) {
                if (cur.hidden) return false;
                const curView = getViewFor(cur);
                const st = curView.getComputedStyle(cur);
                if (!st || st.display === 'none' || st.visibility === 'hidden' || st.visibility === 'collapse') return false;
                if (parseFloat(st.opacity || '1') <= 0.05) return false;
              }
              if (cur.parentNode) {
                cur = cur.parentNode;
              } else if (cur.host) {
                cur = cur.host;
              } else {
                break;
              }
            }
            return true;
          } catch (_) {
            return true;
          }
        }

        function isVisible(el) {
          if (!isRenderedVisible(el)) return false;
          try {
            const view = getViewFor(el);
            const st = view.getComputedStyle(el);
            if (st && st.pointerEvents === 'none') return false;
            return !el.disabled;
          } catch (_) {
            return true;
          }
        }

        function hasActiveTradingWorkspace(lowerText) {
          if (
            lowerText.includes('connection closed or not established') ||
            (lowerText.includes('trading accounts') && lowerText.includes('connect to account') && !lowerText.includes('market watch'))
          ) {
            return false;
          }

          const hasNumericAccountMetrics =
            /\b(?:balance|equity|free\s*margin)\b[\s:]*[+\-$€£¥a-z]{0,4}\s*-?\d[\d\s,]*[.,]\d{2}/i.test(lowerText);
          if (hasNumericAccountMetrics) return true;

          const hasClassicMt =
            (lowerText.includes('market watch') && (lowerText.includes('new order') || /\b\d{1,6}\.\d{2,5}\b/.test(lowerText))) ||
            queryDeepAll('[data-testid*="trade"], [class*="trade-panel"], [class*="positions"], [class*="market-watch"]').filter(isVisible).length > 0;
          if (hasClassicMt) return true;

          const hasMt5OrderTicket =
            (lowerText.includes('instant execution') || lowerText.includes('market execution')) &&
            lowerText.includes('volume') &&
            lowerText.includes('sell') &&
            lowerText.includes('buy');
          if (hasMt5OrderTicket) return true;

          const hasMt5SearchBox = queryDeepAll("input[placeholder='Search symbol'], input[placeholder*='Search symbol' i]").filter(isVisible).length > 0;
          const hasLiveQuotes = /\b\d{1,6}\.\d{2,5}\b/.test(lowerText);
          if (hasMt5SearchBox && (hasLiveQuotes || lowerText.includes('positions') || lowerText.includes('no symbols found'))) {
            return true;
          }

          return false;
        }

        function hasVerifiedAccountSession(rawText, lowerText, platform) {
          if (!hasActiveTradingWorkspace(lowerText)) return false;

          const hasLiveBalance =
            /\b(?:balance|equity|free\s*margin)\b[\s:]*[+\-$€£¥A-Z]{0,4}\s*-?\d[\d\s,]*[.,]\d{2}/i.test(rawText) ||
            /\b\d[\d\s,]*\.\d{2}\s*(?:USD|EUR|GBP|ZAR|JPY|AUD|CAD|CHF|USC)\b/i.test(rawText);
          if (hasLiveBalance) return true;

          const hasLiveQuotePrices = /\b\d{1,6}\.\d{2,5}\b/.test(rawText);
          const upperPlat = String(platform || '').trim().toUpperCase();
          if (upperPlat === 'MT4') {
            return lowerText.includes('market watch') && (hasLiveQuotePrices || lowerText.includes('new order'));
          }

          const hasMt5SearchOrForm =
            queryDeepAll("input[placeholder='Search symbol'], input[placeholder*='Search symbol' i], .trade-form, .trade-input").filter(isVisible).length > 0;
          return hasMt5SearchOrForm && (hasLiveQuotePrices || lowerText.includes('positions') || lowerText.includes('market watch') || lowerText.includes('no symbols found'));
        }

        function detectLoadedTerminalPlatform() {
          const hrefLower = String((window.location && window.location.href) || '').toLowerCase();
          if (hrefLower.includes('metatraderweb.app') || /[?&]version=4\b/i.test(hrefLower)) {
            return 'MT4';
          }
          if (hrefLower.includes('live.justmarkets.com') || hrefLower.includes('trade.metatrader5.com')) {
            return 'MT5';
          }

          const rawLower = collectDeepText().toLowerCase();
          const hasMwPanel = queryDeepAll('body > div.page-window.market-watch, .page-window.market-watch, .market-watch-panel').filter(isVisible).length > 0 ||
            rawLower.includes('market watch');
          const hasClassicMt4Fields = queryDeepAll('#volume').filter(isVisible).length > 0 &&
            queryDeepAll('#sl, #tp').filter(isVisible).length > 0;

          const hasMt5TradeForm = queryDeepAll('.trade-input, .trade-form').filter(isRenderedVisible).length > 0 ||
            queryDeepAll('[title*="Show Trade Form (F9)"]').filter(isVisible).length > 0 ||
            rawLower.includes('connection closed or not established') ||
            (rawLower.includes('trading accounts') && rawLower.includes('connect to account'));
          const hasMt5Search = queryDeepAll("input[placeholder='Search symbol'], input[placeholder*='Search symbol' i]").filter(isVisible).length > 0;

          if (hasMt5TradeForm || (hasMt5Search && !hasMwPanel && !hasClassicMt4Fields)) {
            return 'MT5';
          }
          if (hasMwPanel || hasClassicMt4Fields) {
            return 'MT4';
          }
          return '';
        }

        function detectPlatformMismatchError(expectedPlatform) {
          const expected = String(expectedPlatform || '').trim().toUpperCase();
          if (!expected) return '';
          const detected = detectLoadedTerminalPlatform();
          if (detected && detected !== expected) {
            const url = (window.location && window.location.href) || '';
            return 'Platform mismatch: BTECH account is configured for ' + expected + ', but loaded web terminal is ' + detected + (url ? ' (' + url + ')' : '') + '. Switch account platform to ' + detected + ' or open the matching terminal URL.';
          }
          return '';
        }

        function hasMt5ReconnectScreen(lowerText, hasPasswordInput) {
          if (hasPasswordInput) return false;
          const l = String(lowerText || '');
          return l.includes('connection closed or not established') ||
            (l.includes('trading accounts') && l.includes('connect to account'));
        }

        function findConnectToAccountButton(targetLogin) {
          const norm = function(v) { return String(v || '').replace(/\s+/g, ' ').trim().toLowerCase(); };
          const loginStr = String(targetLogin || '').trim();

          // If multiple saved accounts exist and targetLogin isn't the currently selected detail card, click its row first
          if (loginStr) {
            const detailBtns = queryDeepAll('button, [role="button"]').filter(isVisible);
            const removeBtn = detailBtns.find(function(b) { return norm(b.innerText || b.textContent) === 'remove'; });
            let cardText = '';
            if (removeBtn && removeBtn.parentElement) {
              let cur = removeBtn.parentElement;
              for (let d = 0; d < 5 && cur && cur !== document.body; d++) {
                const t = norm(cur.innerText || cur.textContent);
                if (t.includes('connect to account') && t.includes('remove')) {
                  cardText = t;
                  break;
                }
                cur = cur.parentElement;
              }
            }
            if (!cardText.includes(loginStr.toLowerCase())) {
              const rowNode = queryDeepAll('div, li, tr, [role="row"], [role="button"], button').filter(isVisible).find(function(el) {
                const t = norm(el.innerText || el.textContent);
                return t.includes(loginStr.toLowerCase()) && t.length <= 160 && !t.includes('remove');
              });
              if (rowNode) singleClick(rowNode);
            }
          }

          // Strictly match real <button> / [role="button"] (never <a> sidebar links and never "Remove")
          const buttons = queryDeepAll('button, [role="button"], input[type="button"], input[type="submit"]').filter(isVisible);
          const connectCandidates = buttons.filter(function(el) {
            if (el.tagName === 'A') return false;
            if (el.closest && el.closest('nav, aside')) return false;
            const t = norm(el.innerText || el.value || el.textContent || el.getAttribute('aria-label') || '');
            if (!t || t === 'remove' || t.includes('remove')) return false;
            return t === 'connect to account' || t === 'connect';
          });
          if (!connectCandidates.length) return null;

          // Prefer the Connect button paired with a "Remove" button inside the account detail pane
          const pairedWithRemove = connectCandidates.find(function(btn) {
            let cur = btn.parentElement;
            for (let d = 0; d < 5 && cur && cur !== document.body; d++) {
              const sibBtns = cur.querySelectorAll ? Array.from(cur.querySelectorAll('button, [role="button"]')) : [];
              const hasRemoveSibling = sibBtns.some(function(s) {
                return s !== btn && norm(s.innerText || s.textContent) === 'remove';
              });
              if (hasRemoveSibling) return true;
              cur = cur.parentElement;
            }
            return false;
          });
          if (pairedWithRemove) return pairedWithRemove;

          return connectCandidates.find(function(b) { return b.tagName === 'BUTTON'; }) || connectCandidates[0];
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
          const view = getViewFor(el);
          const proto = el.tagName === 'SELECT'
            ? view.HTMLSelectElement.prototype
            : (el.tagName === 'TEXTAREA' ? view.HTMLTextAreaElement.prototype : view.HTMLInputElement.prototype);
          const desc = Object.getOwnPropertyDescriptor(proto, 'value');
          if (desc && desc.set) {
            desc.set.call(el, value);
          } else {
            el.value = value;
          }
          try {
            if (typeof view.InputEvent === 'function' && el.tagName === 'INPUT') {
              el.dispatchEvent(new view.InputEvent('input', {
                bubbles: true,
                composed: true,
                data: String(value || ''),
                inputType: value ? 'insertText' : 'deleteContentBackward'
              }));
            } else {
              el.dispatchEvent(new view.Event('input', { bubbles: true, composed: true }));
            }
          } catch (_) {
            el.dispatchEvent(new view.Event('input', { bubbles: true, composed: true }));
          }
          el.dispatchEvent(new view.Event('change', { bubbles: true, composed: true }));
          try {
            el.dispatchEvent(new view.KeyboardEvent('keyup', { bubbles: true, composed: true }));
          } catch (_) {}
          return true;
        }

        function singleClick(el) {
          if (!el) return false;
          try { if (typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (_) {}
          try { if (typeof el.focus === 'function') el.focus(); } catch (_) {}
          const view = getViewFor(el);
          const rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
          const cx = rect ? (rect.left + rect.width / 2) : 0;
          const cy = rect ? (rect.top + rect.height / 2) : 0;
          try {
            el.dispatchEvent(new view.MouseEvent('mousedown', {
              bubbles: true,
              cancelable: true,
              composed: true,
              view: view,
              button: 0,
              buttons: 1,
              clientX: cx,
              clientY: cy
            }));
            el.dispatchEvent(new view.MouseEvent('mouseup', {
              bubbles: true,
              cancelable: true,
              composed: true,
              view: view,
              button: 0,
              buttons: 0,
              clientX: cx,
              clientY: cy
            }));
          } catch (_) {}
          try {
            if (typeof el.click === 'function') {
              el.click();
            } else {
              el.dispatchEvent(new view.MouseEvent('click', {
                bubbles: true,
                cancelable: true,
                composed: true,
                view: view,
                button: 0,
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

    private const val ROBOTRADER_DISCOVERY_HELPERS = """
        const ALLOWED_SUFFIXES = [
          '.m', '.s', '.c', '.ecn', '.pro',
          '.raw', '.r', '.micro', '.std',
          '.cent', '.i', '_i', '#'
        ];

        function normalise(value) {
          return String(value || '').trim().toUpperCase();
        }

        function getRoots() {
          const roots = [];
          collectRoots(document, roots);
          return roots;
        }

        function deepAll(selector, scopeRoot) {
          return queryDeepAll(selector, scopeRoot);
        }

        function getElementText(element) {
          if (!element) return '';
          return [
            element.getAttribute && element.getAttribute('data-symbol'),
            element.getAttribute && element.getAttribute('data-name'),
            element.getAttribute && element.getAttribute('aria-label'),
            element.getAttribute && element.getAttribute('title'),
            element.tagName === 'SELECT' && element.selectedOptions && element.selectedOptions[0]
              ? (element.selectedOptions[0].value || element.selectedOptions[0].textContent)
              : null,
            element.value,
            element.textContent
          ]
            .filter(Boolean)
            .join(' ')
            .trim();
        }

        function extractSymbolTokens(value) {
          return normalise(value).match(/[A-Z0-9]+(?:[._#-][A-Z0-9]+)*/g) || [];
        }

        function extractPreservedCaseTokens(value) {
          return String(value || '').trim().match(/[A-Za-z0-9]+(?:[._#-][A-Za-z0-9]+)*/g) || [];
        }

        function stripKnownSuffix(symbol) {
          const value = normalise(symbol);
          for (let i = 0; i < ALLOWED_SUFFIXES.length; i++) {
            const suffix = ALLOWED_SUFFIXES[i].toUpperCase();
            if (value.endsWith(suffix) && value.length > suffix.length + 2) {
              return value.slice(0, -suffix.length);
            }
          }
          if (value.length >= 7 && /[MC]$/.test(value)) {
            return value.slice(0, -1);
          }
          return value;
        }

        function matchRank(actual, requested, verifiedAliases) {
          const aRaw = String(actual || '').trim();
          const rRaw = String(requested || '').trim();
          if (!aRaw || !rRaw) return 0;

          if (aRaw === rRaw) return 4;

          const a = normalise(aRaw);
          const r = normalise(rRaw);
          if (a === r) return 3;

          if (verifiedAliases && typeof verifiedAliases === 'object') {
            const keys = Object.keys(verifiedAliases);
            for (let i = 0; i < keys.length; i++) {
              if (normalise(keys[i]) === r && normalise(verifiedAliases[keys[i]]) === a) {
                return 2;
              }
            }
          }

          return 0;
        }

        function matchTypeForRank(rank) {
          if (rank === 4) return 'EXACT';
          if (rank === 3) return 'CASE_INSENSITIVE';
          if (rank === 2) return 'VERIFIED_ALIAS';
          return 'NOT_FOUND';
        }

        function looksLikeBrokerInstrumentToken(clean) {
          const v = String(clean || '').trim();
          if (!v || v.length < 3 || v.length > 18) return false;
          if (/^\d+(?:\.\d+)?$/.test(v)) return false;
          if (!/^[A-Za-z#][A-Za-z0-9._#-]{1,17}$/.test(v)) return false;
          if (!/[A-Za-z]{2,}/.test(v)) return false;
          return /^(?:[A-Z]{6}(?:[._#-][A-Za-z0-9]+|[mc])?|XAU|XAG|GOLD|SILVER|BTC|ETH|LTC|XRP|SOL|US30|US500|NAS100|USTEC|DE30|DE40|GER40|UK100|SPX500|JP225|USOIL|UKOIL|XTIUSD|XBRUSD|XNGUSD)/i.test(v) ||
            (/[._#][A-Za-z0-9]{1,6}$/.test(v) && /^[A-Z0-9]{2,10}/.test(v)) ||
            (/^#[A-Za-z0-9._-]{2,14}$/.test(v)) ||
            (/^[A-Z]{2,6}\d{2,4}$/.test(v));
        }

        function isCategoryFolderRowText(rawText) {
          const text = String(rawText || '').replace(/\s+/g, ' ').trim();
          if (!text) return false;
          // Screenshot 5/6 folder counters: "Forex S 10/29", "Metals S 1/9", "OIL S 0/3", "Indices S 0/12", "Crypto 1/8"
          if (/\b\d+\s*\/\s*\d+\b/.test(text) && text.length <= 64) return true;
          if (/^(?:forex|metals|oil|indices|crypto|stocks|shares|commodities|energies|etfs|favorites|non-tradable)(?:\s+[a-z0-9._-]+)?$/i.test(text)) {
            return true;
          }
          return false;
        }

        function findSymbolElement(requestedSymbol, scopeRoot, verifiedAliases) {
          const selectors = [
            "[data-symbol]",
            "[data-name]",
            "[role='option']",
            "[role='row']",
            "tr",
            "td",
            "li",
            "button",
            "[class*='symbol']",
            "[class*='Symbol']"
          ];

          const elements = deepAll(selectors.join(','), scopeRoot);
          const matches = [];

          elements.forEach(function(element) {
            if (!isRenderedVisible(element)) return;

            const rawText = getElementText(element);
            if (!rawText) return;
            const hasExplicitAttr = !!(
              (element.getAttribute && element.getAttribute('data-symbol')) ||
              (element.getAttribute && element.getAttribute('data-name'))
            );
            if (!hasExplicitAttr && isCategoryFolderRowText(rawText)) return;
            if (!hasExplicitAttr && (element.textContent || '').length > 180) return;

            const rawTokens = extractPreservedCaseTokens(rawText);
            let bestRank = 0;
            let actualSymbol = null;

            rawTokens.forEach(function(token) {
              const rank = matchRank(token, requestedSymbol, verifiedAliases);
              if (rank > bestRank) {
                bestRank = rank;
                actualSymbol = token;
              }
            });

            if (bestRank > 0) {
              matches.push({
                element: element,
                actualSymbol: actualSymbol,
                matchRank: bestRank,
                matchType: matchTypeForRank(bestRank),
                textLength: (element.textContent || '').length
              });
            }
          });

          matches.sort(function(a, b) {
            if (b.matchRank !== a.matchRank) {
              return b.matchRank - a.matchRank;
            }
            return a.textLength - b.textLength;
          });

          return matches.length ? matches[0] : null;
        }

        function getClickableElement(element) {
          if (!element) return null;
          if (!element.closest) return element;
          return element.closest(
            "button, a, [role='button'], [role='option'], " +
            "[role='row'], tr, li, [data-symbol], " +
            "[class*='row'], [class*='item']"
          ) || element;
        }

        function clickElement(element) {
          if (!element || !isVisible(element)) {
            return false;
          }
          try {
            return singleClick(getClickableElement(element));
          } catch (_) {
            return false;
          }
        }

        function setNativeInputValue(input, value) {
          return setNativeValue(input, value);
        }

        function findExactVisibleText(text, selectors, scopeRoot) {
          const wanted = String(text || '').trim().toLowerCase();
          const elements = deepAll(selectors, scopeRoot);
          const matches = elements.filter(function(element) {
            if (!isRenderedVisible(element)) return false;
            const clean = (element.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
            return clean === wanted;
          });
          if (!matches.length) return null;
          const matchSet = new Set(matches);
          const leafmost = matches.find(function(candidate) {
            for (let i = 0; i < matches.length; i++) {
              const other = matches[i];
              if (other === candidate) continue;
              let cur = other.parentElement;
              while (cur) {
                if (cur === candidate) return false;
                cur = cur.parentElement;
              }
            }
            return matchSet.has(candidate);
          });
          return leafmost || matches[0];
        }

        function findMt5SearchRowContainer(rowEl, actualSymbol) {
          if (!rowEl) return null;
          let cur = getClickableElement(rowEl) || rowEl;
          for (let depth = 0; depth < 4 && cur && cur.parentElement && cur.parentElement !== document.body && cur.parentElement !== document.documentElement; depth++) {
            const p = cur.parentElement;
            const pTxt = String(p.textContent || '').replace(/\s+/g, ' ').trim();
            if (pTxt.length > 220) break;
            const rowCount = p.querySelectorAll ? p.querySelectorAll("[data-symbol], [data-name], [role='row'], tr, li").length : 0;
            if (rowCount > 1) break;
            const hasControlHere = cur.querySelectorAll && cur.querySelectorAll("button, [role='button'], svg").length > 0;
            if (hasControlHere && cur !== rowEl) break;
            cur = p;
          }
          return cur;
        }

        function isGreenSvgNode(node) {
          if (!node || !node.tagName) return false;
          try {
            const tag = String(node.tagName || '').toLowerCase();
            if (tag !== 'svg' && tag !== 'path' && tag !== 'polyline' && tag !== 'circle') return false;
            const view = getViewFor(node);
            const st = view.getComputedStyle(node);
            const colors = [st && st.color, st && st.fill, st && st.stroke, node.getAttribute && node.getAttribute('stroke'), node.getAttribute && node.getAttribute('fill')];
            for (let i = 0; i < colors.length; i++) {
              const c = String(colors[i] || '').trim().toLowerCase();
              if (!c || c === 'none' || c === 'transparent') continue;
              const m = c.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/);
              if (m) {
                const r = parseInt(m[1], 10), g = parseInt(m[2], 10), b = parseInt(m[3], 10);
                if (g >= 110 && g > r + 25 && g > b + 15) return true;
              }
              if (/^#[0-9a-f]{6}$/i.test(c)) {
                const r = parseInt(c.slice(1, 3), 16), g = parseInt(c.slice(3, 5), 16), b = parseInt(c.slice(5, 7), 16);
                if (g >= 110 && g > r + 25 && g > b + 15) return true;
              }
            }
          } catch (_) {}
          return false;
        }

        function detectSvgShapeKind(svgEl) {
          if (!svgEl || !svgEl.querySelectorAll) return '';
          try {
            const uses = Array.from(svgEl.querySelectorAll('use'));
            for (let i = 0; i < uses.length; i++) {
              const ref = ((uses[i].getAttribute('href') || '') + ' ' + (uses[i].getAttribute('xlink:href') || '')).toLowerCase();
              if (ref.includes('check') || ref.includes('tick') || ref.includes('done') || ref.includes('added')) return 'CHECK';
              if (ref.includes('plus') || ref.includes('add')) return 'PLUS';
            }
            const lines = svgEl.querySelectorAll('line').length;
            const rects = svgEl.querySelectorAll('rect').length;
            if (lines === 2 || rects === 2) return 'PLUS';
            const polylines = svgEl.querySelectorAll('polyline').length;
            const paths = Array.from(svgEl.querySelectorAll('path'));
            if (polylines > 0 && paths.length === 0 && lines === 0) return 'CHECK';
            if (paths.length === 1) {
              const d = String(paths[0].getAttribute('d') || '');
              const mCount = (d.match(/[Mm]/g) || []).length;
              const hasHorizAndVert = (/[Hh]/.test(d) && /[Vv]/.test(d)) || mCount >= 2;
              if (hasHorizAndVert && !/[CcQqAa]/.test(d)) return 'PLUS';
            } else if (paths.length === 2) {
              const d0 = String(paths[0].getAttribute('d') || '');
              const d1 = String(paths[1].getAttribute('d') || '');
              if ((/[HhVv]/.test(d0) || /[HhVv]/.test(d1)) && !/[CcQqAa]/.test(d0 + d1)) return 'PLUS';
            }
          } catch (_) {}
          return '';
        }

        // Inspects a matched MT5 search result row to distinguish:
        // - '+' (unadded -> click add button once)
        // - 'check' / already added (skip '+' click!)
        // - fallbackControl: small trailing icon button whose SVG couldn't be classified unambiguously
        // - neither (minimal/inline search result row)
        function inspectMt5SearchResultAction(rowEl) {
          if (!rowEl) return { state: 'NONE', control: null, fallbackControl: null };
          const container = findMt5SearchRowContainer(rowEl) || getClickableElement(rowEl) || rowEl;
          const nodes = [];
          if (container.querySelectorAll) {
            Array.from(container.querySelectorAll("button, [role='button'], span, div, svg, i, path, use")).forEach(function(n) {
              if (isVisible(n)) nodes.push(n);
            });
          }
          if (container.parentElement && container.parentElement.children) {
            const sibs = Array.from(container.parentElement.children);
            if (sibs.length <= 4) {
              sibs.forEach(function(s) {
                if (s !== container && isVisible(s)) nodes.push(s);
              });
            }
          }

          let checkNode = null;
          let plusNode = null;
          let fallbackIconBtn = null;

          for (let i = 0; i < nodes.length; i++) {
            const n = nodes[i];
            const txt = String(n.innerText || n.textContent || '').replace(/\s+/g, ' ').trim();
            const meta = (
              (n.getAttribute && n.getAttribute('aria-label') || '') + ' ' +
              (n.getAttribute && n.getAttribute('title') || '') + ' ' +
              (n.getAttribute && n.getAttribute('data-testid') || '') + ' ' +
              (n.getAttribute && n.getAttribute('data-icon') || '') + ' ' +
              (n.getAttribute && n.getAttribute('data-added') || '') + ' ' +
              (n.getAttribute && n.getAttribute('href') || '') + ' ' +
              (n.getAttribute && n.getAttribute('xlink:href') || '') + ' ' +
              (typeof n.className === 'string' ? n.className : '')
            ).toLowerCase();

            const svgKind = (n.tagName && n.tagName.toLowerCase() === 'svg') ? detectSvgShapeKind(n) : '';

            const isCheck =
              txt === '✓' || txt === '✔' || txt.toLowerCase() === 'added' ||
              meta.includes('added') || meta.includes('check') || meta.includes('subscribed') || meta.includes('tick') ||
              (n.getAttribute && n.getAttribute('data-added') === 'true') ||
              svgKind === 'CHECK' ||
              isGreenSvgNode(n);
            if (isCheck) {
              checkNode = n;
              continue;
            }

            const isPlus =
              txt === '+' || txt === '＋' ||
              meta.includes('plus') ||
              /\badd\b/.test(meta) ||
              meta.includes('add-symbol') ||
              svgKind === 'PLUS';
            if (isPlus) {
              plusNode = (n.closest && (n.closest('button') || n.closest("[role='button']"))) || n;
              continue;
            }

            const isSmallButton =
              (n.tagName === 'BUTTON' || (n.getAttribute && n.getAttribute('role') === 'button')) &&
              !n.disabled &&
              n !== container &&
              (!txt || txt.length <= 2) &&
              !/\b(?:remove|delete|hide|close|clear|star|fav|chart|info|spec|minus|check|tick)\b/.test(meta);
            if (isSmallButton && !fallbackIconBtn) {
              try {
                const r = n.getBoundingClientRect ? n.getBoundingClientRect() : null;
                if (!r || (r.width <= 56 && r.height <= 56)) {
                  fallbackIconBtn = n;
                }
              } catch (_) {
                fallbackIconBtn = n;
              }
            }
          }

          if (checkNode) return { state: 'CHECK', control: checkNode, fallbackControl: null };
          if (plusNode) return { state: 'PLUS', control: plusNode, fallbackControl: plusNode };
          return { state: 'NONE', control: null, fallbackControl: fallbackIconBtn };
        }

        function findMt5ClearSearchButton(searchInput) {
          if (!searchInput) return null;
          let cur = searchInput.parentElement;
          for (let depth = 0; depth < 3 && cur && cur !== document.body; depth++) {
            const candidates = cur.querySelectorAll
              ? Array.from(cur.querySelectorAll("button, [role='button'], span, div, a")).filter(isVisible)
              : [];
            const hit = candidates.find(function(el) {
              if (el === searchInput) return false;
              const t = String(el.innerText || el.textContent || '').replace(/\s+/g, '').trim().toLowerCase();
              const meta = (
                (el.getAttribute && el.getAttribute('aria-label') || '') + ' ' +
                (el.getAttribute && el.getAttribute('title') || '') + ' ' +
                (el.getAttribute && el.getAttribute('data-testid') || '') + ' ' +
                (typeof el.className === 'string' ? el.className : '')
              ).toLowerCase();
              return t === '×' || t === '✕' || t === 'x' || meta.includes('clear') || meta.includes('reset');
            });
            if (hit) return hit;
            cur = cur.parentElement;
          }
          return null;
        }

        function clearMt5SearchBox(searchInput) {
          if (!searchInput) return false;
          const clearBtn = findMt5ClearSearchButton(searchInput);
          if (clearBtn) {
            singleClick(clearBtn);
          }
          if (searchInput.value) {
            setNativeInputValue(searchInput, '');
          }
          return true;
        }

        function findMt5DisplayedWatchlistRow(requestedSymbol, verifiedAliases) {
          const hit = findSymbolElement(requestedSymbol, null, verifiedAliases);
          if (!hit) return null;
          const txt = String(getElementText(hit.element) || '').trim();
          if (isCategoryFolderRowText(txt)) return null;
          return hit;
        }

        function findMt5WatchlistScrollContainers(searchInput) {
          const seen = new Set();
          const out = [];
          function addIfScrollable(el) {
            if (!el || seen.has(el) || !isVisible(el)) return;
            try {
              if (el.scrollHeight > (el.clientHeight || 0) + 30) {
                const t = (el.textContent || '').toLowerCase();
                if (!t.includes('chart') || t.includes('forex') || t.includes('metals') || el.querySelectorAll("[data-symbol], [role='row'], tr").length > 0) {
                  seen.add(el);
                  out.push(el);
                }
              }
            } catch (_) {}
          }
          if (searchInput && searchInput.parentElement) {
            let cur = searchInput.parentElement;
            for (let d = 0; d < 4 && cur && cur !== document.body; d++) {
              addIfScrollable(cur);
              if (cur.querySelectorAll) {
                Array.from(cur.querySelectorAll('*')).forEach(addIfScrollable);
              }
              cur = cur.parentElement;
            }
          }
          deepAll("[class*='quotes' i], [class*='watchlist' i], [class*='market-watch' i], [class*='symbols' i], [role='grid'], [role='list']").forEach(addIfScrollable);
          return out;
        }

        function verifyMt5ActiveChartSymbol(expectedSymbol, verifiedAliases) {
          const chartSelectors = [
            "[class*='chart' i] [class*='title' i]",
            "[class*='chart' i] [class*='symbol' i]",
            "[class*='chart-header' i]",
            "[class*='symbol-info' i]",
            "[data-testid*='chart-symbol' i]",
            ".chart-title",
            "[role='tab'][aria-selected='true']"
          ];
          const nodes = deepAll(chartSelectors.join(',')).filter(isVisible);
          let foreignFound = '';
          for (let i = 0; i < nodes.length; i++) {
            const raw = getElementText(nodes[i]);
            if (!raw || raw.length > 90) continue;
            // Skip bottom nav tabs like "Quotes", "Chart", "Trade", "History"
            const lower = raw.toLowerCase();
            if (/^(?:quotes|chart|trade|history|orders|positions|journal|market\s*watch)$/.test(lower)) continue;
            const tokens = extractPreservedCaseTokens(raw);
            for (let j = 0; j < tokens.length; j++) {
              const tok = tokens[j];
              if (matchRank(tok, expectedSymbol, verifiedAliases) > 0) {
                return { state: 'MATCHED', symbol: tok };
              }
              if (
                !foreignFound &&
                /^(?:[A-Z]{6}(?:[._#-][A-Za-z0-9]+|[mc])?|XAU|XAG|GOLD|SILVER|BTC|ETH|US30|US500|NAS100|USTEC|DE30|DE40|GER40)/i.test(tok)
              ) {
                foreignFound = tok;
              }
            }
          }
          if (foreignFound) {
            return { state: 'MISMATCH', symbol: foreignFound };
          }
          return { state: 'UNKNOWN', symbol: '' };
        }

        function dispatchMT4RightClick(target, overrideX, overrideY) {
          if (!target) return false;
          try {
            try { if (typeof target.scrollIntoView === 'function') target.scrollIntoView({ block: 'center', inline: 'center' }); } catch (_) {}
            const view = getViewFor(target);
            const rect = target.getBoundingClientRect ? target.getBoundingClientRect() : null;
            const cx = (typeof overrideX === 'number' && isFinite(overrideX))
              ? overrideX
              : (rect ? (rect.left + Math.max(6, Math.min(rect.width / 2, 48))) : 0);
            const cy = (typeof overrideY === 'number' && isFinite(overrideY))
              ? overrideY
              : (rect
                  ? (rect.height > 80
                      ? (rect.top + Math.min(rect.height / 2, 120))
                      : (rect.top + Math.max(6, Math.min(rect.height / 2, 24))))
                  : 0);

            target.dispatchEvent(
              new view.MouseEvent('mousedown', {
                bubbles: true,
                cancelable: true,
                composed: true,
                view: view,
                button: 2,
                buttons: 2,
                which: 3,
                clientX: cx,
                clientY: cy
              })
            );
            target.dispatchEvent(
              new view.MouseEvent('mouseup', {
                bubbles: true,
                cancelable: true,
                composed: true,
                view: view,
                button: 2,
                buttons: 0,
                which: 3,
                clientX: cx,
                clientY: cy
              })
            );
            target.dispatchEvent(
              new view.MouseEvent('contextmenu', {
                bubbles: true,
                cancelable: true,
                composed: true,
                view: view,
                button: 2,
                buttons: 0,
                which: 3,
                clientX: cx,
                clientY: cy
              })
            );
            return true;
          } catch (_) {
            return false;
          }
        }

        function openMT4ContextMenu(target) {
          if (!target && typeof document === 'undefined') return false;
          const panelTarget = isRenderedVisible(target)
            ? target
            : (findMarketWatchPanel() || target);
          if (!panelTarget) return false;
          return dispatchMT4RightClick(panelTarget);
        }

        function openMT4RowContextMenu(rowElement) {
          if (!rowElement || !isRenderedVisible(rowElement)) return false;
          return dispatchMT4RightClick(rowElement);
        }

        function isNonMarketWatchQuoteContext(el) {
          if (!el || !el.closest) return false;
          try {
            return !!el.closest("[role='dialog'], [role='alertdialog'], .trade-form, [class*='order-dialog' i], [class*='order-ticket' i], [class*='position' i], [class*='positions' i], [class*='history' i], [class*='deal' i], [class*='orders' i], [class*='toolbox' i], [class*='journal' i]");
          } catch (_) {
            return false;
          }
        }

        function findVisibleMarketWatchSymbolRows(marketWatchPanel) {
          if (!marketWatchPanel) return [];
          const rows = [];
          const seen = new Set();
          function collectFrom(selector, requireInstrumentShape) {
            const trs = deepAll(selector, marketWatchPanel);
            for (let i = 0; i < trs.length; i++) {
              const tr = trs[i];
              if (!isRenderedVisible(tr) || seen.has(tr) || isInsideMenuContainer(tr) || isNonMarketWatchQuoteContext(tr)) continue;
              if (requireInstrumentShape && tr.children && tr.children.length > 6) continue;
              const firstCell = (tr.getElementsByTagName && tr.getElementsByTagName('td')[0]) ||
                (tr.querySelector && tr.querySelector("[role='gridcell'], [role='cell'], [class*='symbol' i]"));
              const raw = String((firstCell ? firstCell.textContent : tr.textContent) || '').trim();
              if (!raw || raw.length > 90) continue;
              const fullRaw = String(tr.textContent || '').replace(/\s+/g, ' ').trim();
              if (/#\d{4,}/.test(fullRaw) || /\bclose\s+position\b/i.test(fullRaw)) continue;
              if (/\b(?:buy|sell)\s+\d+(?:\.\d+)?\b/i.test(fullRaw)) continue;
              const lower = raw.toLowerCase();
              if (lower.startsWith('symbol') || lower === 'bid' || lower === 'ask') continue;
              const ds = (tr.getAttribute && (tr.getAttribute('data-symbol') || tr.getAttribute('data-name'))) || '';
              const tok = String(ds || '').trim() || extractPreservedCaseTokens(raw)[0] || '';
              if (!tok || tok.length < 3 || /^(?:symbol|bid|ask|high|low|time|spread)$/i.test(tok)) continue;
              if (requireInstrumentShape && !looksLikeBrokerInstrumentToken(tok)) continue;
              seen.add(tr);
              rows.push(tr);
            }
          }
          collectFrom("tr, [role='row'], [data-symbol]", false);
          if (rows.length < 2) {
            collectFrom("li, [class*='row' i], [class*='quote' i], [class*='symbol' i]", true);
          }
          return rows;
        }

        function isInsideMenuContainer(el) {
          if (!el || !el.closest) return false;
          try {
            if (el.closest("[role='tablist'], [role='tab'], .tabs, [class*='tab-bar' i]")) return false;
            return !!el.closest(".page-menu, .mt4-ctx-menu, [role='menu'], [role='menuitem'], [class*='context-menu' i], [class*='ctx-menu' i], [class*='popup-menu' i], [class*='dropdown' i]");
          } catch (_) {
            return false;
          }
        }

        function resolveMT4MenuItemTarget(node) {
          if (!node) return null;
          if (!isInsideMenuContainer(node)) return node;
          if (node.closest) {
            const semantic = node.closest("[role='menuitem'], [role='menuitemcheckbox'], li, button, a, tr");
            if (semantic && isInsideMenuContainer(semantic)) return semantic;
            const nodeClean = (node.textContent || '').replace(/[✓✔☑•]/g, '').replace(/\s+/g, ' ').trim();
            let cur = node;
            for (let depth = 0; depth < 3 && cur && cur.parentElement; depth++) {
              const p = cur.parentElement;
              const pCls = (typeof p.className === 'string' ? p.className : '').toLowerCase();
              if (pCls.includes('page-menu') || pCls.includes('box') || (p.getAttribute && p.getAttribute('role') === 'menu')) {
                break;
              }
              if (p.children && p.children.length > 2) break;
              const pClean = (p.textContent || '').replace(/[✓✔☑•]/g, '').replace(/\s+/g, ' ').trim();
              if (pClean.length > 44 || (nodeClean && pClean.length > nodeClean.length + 18)) break;
              cur = p;
            }
            return cur;
          }
          return node;
        }

        function findMT4MenuItemByLabels(labels, strictMenuOnly) {
          const menuContainers = deepAll(".page-menu, .mt4-ctx-menu, [role='menu'], [class*='context-menu' i], [class*='ctx-menu' i], [class*='popup-menu' i]")
            .filter(isRenderedVisible);
          for (let lIdx = 0; lIdx < labels.length; lIdx++) {
            const wanted = String(labels[lIdx] || '').replace(/[✓✔☑•]/g, '').replace(/\s+/g, ' ').trim().toLowerCase();
            if (!wanted) continue;
            for (let mIdx = 0; mIdx < menuContainers.length; mIdx++) {
              const menu = menuContainers[mIdx];
              const nodes = deepAll("[role='menuitem'], [role='menuitemcheckbox'], li, tr, td, a, button, div, span", menu)
                .filter(isRenderedVisible);
              let bestMatch = null;
              let bestLen = 999;
              for (let nIdx = 0; nIdx < nodes.length; nIdx++) {
                const n = nodes[nIdx];
                if (n.closest && n.closest("[role='tablist'], [role='tab'], .tabs")) continue;
                const clean = (n.textContent || '').replace(/[✓✔☑•]/g, '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (!clean || clean.length > 48) continue;
                const exact = clean === wanted;
                const withShortcut = !exact && (
                  clean.startsWith(wanted + ' ') ||
                  clean.startsWith(wanted + '\t') ||
                  (wanted === 'symbols' && clean === 'symbols...')
                );
                if (!exact && !withShortcut) continue;
                const isLeafLike = !n.querySelector || !Array.from(n.querySelectorAll('div, span, a, li, button')).some(function(ch) {
                  if (!isRenderedVisible(ch)) return false;
                  const chClean = (ch.textContent || '').replace(/[✓✔☑•]/g, '').replace(/\s+/g, ' ').trim().toLowerCase();
                  return chClean === wanted;
                });
                const scoreLen = clean.length + (exact ? 0 : 10) + (isLeafLike ? 0 : 20);
                if (scoreLen < bestLen) {
                  bestLen = scoreLen;
                  bestMatch = resolveMT4MenuItemTarget(n);
                }
              }
              if (bestMatch) return bestMatch;
            }
          }
          if (strictMenuOnly) {
            for (let i = 0; i < labels.length; i++) {
              const hit = findExactVisibleText(labels[i], "[role='menuitem'], [role='menuitemcheckbox']");
              if (hit && !(hit.closest && hit.closest("[role='tablist'], [role='tab'], .tabs"))) {
                return hit;
              }
            }
            return null;
          }
          for (let i = 0; i < labels.length; i++) {
            const hit = findExactVisibleText(labels[i], "[role='menuitem'], button, li, a");
            if (hit && !(hit.closest && hit.closest("[role='tablist'], [role='tab'], .tabs"))) return hit;
          }
          return null;
        }

        // Matches visible "Show All" label inside open context menus (never hardcoded positional div:nth-child(7)).
        function findMT4ShowAllMenuItem() {
          return findExactVisibleText('Show All', "[role='menuitem'], [role='menuitemcheckbox']") ||
            findMT4MenuItemByLabels(['Show All'], true);
        }

        function findMT4NewOrderMenuItem() {
          // Strictly scoped to menu containers so top toolbar buttons are never mis-clicked as context menu items
          return findMT4MenuItemByLabels(['New Order'], true);
        }

        function findMT4SymbolsMenuItem() {
          // Strictly scoped to open menu containers so a permanent "Symbols" panel tab is never mis-matched
          return findMT4MenuItemByLabels(['Symbols', 'Symbols...'], true);
        }

        function clickMT4MenuItem(el) {
          if (!el || !isRenderedVisible(el)) return false;
          const target = resolveMT4MenuItemTarget(el) || el;
          try {
            const view = getViewFor(target);
            const rect = target.getBoundingClientRect ? target.getBoundingClientRect() : null;
            const cx = rect ? (rect.left + rect.width / 2) : 0;
            const cy = rect ? (rect.top + rect.height / 2) : 0;
            ['mouseenter', 'mouseover', 'mousemove'].forEach(function(evName) {
              try {
                target.dispatchEvent(new view.MouseEvent(evName, {
                  bubbles: evName !== 'mouseenter',
                  cancelable: true,
                  composed: true,
                  view: view,
                  clientX: cx,
                  clientY: cy
                }));
              } catch (_) {}
            });
            // Match MetaQuotes .page-menu activation (mouseover + .click()) first without premature focus/mousedown menu blur
            if (typeof target.click === 'function') {
              target.click();
            } else {
              target.dispatchEvent(new view.MouseEvent('click', {
                bubbles: true, cancelable: true, composed: true, view: view, button: 0, clientX: cx, clientY: cy
              }));
            }
            // If menu item stayed mounted & visible (handler listens to mousedown/mouseup instead of click), dispatch mousedown/mouseup without re-firing click
            if (target.isConnected && isRenderedVisible(target)) {
              target.dispatchEvent(new view.MouseEvent('mousedown', {
                bubbles: true, cancelable: true, composed: true, view: view, button: 0, buttons: 1, clientX: cx, clientY: cy
              }));
              target.dispatchEvent(new view.MouseEvent('mouseup', {
                bubbles: true, cancelable: true, composed: true, view: view, button: 0, buttons: 0, clientX: cx, clientY: cy
              }));
            }
            return true;
          } catch (_) {
            return clickElement(target);
          }
        }

        function clickMT4ShowAllMenuItem(el) {
          return clickMT4MenuItem(el);
        }

        function findMT4SymbolsDialog() {
          const candidates = deepAll("[role='dialog'], [role='alertdialog'], .page-window, [class*='dialog' i], [class*='modal' i]").filter(isRenderedVisible);
          return candidates.find(function(el) {
            if (el.closest && el.closest(".market-watch, [class*='market-watch' i], [role='tablist']")) return false;
            const cls = ((el.className || '') + ' ' + (el.getAttribute && el.getAttribute('aria-label') || '')).toLowerCase();
            if (cls.includes('market-watch')) return false;
            const txt = (el.textContent || '').toLowerCase();
            if (txt.includes('market watch') && (txt.includes('balance') || txt.includes('bid'))) return false;
            return txt.includes('symbols') ||
              (txt.includes('show') && (txt.includes('hide') || txt.includes('close') || txt.includes('properties') || txt.includes('specification')));
          }) || null;
        }

        function expandMT4SymbolGroups(dialog) {
          if (!dialog) return 0;
          let expandedCount = 0;
          const groupSelectors = [
            "[aria-expanded='false']",
            "[class*='collapsed' i]",
            "[class*='group' i]",
            "[class*='folder' i]",
            "[class*='category' i]",
            "summary"
          ];
          const nodes = deepAll(groupSelectors.join(','), dialog).filter(isRenderedVisible);
          for (let i = 0; i < nodes.length; i++) {
            const n = nodes[i];
            if (n.__btechExpandedClicked) continue;
            const isExplicitCollapsed = n.getAttribute && n.getAttribute('aria-expanded') === 'false';
            const cls = (n.className || '').toLowerCase();
            const looksCollapsed = isExplicitCollapsed || cls.includes('collapsed') || cls.includes('closed') || n.tagName === 'SUMMARY';
            if (looksCollapsed || cls.includes('group') || cls.includes('folder') || cls.includes('category')) {
              if (n.getAttribute && n.getAttribute('aria-expanded') === 'true') continue;
              n.__btechExpandedClicked = true;
              singleClick(n);
              expandedCount++;
            }
          }
          return expandedCount;
        }

        function isMT4DialogRowAlreadyShown(rowEl) {
          if (!rowEl) return false;
          const cls = (rowEl.className || '').toLowerCase();
          if (rowEl.getAttribute && (rowEl.getAttribute('data-visible') === 'true' || rowEl.getAttribute('data-shown') === 'true')) return true;
          if (cls.includes('shown') || cls.includes('visible') || cls.includes('active-symbol')) return true;
          if (cls.includes('hidden') || (rowEl.getAttribute && rowEl.getAttribute('data-visible') === 'false')) return false;
          return false;
        }

        function closeMT4SymbolsDialog(dialog) {
          if (!dialog) return false;
          const closeBtn =
            findExactVisibleText('Close', "button, [role='button']", dialog) ||
            findExactVisibleText('OK', "button, [role='button']", dialog) ||
            findExactVisibleText('×', "button, [role='button']", dialog);
          if (closeBtn) {
            singleClick(closeBtn);
            return true;
          }
          return false;
        }

        function doubleClickMT4Row(element) {
          if (!element || !isVisible(element)) return false;
          const target = getClickableElement(element);
          try {
            const view = getViewFor(target);
            const rect = target.getBoundingClientRect ? target.getBoundingClientRect() : null;
            const cx = rect ? (rect.left + rect.width / 2) : 0;
            const cy = rect ? (rect.top + rect.height / 2) : 0;
            function mouseEvent(type, detail) {
              target.dispatchEvent(
                new view.MouseEvent(type, {
                  bubbles: true,
                  cancelable: true,
                  composed: true,
                  view: view,
                  button: 0,
                  buttons: type === 'mouseup' ? 0 : 1,
                  detail: detail,
                  clientX: cx,
                  clientY: cy
                })
              );
            }
            mouseEvent('mousedown', 1);
            mouseEvent('mouseup', 1);
            mouseEvent('click', 1);
            mouseEvent('mousedown', 2);
            mouseEvent('mouseup', 2);
            mouseEvent('click', 2);
            mouseEvent('dblclick', 2);
            return true;
          } catch (_) {
            return false;
          }
        }

        // RoboTrader Part 2 D: first-cell (td[0]) inspection in Market Watch table, combined with
        // rank-verified live DOM symbol matching.
        function findMT4MarketWatchSymbolRow(requestedSymbol, marketWatchPanel, verifiedAliases) {
          try {
            const exactTbody = document.querySelector(
              'body > div.page-window.market-watch.compact > div > div.b > div.page-block > div > table > tbody'
            );
            const scopes = [];
            if (exactTbody) scopes.push(exactTbody);
            if (marketWatchPanel && scopes.indexOf(marketWatchPanel) < 0) scopes.push(marketWatchPanel);

            let bestFirstCell = null;
            for (let s = 0; s < scopes.length; s++) {
              const trs = scopes[s].querySelectorAll ? Array.from(scopes[s].querySelectorAll('tr')) : [];
              for (let i = 0; i < trs.length; i++) {
                const tr = trs[i];
                if (!isRenderedVisible(tr)) continue;
                const firstTd = tr.getElementsByTagName ? tr.getElementsByTagName('td')[0] : null;
                if (!firstTd) continue;
                const cellRaw = String(firstTd.textContent || '').replace(/^\s+/, '');
                const tok = extractPreservedCaseTokens(cellRaw)[0] || '';
                if (!tok) continue;
                const rank = matchRank(tok, requestedSymbol, verifiedAliases);
                if (rank > 0 && (!bestFirstCell || rank > bestFirstCell.matchRank)) {
                  bestFirstCell = {
                    element: tr,
                    actualSymbol: tok,
                    matchRank: rank,
                    matchType: matchTypeForRank(rank),
                    textLength: cellRaw.length
                  };
                  if (rank === 4) return bestFirstCell;
                }
              }
            }
            if (bestFirstCell) return bestFirstCell;
          } catch (_) {}
          return marketWatchPanel
            ? findSymbolElement(requestedSymbol, marketWatchPanel, verifiedAliases)
            : findSymbolElement(requestedSymbol, null, verifiedAliases);
        }

        function isLikelyQuoteTableOrPanelContainer(el) {
          if (!el || !isRenderedVisible(el) || isInsideMenuContainer(el) || isNonMarketWatchQuoteContext(el)) return false;
          const txtLower = (el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
          if (!txtLower) return false;
          if (/\b(?:balance|equity|free\s*margin|close\s+position|account\s+history)\b/.test(txtLower)) return false;
          if (/#\d{4,}/.test(txtLower)) return false;
          return true;
        }

        function findMarketWatchPanel() {
          const exactMwHits = deepAll('body > div.page-window.market-watch, div.page-window.market-watch')
            .filter(function(el) {
              return isRenderedVisible(el) && !isInsideMenuContainer(el) && !isNonMarketWatchQuoteContext(el);
            });
          if (exactMwHits.length) return exactMwHits[0];

          const explicitPanel = deepAll("[class*='market-watch' i], [data-testid*='market-watch' i], [aria-label*='Market Watch' i]")
            .find(function(el) {
              if (!isRenderedVisible(el) || isInsideMenuContainer(el) || isNonMarketWatchQuoteContext(el)) return false;
              const tag = (el.tagName || '').toUpperCase();
              if (tag === 'BUTTON' || tag === 'A' || (el.getAttribute && el.getAttribute('role') === 'menuitem')) return false;
              return true;
            });
          if (explicitPanel) return explicitPanel;

          const marketWatchLabel = deepAll("div, span, label, th, h1, h2, h3, [role='heading']")
            .filter(isRenderedVisible)
            .find(function(el) {
              if (isInsideMenuContainer(el) || isNonMarketWatchQuoteContext(el)) return false;
              const clean = (el.textContent || '').replace(/[✓✔☑]/g, '').replace(/\s+/g, ' ').trim().toLowerCase();
              if (!clean || clean.length > 44) return false;
              return clean === 'market watch' || /^market watch(?:\s*[:(\-·|]|\s+\d)/.test(clean);
            }) || null;

          if (marketWatchLabel) {
            let marketWatchPanel = marketWatchLabel;
            for (let i = 0; i < 5 && marketWatchPanel && marketWatchPanel !== document.body; i++) {
              if (marketWatchPanel.querySelectorAll && !isInsideMenuContainer(marketWatchPanel)) {
                const rowCount = marketWatchPanel.querySelectorAll("tr, [role='row'], [data-symbol], li, [class*='symbol']").length;
                const panelText = (marketWatchPanel.textContent || '').toLowerCase();
                if (panelText.includes('market watch') && (rowCount > 0 || i >= 2)) {
                  break;
                }
              }
              marketWatchPanel = marketWatchPanel.parentElement;
            }
            if (marketWatchPanel && marketWatchPanel !== document.body && isRenderedVisible(marketWatchPanel) && !isInsideMenuContainer(marketWatchPanel)) {
              return marketWatchPanel;
            }
            if (isRenderedVisible(marketWatchLabel) && !isInsideMenuContainer(marketWatchLabel)) {
              return marketWatchLabel;
            }
          }

          // Fallback for compact / hashed-class terminals: locate quote table/panel directly
          const vpW = (typeof window !== 'undefined' && window.innerWidth) ? window.innerWidth : 1280;
          const vpH = (typeof window !== 'undefined' && window.innerHeight) ? window.innerHeight : 960;
          const quotePanels = deepAll("div.page-window, [class*='page-window' i], [class*='panel' i], [class*='quotes' i], [class*='window' i], [class*='widget' i], [class*='pane' i]")
            .filter(function(el) {
              if (!isLikelyQuoteTableOrPanelContainer(el)) return false;
              try {
                const rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
                if (rect && rect.width > vpW * 0.85 && rect.height > vpH * 0.85) return false;
              } catch (_) {}
              const tbl = el.querySelector ? el.querySelector('table, [role="grid"], [role="list"]') : null;
              if (!tbl) return false;
              const hdrText = (tbl.textContent || '').toLowerCase();
              const rows = findVisibleMarketWatchSymbolRows(el);
              const instrumentRows = rows.filter(function(r) {
                const fc = (r.getElementsByTagName && r.getElementsByTagName('td')[0]) || r;
                const tok = ((r.getAttribute && (r.getAttribute('data-symbol') || r.getAttribute('data-name'))) || extractPreservedCaseTokens(fc.textContent || '')[0] || '').trim();
                return looksLikeBrokerInstrumentToken(tok);
              });
              return instrumentRows.length >= 2 && (hdrText.includes('bid') || hdrText.includes('ask') || hdrText.includes('symbol') || instrumentRows.length >= 3);
            });
          if (quotePanels.length) return quotePanels[0];

          // Row-first quote-container fallback: derive Market Watch container directly from visible quote rows
          const listContainers = deepAll("table, tbody, [role='grid'], [role='listbox'], [role='list']");
          let bestList = null;
          let bestCount = 0;
          for (let i = 0; i < listContainers.length; i++) {
            const lc = listContainers[i];
            if (!isLikelyQuoteTableOrPanelContainer(lc)) continue;
            const rows = findVisibleMarketWatchSymbolRows(lc);
            let instCount = 0;
            for (let r = 0; r < rows.length; r++) {
              const row = rows[r];
              const fc = (row.getElementsByTagName && row.getElementsByTagName('td')[0]) || row;
              const tok = ((row.getAttribute && (row.getAttribute('data-symbol') || row.getAttribute('data-name'))) || extractPreservedCaseTokens(fc.textContent || '')[0] || '').trim();
              if (looksLikeBrokerInstrumentToken(tok)) instCount++;
            }
            if (instCount >= 2 && instCount > bestCount) {
              bestCount = instCount;
              bestList = lc;
            }
          }
          if (bestList) {
            let panel = bestList;
            let cur = bestList.parentElement;
            for (let depth = 0; depth < 4 && cur && cur !== document.body && cur !== document.documentElement; depth++) {
              if (!isLikelyQuoteTableOrPanelContainer(cur)) break;
              try {
                const r = cur.getBoundingClientRect ? cur.getBoundingClientRect() : null;
                if (r && r.width > vpW * 0.85 && r.height > vpH * 0.85) break;
              } catch (_) {}
              const cls = (typeof cur.className === 'string' ? cur.className : '').toLowerCase();
              panel = cur;
              if (/page-window|market-watch|quotes|panel|widget|pane|sidebar/.test(cls)) break;
              cur = cur.parentElement;
            }
            return panel;
          }
          return null;
        }

        function findMT4ViewMenuDropdown() {
          const candidates = deepAll(".page-menu, [role='menu'], [class*='dropdown' i], [class*='popup-menu' i], [class*='menu' i]").filter(isRenderedVisible);
          return candidates.find(function(m) {
            const txt = (m.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
            if (!txt || txt.includes('show all') || txt.includes('new order')) return false;
            if (txt.includes('file') && txt.includes('view') && txt.includes('help')) return false;
            return (txt.includes('market watch') && (
              txt.includes('languages') ||
              txt.includes('toolbox') ||
              txt.includes('terminal') ||
              txt.includes('navigator') ||
              txt.includes('status bar') ||
              txt.includes('data window')
            )) || (m.getAttribute && m.getAttribute('role') === 'menu' && txt.includes('market watch'));
          }) || null;
        }

        function isMT4MenuItemChecked(el) {
          if (!el) return false;
          const raw = String(el.textContent || '');
          if (/[✓✔☑]/.test(raw)) return true;
          if (el.getAttribute) {
            const ac = (el.getAttribute('aria-checked') || el.getAttribute('data-checked') || '').toLowerCase();
            if (ac === 'true' || ac === 'checked') return true;
          }
          const cls = ((el.className || '') + ' ' + ((el.parentElement && el.parentElement.className) || '')).toLowerCase();
          return cls.includes('checked') || cls.includes('active');
        }

        function findMT4ViewMarketWatchItem(scope) {
          const root = scope || findMT4ViewMenuDropdown();
          const items = deepAll("[role='menuitem'], [role='menuitemcheckbox'], li, button, a, tr, div, span", root).filter(isVisible);
          return items.find(function(n) {
            if (!root && !isInsideMenuContainer(n)) return false;
            const raw = (n.textContent || '').replace(/[✓✔☑]/g, '').replace(/\s+/g, ' ').trim().toLowerCase();
            if (raw !== 'market watch') return false;
            return !n.querySelector || !Array.from(n.querySelectorAll('div, span, a, li')).some(function(ch) {
              return (ch.textContent || '').replace(/[✓✔☑]/g, '').replace(/\s+/g, ' ').trim().toLowerCase() === 'market watch';
            });
          }) || null;
        }

        async function dismissOpenMT4ViewMenus(waitFn) {
          const openDrop = findMT4ViewMenuDropdown();
          if (!openDrop) return false;
          try {
            const view = typeof window !== 'undefined' ? window : null;
            const target = document.activeElement || document.body;
            if (target && view && typeof view.KeyboardEvent === 'function') {
              target.dispatchEvent(new view.KeyboardEvent('keydown', {
                key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true, composed: true
              }));
              target.dispatchEvent(new view.KeyboardEvent('keyup', {
                key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true, composed: true
              }));
            }
          } catch (_) {}
          if (waitFn) await waitFn(80);
          if (findMT4ViewMenuDropdown()) {
            const chartNeutral = deepAll("[class*='chart' i], canvas, body > div").find(function(el) {
              return isRenderedVisible(el) && !isInsideMenuContainer(el) && !(el.textContent || '').toLowerCase().includes('languages');
            });
            if (chartNeutral) {
              singleClick(chartNeutral);
              if (waitFn) await waitFn(80);
            }
          }
          return true;
        }

        async function openHiddenMarketWatch(waitFn, pollMs) {
          let panel = findMarketWatchPanel();
          if (panel) {
            await dismissOpenMT4ViewMenus(waitFn);
            return panel;
          }

          // 1. Direct Market Watch toolbar / toggle button (strictly outside dropdown menus)
          const mwToggle = deepAll("button, [role='button'], [title*='Market Watch' i], [aria-label*='Market Watch' i], a")
            .filter(isVisible)
            .find(function(b) {
              if (b.closest && b.closest(".page-menu, .mt4-ctx-menu, [class*='context-menu' i], [class*='ctx-menu' i], [class*='popup-menu' i]")) return false;
              const ownTitle = ((b.getAttribute && (b.getAttribute('aria-label') || b.getAttribute('title'))) || '').trim();
              const ownText = (b.children && b.children.length > 2) ? '' : (b.innerText || b.textContent || '');
              const t = (ownText + ' ' + ownTitle).replace(/\s+/g, ' ').trim().toLowerCase();
              return t.includes('market watch') && !isMT4MenuItemChecked(b);
            });
          if (mwToggle) {
            singleClick(mwToggle);
            await waitFn(Math.min(350, (pollMs || 250) + 80));
            panel = findMarketWatchPanel();
            if (panel) {
              await dismissOpenMT4ViewMenus(waitFn);
              return panel;
            }
          }

          // 2. Desktop top "View" menu -> "Market Watch" menu item (Languages / Market Watch / Toolbox)
          const viewCandidates = deepAll("button, a, [role='button'], [role='menuitem'], [role='tab'], li, span, div")
            .filter(function(el) {
              if (!isRenderedVisible(el)) return false;
              if (el.closest && el.closest(".page-menu, .mt4-ctx-menu, [class*='context-menu' i], [class*='ctx-menu' i], [class*='popup-menu' i]")) return false;
              const clean = (el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
              if (clean !== 'view') return false;
              const r = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
              return !r || r.top <= 160;
            });
          if (viewCandidates.length) {
            let viewDropdown = null;
            for (let vc = viewCandidates.length - 1; vc >= 0 && !viewDropdown; vc--) {
              const trigger = viewCandidates[vc];
              singleClick(trigger);
              viewDropdown = findMT4ViewMenuDropdown();
              const dropStart = Date.now();
              while (!viewDropdown && Date.now() - dropStart < 300) {
                await waitFn(Math.min(70, pollMs || 70));
                viewDropdown = findMT4ViewMenuDropdown();
              }
            }
            const viewMwItem = findMT4ViewMarketWatchItem(viewDropdown);
            if (viewMwItem) {
              const explicitlyChecked = /[✓✔☑]/.test(String(viewMwItem.textContent || '')) ||
                (viewMwItem.getAttribute && (viewMwItem.getAttribute('aria-checked') === 'true' || viewMwItem.getAttribute('data-checked') === 'true'));
              if (!explicitlyChecked && !findMarketWatchPanel()) {
                clickMT4MenuItem(viewMwItem);
                const mwStart = Date.now();
                const openWaitLimit = Math.min(1200, Math.max(400, (pollMs || 250) * 4));
                while (Date.now() - mwStart < openWaitLimit) {
                  panel = findMarketWatchPanel();
                  if (panel) break;
                  await waitFn(Math.min(120, pollMs || 120));
                }
              }
              await dismissOpenMT4ViewMenus(waitFn);
              if (panel) return panel;
            } else {
              await dismissOpenMT4ViewMenus(waitFn);
            }
          }

          // 3. MT4 desktop keyboard shortcut Ctrl+M
          try {
            const view = typeof window !== 'undefined' ? window : null;
            const target = document.body || document;
            if (target && view && typeof view.KeyboardEvent === 'function') {
              target.dispatchEvent(new view.KeyboardEvent('keydown', {
                key: 'm', code: 'KeyM', keyCode: 77, which: 77, ctrlKey: true, bubbles: true, cancelable: true, composed: true
              }));
              await waitFn(Math.min(350, (pollMs || 250) + 80));
              panel = findMarketWatchPanel();
              if (panel) return panel;
            }
          } catch (_) {}

          return findMarketWatchPanel();
        }

        function discoverAllBrokerSymbols(scopeRoot) {
          const seenLower = new Set();
          const result = [];
          const EXCLUDED_WORDS = new Set([
            'BUY', 'SELL', 'MARKET', 'WATCH', 'SYMBOL', 'SYMBOLS', 'BID', 'ASK', 'HIGH', 'LOW',
            'SPREAD', 'TIME', 'ORDER', 'NEW', 'VOLUME', 'LOT', 'SL', 'TP', 'COMMENT', 'BALANCE',
            'EQUITY', 'MARGIN', 'FREE', 'PROFIT', 'TRADE', 'HISTORY', 'CHART', 'QUOTES', 'SHOW',
            'ALL', 'HIDE', 'DETAILS', 'SPECIFICATION', 'PROPERTIES', 'CONNECT', 'ACCOUNT', 'CLOSE',
            'CANCEL', 'OK', 'DONE', 'YES', 'NO', 'MT4', 'MT5', 'INSTANT', 'EXECUTION', 'PRICE',
            'FOREX', 'METALS', 'OIL', 'INDICES', 'CRYPTO', 'STOCKS', 'SHARES'
          ]);

          function maybeAdd(tok) {
            const clean = String(tok || '').trim();
            if (!clean || clean.length < 3 || clean.length > 18) return;
            const upper = clean.toUpperCase();
            if (EXCLUDED_WORDS.has(upper)) return;
            if (/^\d+(?:\.\d+)?$/.test(clean)) return;
            if (!/^[A-Za-z#][A-Za-z0-9._#-]{1,17}$/.test(clean)) return;
            if (!/[A-Za-z]{2,}/.test(clean)) return;
            if (!looksLikeBrokerInstrumentToken(clean)) return;
            const key = clean.toLowerCase();
            if (!seenLower.has(key)) {
              seenLower.add(key);
              result.push(clean);
            }
          }

          const explicitEls = deepAll("[data-symbol], [data-name], [role='row'], tr, [role='option'], option, li, [class*='symbol']", scopeRoot);
          for (let i = 0; i < explicitEls.length; i++) {
            const el = explicitEls[i];
            if (el.tagName !== 'OPTION' && !isVisible(el)) continue;
            const rawTextCheck = (el.textContent || '').trim();
            if (isCategoryFolderRowText(rawTextCheck)) continue;
            const ds = (el.getAttribute && (el.getAttribute('data-symbol') || el.getAttribute('data-name'))) || '';
            if (ds) {
              maybeAdd(ds.trim());
            }
            const raw = (el.tagName === 'OPTION' ? (el.value || el.textContent) : (el.textContent || '')) || '';
            if (raw && raw.length <= 80) {
              const firstTok = raw.trim().split(/[\s\r\n,;/|]+/)[0];
              maybeAdd(firstTok);
            }
          }
          return result;
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
                // Prioritize RoboTrader recovered MT4 (#login/#password/#server) and MT5 (autocomplete='username', name='login', name='password') selectors
                const passwordInput =
                  queryDeepAll("#password, input[type='password'], input[name='password']").find(isVisible) ||
                  inputs.find(el => (el.type || '').toLowerCase() === 'password');

                const loginInput =
                  queryDeepAll("#login, input[autocomplete='username'], input[name='login']").find(el => isVisible(el) && el !== passwordInput) ||
                  inputs.find(el => {
                    if (el === passwordInput) return false;
                    const type = (el.type || '').toLowerCase();
                    if (type === 'checkbox' || type === 'radio' || type === 'hidden' || type === 'submit') return false;
                    const meta = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
                    return meta.includes('login') || meta.includes('account') || meta.includes('user') || meta.includes('email') || type === 'number' || type === 'text';
                  });

                const serverInput =
                  queryDeepAll("#server, input[name='server'], select[name='server']").find(el => isVisible(el) && el !== passwordInput && el !== loginInput) ||
                  inputs.find(el => {
                    if (el === passwordInput || el === loginInput) return false;
                    const meta = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
                    return meta.includes('server') || meta.includes('broker') || el.tagName === 'SELECT';
                  });

                return { allCount: inputs.length, loginInput, passwordInput, serverInput };
              }

              function findActionButton(matchFn) {
                const buttons = queryDeepAll('button, [role="button"], input[type="submit"], input[type="button"], a').filter(isVisible);
                const strict = buttons.find(el => {
                  const t = (el.innerText || el.value || el.textContent || el.getAttribute('aria-label') || el.getAttribute('title') || '').replace(/\s+/g, ' ').trim().toLowerCase();
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

                const platformTab = findActionButton(t => {
                  const u = t.toUpperCase();
                  return u === PLATFORM || u === ('METATRADER ' + PLATFORM.replace('MT', ''));
                });
                if (platformTab) {
                  realClick(platformTab);
                }

                const primaryButtons = queryDeepAll('button, [role="button"], input[type="submit"], input[type="button"]').filter(isVisible);
                const realBtn = primaryButtons.find(el => {
                  const t = (el.innerText || el.value || el.textContent || el.getAttribute('aria-label') || el.getAttribute('title') || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  return t.includes('connect to account') || t === 'log in' || t === 'login' || t === 'sign in';
                });
                if (realBtn) {
                  realClick(realBtn);
                  return true;
                }

                const openLoginBtn = findActionButton(t => t.includes('connect to account') || t === 'log in' || t === 'login' || t === 'sign in');
                if (openLoginBtn) {
                  realClick(openLoginBtn);
                  return true;
                }
                return false;
              }

              function findSubmitButton() {
                const candidates = queryDeepAll('button, input[type="submit"], input[type="button"], [role="button"]').filter(isVisible);
                return candidates.find(el => {
                  const txt = (el.innerText || el.value || el.textContent || el.getAttribute('aria-label') || el.getAttribute('title') || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  return txt.includes('connect to account') || txt === 'connect' || txt === 'ok' || txt === 'log in' || txt === 'login' || txt === 'sign in' || txt === 'submit';
                });
              }

              let attempts = 0;
              let submitted = false;
              let submittedAtAttempt = -1;
              let lastTriggerAttempt = -10;
              let lastPickerConnectAttempt = -10;

              function checkTerminalState() {
                const platformMismatch = detectPlatformMismatchError(PLATFORM);
                if (platformMismatch) {
                  return { done: true, ok: false, msg: platformMismatch };
                }

                const rawText = collectDeepText();
                const lower = rawText.toLowerCase();

                // RoboTrader Part 2 F explicit .error element & 'Invalid account or password' inspection
                const visibleDotError = queryDeepAll('.error').find(el => {
                  if (!isVisible(el)) return false;
                  const txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                  return !!txt;
                });
                if (submitted && visibleDotError) {
                  const errTxt = (visibleDotError.innerText || visibleDotError.textContent || '').replace(/\s+/g, ' ').trim();
                  return {
                    done: true,
                    ok: false,
                    msg: 'Web Terminal reported: "' + errTxt.slice(0, 220) + '"\nURL: ' + (window.location.href || '')
                  };
                }

                const errorTerms = [
                  'invalid account or password',
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
                const isReconnectPrompt = hasMt5ReconnectScreen(lower, !!passwordInput);
                const hasSpinner = queryDeepAll('[role="progressbar"], [class*="spinner"], [class*="loader"], [class*="loading"]').some(isVisible);
                const isStillConnecting =
                  lower.includes('connecting...') ||
                  lower.includes('authorizing...') ||
                  lower.includes('waiting for update') ||
                  hasSpinner;

                if ((submitted || isReconnectPrompt) && !passwordInput && (hasAccountsPicker || isReconnectPrompt) && !isStillConnecting && (attempts - lastPickerConnectAttempt) >= 6) {
                  const connectBtn = findConnectToAccountButton(LOGIN);
                  if (connectBtn) {
                    lastPickerConnectAttempt = attempts;
                    submitted = true;
                    submittedAtAttempt = attempts;
                    logStep('Reconnecting saved ' + PLATFORM + ' account ' + LOGIN + ' via Connect to account...');
                    realClick(connectBtn);
                    return { done: false, lower: lower };
                  }
                }

                if (submitted && (attempts - submittedAtAttempt) < 5) {
                  return { done: false, lower: lower };
                }
                if (!submitted && attempts < 4) {
                  return { done: false, lower: lower };
                }

                if (submitted && (attempts - submittedAtAttempt) >= 24 && passwordInput) {
                  const visibleSample = rawText.split(/\r?\n/).map(l => l.trim()).filter(Boolean).slice(0, 4).join(' | ').slice(0, 200);
                  return {
                    done: true,
                    ok: false,
                    msg: 'Web Terminal still shows the login form after submitting.\nTerminal text: "' + (visibleSample || 'Login dialog visible') + '"\nURL: ' + (window.location.href || '')
                  };
                }

                const hasVerifiedSession = hasVerifiedAccountSession(rawText, lower, PLATFORM);

                if (!passwordInput && !hasAccountsPicker && !isReconnectPrompt && !isStillConnecting && hasVerifiedSession) {
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
                const currentLower = collectDeepText().toLowerCase();

                if (!found.passwordInput && !submitted && !hasMt5ReconnectScreen(currentLower, false) && attempts >= 2 && (attempts - lastTriggerAttempt) >= 4) {
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
     * Generates JS that cancels any active discovery/order run and clears scheduled JS timers.
     */
    fun buildCancelTradeScript(kotlinRunId: Long = 0L): String {
        return """
            (function() {
              if ($kotlinRunId > 0 && window.__btechActiveKotlinRunId && window.__btechActiveKotlinRunId !== $kotlinRunId) {
                return false;
              }
              window.__btechTradeCancelled = true;
              window.__btechTradeRunId = (window.__btechTradeRunId || 0) + 1;
              window.__btechDiscoveryRunning = false;
              window.__btechOrderTicketOpen = false;
              window.__btechCurrentStage = 'CANCELLED';
              if (Array.isArray(window.__btechTradeTimers)) {
                window.__btechTradeTimers.forEach(function(id) {
                  try { clearTimeout(id); } catch (_) {}
                });
              }
              window.__btechTradeTimers = [];
              if (Array.isArray(window.__btechTradeWaitResolvers)) {
                window.__btechTradeWaitResolvers.forEach(function(fn) {
                  try { fn(); } catch (_) {}
                });
              }
              window.__btechTradeWaitResolvers = [];
              return true;
            })();
        """.trimIndent()
    }

    /**
     * Lightweight O(k) heartbeat probe executed on demand or on a 4s interval.
     * Proves JS thread liveness and captures structured terminal health without wildcard body scans.
     */
    fun buildHeartbeatProbeScript(expectedPlatform: String = ""): String {
        val safePlat = JSONObject.quote(expectedPlatform.uppercase())
        return """
            (function() {
              try {
                $DEEP_DOM_HELPERS
                const EXPECTED_PLATFORM = $safePlat;
                const href = String((window.location && window.location.href) || '');
                const readyState = String((typeof document !== 'undefined' && document.readyState) || '');
                const domReady = readyState === 'interactive' || readyState === 'complete';
                const hasBodyChildren = !!(typeof document !== 'undefined' && document.body && document.body.children && document.body.children.length > 0);
                const terminalLoaded = domReady && !!href && !/^about:blank$/i.test(href) && hasBodyChildren;
                const rawText = collectDeepText();
                const lower = rawText.toLowerCase();
                const hasPwd = queryDeepAll("input[type='password'], input[name='password'], #password").filter(isVisible).length > 0;
                const isReconnect = hasMt5ReconnectScreen(lower, hasPwd);
                const verifiedSession = terminalLoaded && !hasPwd && !isReconnect && hasVerifiedAccountSession(rawText, lower, EXPECTED_PLATFORM);
                const hasVol = queryDeepAll("#volume, input[name='volume'], .trade-form, .trade-input").filter(isRenderedVisible).length > 0;
                const hasOrderBtn = queryDeepAll("button, [role='button']").filter(isRenderedVisible).some(function(b) {
                  const t = (b.innerText || b.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  return t === 'buy by market' || t === 'sell by market';
                });
                const orderTicketOpen = !!(window.__btechOrderTicketOpen || hasVol || hasOrderBtn);
                const discoveryRunning = !!(window.__btechDiscoveryRunning && window.__btechTradeCancelled !== true);
                const detectedPlat = detectLoadedTerminalPlatform() || EXPECTED_PLATFORM;
                return JSON.stringify({
                  webview_alive: true,
                  javascript_running: true,
                  terminal_loaded: terminalLoaded,
                  authenticated: verifiedSession,
                  dom_ready: domReady,
                  symbol_discovery_running: discoveryRunning,
                  order_ticket_open: orderTicketOpen,
                  current_stage: String(window.__btechCurrentStage || (verifiedSession ? 'READY' : (terminalLoaded ? 'LOADED' : 'IDLE'))),
                  run_id: Number(window.__btechActiveKotlinRunId || 0),
                  last_heartbeat: Date.now(),
                  platform: detectedPlat,
                  url: href
                });
              } catch (err) {
                return JSON.stringify({
                  webview_alive: true,
                  javascript_running: true,
                  terminal_loaded: false,
                  authenticated: false,
                  dom_ready: false,
                  symbol_discovery_running: false,
                  order_ticket_open: false,
                  current_stage: 'PROBE_ERROR:' + (err && err.message ? err.message : err),
                  run_id: Number(window.__btechActiveKotlinRunId || 0),
                  last_heartbeat: Date.now(),
                  platform: '',
                  url: String((window.location && window.location.href) || '')
                });
              }
            })();
        """.trimIndent()
    }

    /**
     * RoboTrader Part 2 N: explicit MT5 session-removal button action.
     * Invoked ONLY during deliberate account clear / reset actions — never during automatic login/trading.
     */
    fun buildMt5RemoveSessionScript(): String {
        return """
            (function() {
              const normalize = function(value) { return (value || '').replace(/\s+/g, ' ').trim(); };
              const buttons = Array.from(document.querySelectorAll('button'));
              const removeButton = buttons.find(function(button) {
                return normalize(button.innerText || button.textContent) === 'Remove';
              });
              if (!removeButton) return 'false';
              removeButton.click();
              return 'true';
            })();
        """.trimIndent()
    }

    /**
     * Enforces a genuine desktop CSS layout viewport (~1366 CSS px by default) on broker terminals
     * by rewriting mobile `width=device-width` / `initial-scale=1` viewport meta declarations early
     * and maintaining the override against post-load framework mutations via MutationObserver.
     */
    fun buildDesktopViewportEnforcementScript(
        targetWidthPx: Int = 1366,
        enabled: Boolean = true,
        isFallback: Boolean = false,
    ): String {
        val clampedWidth = targetWidthPx.coerceIn(1024, 2560)
        return """
            (function() {
              'use strict';
              const TARGET_WIDTH = $clampedWidth;
              const ENABLED = $enabled;
              const IS_FALLBACK = $isFallback;
              const DESKTOP_CONTENT = 'width=' + TARGET_WIDTH + ', user-scalable=yes';

              window.__btechViewportTargetWidth = TARGET_WIDTH;
              window.__btechViewportEnabled = !!ENABLED;
              window.__btechViewportFallbackApplied = !!IS_FALLBACK || !!window.__btechViewportFallbackApplied;
              if (!IS_FALLBACK && ENABLED) {
                window.__btechViewportDocStartActive = true;
              }

              function disconnectExistingObserver() {
                try {
                  if (window.__btechViewportObserver && typeof window.__btechViewportObserver.disconnect === 'function') {
                    window.__btechViewportObserver.disconnect();
                  }
                } catch (_) {}
                window.__btechViewportObserver = null;
              }

              if (!ENABLED) {
                disconnectExistingObserver();
                window.__btechViewportEnforced = false;
                return false;
              }

              function isViewportMeta(node) {
                if (!node || String(node.tagName || '').toUpperCase() !== 'META') return false;
                const name = String((node.getAttribute && node.getAttribute('name')) || node.name || '').trim().toLowerCase();
                return name === 'viewport';
              }

              function applyViewportTag() {
                if (typeof document === 'undefined') return false;
                window.__btechApplyingViewport = true;
                let changed = false;
                try {
                  const head = document.head || (document.querySelector ? document.querySelector('head') : null) || document.documentElement;
                  const metas = document.querySelectorAll ? Array.from(document.querySelectorAll('meta')) : [];
                  const vpMetas = metas.filter(isViewportMeta);
                  if (vpMetas.length === 0) {
                    if (head && typeof document.createElement === 'function') {
                      const meta = document.createElement('meta');
                      meta.setAttribute('name', 'viewport');
                      meta.setAttribute('content', DESKTOP_CONTENT);
                      if (typeof head.prepend === 'function') {
                        head.prepend(meta);
                      } else if (typeof head.appendChild === 'function') {
                        head.appendChild(meta);
                      }
                      changed = true;
                    }
                  } else {
                    for (let i = 0; i < vpMetas.length; i++) {
                      const m = vpMetas[i];
                      const prev = String((m.getAttribute && m.getAttribute('content')) || m.content || '').trim();
                      if (prev !== DESKTOP_CONTENT) {
                        if (typeof m.setAttribute === 'function') {
                          m.setAttribute('content', DESKTOP_CONTENT);
                        } else {
                          m.content = DESKTOP_CONTENT;
                        }
                        changed = true;
                      }
                    }
                  }
                  window.__btechViewportEnforced = true;
                  if (changed && typeof window.dispatchEvent === 'function' && typeof Event === 'function') {
                    try { window.dispatchEvent(new Event('resize')); } catch (_) {}
                  }
                } catch (_) {
                } finally {
                  window.__btechApplyingViewport = false;
                }
                return changed;
              }

              // Intercept programmatic setAttribute('content', ...) / meta.content mutations before layout settles
              if (!window.__btechViewportProtoPatched && typeof HTMLMetaElement !== 'undefined' && HTMLMetaElement.prototype) {
                window.__btechViewportProtoPatched = true;
                try {
                  const origSetAttr = HTMLMetaElement.prototype.setAttribute;
                  HTMLMetaElement.prototype.setAttribute = function(attrName, attrVal) {
                    if (!window.__btechApplyingViewport && window.__btechViewportEnabled &&
                        String(attrName || '').toLowerCase() === 'content' && isViewportMeta(this)) {
                      return origSetAttr.call(this, attrName, DESKTOP_CONTENT);
                    }
                    const res = origSetAttr.call(this, attrName, attrVal);
                    if (!window.__btechApplyingViewport && window.__btechViewportEnabled &&
                        String(attrName || '').toLowerCase() === 'name' &&
                        String(attrVal || '').trim().toLowerCase() === 'viewport') {
                      origSetAttr.call(this, 'content', DESKTOP_CONTENT);
                    }
                    return res;
                  };
                  const contentDesc = Object.getOwnPropertyDescriptor(HTMLMetaElement.prototype, 'content');
                  if (contentDesc && contentDesc.set) {
                    const origSetter = contentDesc.set;
                    Object.defineProperty(HTMLMetaElement.prototype, 'content', {
                      configurable: true,
                      enumerable: contentDesc.enumerable,
                      get: contentDesc.get,
                      set: function(val) {
                        if (!window.__btechApplyingViewport && window.__btechViewportEnabled && isViewportMeta(this)) {
                          return origSetter.call(this, DESKTOP_CONTENT);
                        }
                        return origSetter.call(this, val);
                      }
                    });
                  }
                } catch (_) {}
              }

              applyViewportTag();

              if (typeof MutationObserver === 'function' && document.documentElement) {
                disconnectExistingObserver();
                try {
                  const observer = new MutationObserver(function(mutations) {
                    if (window.__btechApplyingViewport || !window.__btechViewportEnabled) return;
                    let needsReapply = false;
                    for (let i = 0; i < mutations.length && !needsReapply; i++) {
                      const mut = mutations[i];
                      if (mut.type === 'attributes' && isViewportMeta(mut.target)) {
                        needsReapply = true;
                      } else if (mut.type === 'childList') {
                        if (mut.addedNodes) {
                          for (let j = 0; j < mut.addedNodes.length; j++) {
                            const n = mut.addedNodes[j];
                            if (isViewportMeta(n) || (n.querySelectorAll && n.querySelectorAll('meta').length > 0)) {
                              needsReapply = true;
                              break;
                            }
                          }
                        }
                        if (!needsReapply && mut.removedNodes) {
                          for (let k = 0; k < mut.removedNodes.length; k++) {
                            if (isViewportMeta(mut.removedNodes[k])) {
                              needsReapply = true;
                              break;
                            }
                          }
                        }
                      }
                    }
                    if (needsReapply) applyViewportTag();
                  });
                  observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['name', 'content']
                  });
                  window.__btechViewportObserver = observer;
                } catch (_) {}
              }

              if (typeof document.addEventListener === 'function' && !window.__btechViewportDomListenerAdded) {
                window.__btechViewportDomListenerAdded = true;
                document.addEventListener('DOMContentLoaded', function() {
                  if (window.__btechViewportEnabled) applyViewportTag();
                });
              }

              return true;
            })();
        """.trimIndent()
    }

    /**
     * Collects measured CSS layout viewport diagnostics directly from the live DOM.
     * Never relies on screen.width alone since physical screen width can remain phone-sized.
     */
    fun buildDesktopViewportDiagnosticsScript(
        targetWidthPx: Int = 1366,
        enforcementActive: Boolean = true,
        documentStartSupported: Boolean = false,
        fallbackApplied: Boolean = false,
    ): String {
        val clampedWidth = targetWidthPx.coerceIn(1024, 2560)
        return """
            (function() {
              try {
                const metas = document.querySelectorAll ? Array.from(document.querySelectorAll('meta')) : [];
                const vpContents = metas
                  .filter(function(m) {
                    return String((m.getAttribute && m.getAttribute('name')) || m.name || '').trim().toLowerCase() === 'viewport';
                  })
                  .map(function(m) {
                    return String((m.getAttribute && m.getAttribute('content')) || m.content || '').trim();
                  })
                  .filter(Boolean);
                const metaViewport = vpContents.join(' | ');
                const innerW = Number((typeof window !== 'undefined' && window.innerWidth) || 0);
                const clientW = Number((typeof document !== 'undefined' && document.documentElement && document.documentElement.clientWidth) || 0);
                const visualW = Number((typeof window !== 'undefined' && window.visualViewport && window.visualViewport.width) || 0);
                const innerH = Number((typeof window !== 'undefined' && window.innerHeight) || 0);
                const ua = String((typeof navigator !== 'undefined' && navigator.userAgent) || '');
                const url = String((typeof window !== 'undefined' && window.location && window.location.href) || '');
                const targetW = $clampedWidth;
                const widthToken = 'width=' + targetW;
                const enforcedFlag = !!$enforcementActive && (
                  !!window.__btechViewportEnforced ||
                  metaViewport.indexOf(widthToken) >= 0 ||
                  innerW >= Math.max(980, targetW - 80) ||
                  clientW >= Math.max(980, targetW - 80)
                );
                return JSON.stringify({
                  innerWidth: innerW,
                  clientWidth: clientW,
                  visualViewportWidth: visualW,
                  innerHeight: innerH,
                  metaViewport: metaViewport,
                  userAgent: ua,
                  url: url,
                  targetWidth: targetW,
                  viewportEnforced: enforcedFlag,
                  layoutIsDesktopWidth: innerW >= 1024 || clientW >= 1024,
                  documentStartSupported: !!$documentStartSupported || !!window.__btechViewportDocStartActive,
                  fallbackApplied: !!$fallbackApplied || !!window.__btechViewportFallbackApplied
                });
              } catch (err) {
                return JSON.stringify({
                  innerWidth: 0,
                  clientWidth: 0,
                  visualViewportWidth: 0,
                  innerHeight: 0,
                  metaViewport: '',
                  userAgent: '',
                  url: String((typeof window !== 'undefined' && window.location && window.location.href) || ''),
                  targetWidth: $clampedWidth,
                  viewportEnforced: false,
                  layoutIsDesktopWidth: false,
                  documentStartSupported: !!$documentStartSupported,
                  fallbackApplied: !!$fallbackApplied,
                  error: String(err && err.message ? err.message : err)
                });
              }
            })();
        """.trimIndent()
    }

    private fun buildSharedPlatformDiscoveryRoutines(
        pollDelayExpr: String,
        withPhases: Boolean,
        includeElementRef: Boolean,
    ): String {
        val phaseCall = if (withPhases) {
            "setPhase"
        } else {
            "(function(){})"
        }
        val mt5ElementLine = if (includeElementRef) "element: selectedRow," else ""
        val mt4ElementLine = if (includeElementRef) "element: result.element," else ""

        return """
              function hasMt5NoSymbolsFound() {
                const deepTxt = collectDeepText();
                if (!deepTxt || deepTxt.indexOf('No symbols found') < 0) return false;
                const hasSpinner = deepAll('[role="progressbar"], [class*="spinner"], [class*="loader"], [class*="loading"]').some(isRenderedVisible);
                if (hasSpinner) return false;
                return deepAll('div, span, p, td, li').some(function(node) {
                  if (node.children && node.children.length > 3) return false;
                  if (!isRenderedVisible(node)) return false;
                  const txt = (node.innerText || node.textContent || '').replace(/\s+/g, ' ').trim();
                  return txt === 'No symbols found' || (txt.length <= 80 && txt.includes('No symbols found'));
                });
              }

              async function waitForSymbol(targetSym, timeoutMs, scopeRoot, aliases, stopOnNoSymbols) {
                const start = Date.now();
                let seenNoSymbolsAt = 0;
                const pollMs = Number($pollDelayExpr) || 250;
                while (Date.now() - start < timeoutMs) {
                  if (isCancelled()) return { cancelled: true };
                  const hit = findSymbolElement(targetSym, scopeRoot, aliases);
                  if (hit) return hit;
                  if (stopOnNoSymbols && hasMt5NoSymbolsFound()) {
                    if (!seenNoSymbolsAt) seenNoSymbolsAt = Date.now();
                    if (Date.now() - seenNoSymbolsAt >= Math.min(200, pollMs)) {
                      return { noSymbolsFound: true };
                    }
                  } else {
                    seenNoSymbolsAt = 0;
                  }
                  await wait(pollMs);
                }
                return null;
              }

              function findMT5SearchFieldWithLabelSupport() {
                const searchSelectors = [
                  "input[placeholder='Search symbol']",
                  "input[placeholder*='Search symbol' i]",
                  "input[aria-label*='Search symbol' i]",
                  "input[placeholder*='search' i]",
                  "input[aria-label*='search' i]",
                  "input[type='search']",
                  "input[name*='search' i]",
                  "input[id*='search' i]"
                ];
                for (let i = 0; i < searchSelectors.length; i++) {
                  const hit = deepAll(searchSelectors[i]).find(isVisible);
                  if (hit) return hit;
                }
                const allInputs = deepAll('input').filter(isVisible);
                const labels = deepAll('label').filter(isRenderedVisible);
                for (let i = 0; i < labels.length; i++) {
                  const lbl = labels[i];
                  const lt = (lbl.innerText || lbl.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  if (!lt || (!lt.includes('search symbol') && !lt.includes('search') && lt !== 'symbol' && lt !== 'instrument')) continue;
                  const forId = lbl.getAttribute ? (lbl.getAttribute('for') || '') : '';
                  if (forId) {
                    const matchedByFor = allInputs.find(function(inp) { return inp.id === forId; });
                    if (matchedByFor) return matchedByFor;
                  }
                  if (lbl.querySelector) {
                    const nested = Array.from(lbl.querySelectorAll('input')).find(isVisible);
                    if (nested) return nested;
                  }
                  if (lbl.parentElement && lbl.parentElement.querySelectorAll) {
                    const sib = Array.from(lbl.parentElement.querySelectorAll('input')).find(isVisible);
                    if (sib) return sib;
                  }
                }
                return null;
              }

              function buildMt5MissingSearchDiagnostic() {
                try {
                  const url = String((window.location && window.location.href) || '');
                  const title = String((typeof document !== 'undefined' && document.title) || '');
                  const visibleIns = deepAll('input').filter(isVisible).slice(0, 6).map(function(i) {
                    return (i.getAttribute('placeholder') || i.getAttribute('aria-label') || i.name || i.id || i.type || 'input');
                  });
                  const visibleBtns = deepAll('button, [role="button"], [role="tab"]').filter(isVisible).slice(0, 8).map(function(b) {
                    return (b.innerText || b.textContent || b.getAttribute('aria-label') || b.getAttribute('title') || '').replace(/\s+/g, ' ').trim();
                  }).filter(Boolean);
                  return 'MT5 symbol search field not found [url=' + url + ', title=' + title +
                    ', inputs=' + (visibleIns.join('|') || 'none') +
                    ', controls=' + (visibleBtns.join('|') || 'none') + ']';
                } catch (_) {
                  return 'MT5 symbol search field not found';
                }
              }

              function maybeDispatchEnter(el) {
                if (!el) return;
                try {
                  const view = getViewFor(el);
                  ['keydown', 'keypress', 'keyup'].forEach(function(ev) {
                    el.dispatchEvent(new view.KeyboardEvent(ev, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true }));
                  });
                } catch (_) {}
              }

              async function discoverMT5Symbol(targetSymbol) {
                const reportPhase = $phaseCall;
                const pollMs = Number($pollDelayExpr) || 250;
                const requested = String(targetSymbol || '').trim();
                if (!requested) {
                  return { success: false, platform: 'MT5', error: 'No symbol was supplied', discoveredSymbols: [] };
                }

                let searchInput = findMT5SearchFieldWithLabelSupport();
                if (!searchInput) {
                  reportPhase('SYMBOL_SEARCHING', 'Locating MT5 Search symbol input for ' + requested + '...');
                  const waitSearchStart = Date.now();
                  let toggledQuotes = false;
                  while (!searchInput && Date.now() - waitSearchStart < 1800) {
                    if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                    if (!toggledQuotes) {
                      const quotesBtn = deepAll('button, [role="button"], [role="tab"], a').filter(isVisible).find(function(b) {
                        const t = ((b.innerText || b.textContent || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                        return t.includes('quotes') || t.includes('market watch') || t.includes('search symbol') || t.includes('add symbol') || t === 'symbols';
                      });
                      if (quotesBtn) {
                        singleClick(quotesBtn);
                        toggledQuotes = true;
                      }
                    }
                    await wait(Math.min(250, pollMs + 100));
                    searchInput = findMT5SearchFieldWithLabelSupport();
                  }
                }

                if (!searchInput) {
                  return {
                    success: false,
                    platform: 'MT5',
                    searchFieldMissing: true,
                    requestedSymbol: requested,
                    error: buildMt5MissingSearchDiagnostic(),
                    discoveredSymbols: discoverAllBrokerSymbols()
                  };
                }

                if (searchInput.value && normalise(searchInput.value) !== normalise(requested)) {
                  clearMt5SearchBox(searchInput);
                  await wait(Math.min(120, pollMs));
                  if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                }

                reportPhase('SYMBOL_SEARCHING', 'Searching ' + requested + ' in MT5 Quotes...');
                if (!setNativeInputValue(searchInput, requested)) {
                  return {
                    success: false,
                    platform: 'MT5',
                    requestedSymbol: requested,
                    error: 'Could not enter symbol in MT5 search field',
                    discoveredSymbols: discoverAllBrokerSymbols()
                  };
                }
                maybeDispatchEnter(searchInput);

                let result = await waitForSymbol(requested, 2200, null, VERIFIED_ALIASES, true);
                if (result && result.cancelled) {
                  return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                }
                if (result && result.noSymbolsFound) result = null;

                if (!result && VERIFIED_ALIASES && typeof VERIFIED_ALIASES === 'object') {
                  const aliasKey = Object.keys(VERIFIED_ALIASES).find(function(k) {
                    return normalise(k) === normalise(requested);
                  });
                  const aliasTarget = aliasKey ? String(VERIFIED_ALIASES[aliasKey] || '').trim() : '';
                  if (aliasTarget && normalise(aliasTarget) !== normalise(requested)) {
                    reportPhase('SYMBOL_SEARCHING', 'Checking verified alias ' + aliasTarget + ' on MT5...');
                    setNativeInputValue(searchInput, aliasTarget);
                    maybeDispatchEnter(searchInput);
                    result = await waitForSymbol(requested, 1600, null, VERIFIED_ALIASES, true);
                    if (result && result.cancelled) {
                      return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                    }
                    if (result && result.noSymbolsFound) result = null;
                  }
                }

                if (!result) {
                  return {
                    success: false,
                    platform: 'MT5',
                    requestedSymbol: requested,
                    error: 'error:Symbol ' + requested + ' not found',
                    discoveredSymbols: discoverAllBrokerSymbols()
                  };
                }

                reportPhase('SYMBOL_SEARCHING', 'Verifying ' + result.actualSymbol + ' in MT5 Quotes...');
                const addAction = inspectMt5SearchResultAction(result.element);
                const hasClearBtn = !!findMt5ClearSearchButton(searchInput);
                let addedViaPlus = false;
                let alreadyChecked = false;

                if (addAction.state === 'PLUS' && addAction.control) {
                  singleClick(addAction.control);
                  addedViaPlus = true;
                  await wait(Math.min(350, pollMs * 2 + 120));
                  if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                } else if (addAction.state === 'CHECK') {
                  alreadyChecked = true;
                }

                let selectedRow = result.element;
                let confirmedInWatchlist = false;
                if (addAction.state !== 'NONE' || hasClearBtn) {
                  clearMt5SearchBox(searchInput);
                  await wait(Math.min(300, pollMs * 2 + 100));
                  if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };

                  let watchlistHit = findMt5DisplayedWatchlistRow(result.actualSymbol, VERIFIED_ALIASES);
                  const wStart = Date.now();
                  while (!watchlistHit && Date.now() - wStart < 1200) {
                    if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                    await wait(pollMs);
                    watchlistHit = findMt5DisplayedWatchlistRow(result.actualSymbol, VERIFIED_ALIASES);
                  }
                  if (!watchlistHit) {
                    const scrollers = findMt5WatchlistScrollContainers(searchInput);
                    for (let sIdx = 0; sIdx < scrollers.length && !watchlistHit; sIdx++) {
                      const box = scrollers[sIdx];
                      const prevTop = box.scrollTop || 0;
                      const stepPx = Math.max(160, (box.clientHeight || 240) * 0.75);
                      for (let step = 0; step < 5 && !watchlistHit; step++) {
                        box.scrollTop = (step + 1) * stepPx;
                        await wait(Math.min(100, pollMs));
                        if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                        watchlistHit = findMt5DisplayedWatchlistRow(result.actualSymbol, VERIFIED_ALIASES);
                      }
                      if (!watchlistHit) box.scrollTop = prevTop;
                    }
                  }

                  if (watchlistHit) {
                    selectedRow = watchlistHit.element;
                    confirmedInWatchlist = true;
                  } else {
                    reportPhase('SYMBOL_SEARCHING', 'Selecting ' + result.actualSymbol + ' from MT5 search...');
                    setNativeInputValue(searchInput, result.actualSymbol);
                    maybeDispatchEnter(searchInput);
                    let retryHit = await waitForSymbol(result.actualSymbol, 1600, null, VERIFIED_ALIASES, true);
                    if (retryHit && retryHit.cancelled) {
                      return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                    }
                    if (!retryHit || retryHit.noSymbolsFound) {
                      return {
                        success: false,
                        platform: 'MT5',
                        requestedSymbol: requested,
                        actualSymbol: result.actualSymbol,
                        error: 'MT5 symbol ' + result.actualSymbol + ' not present in displayed watchlist or search results',
                        discoveredSymbols: discoverAllBrokerSymbols()
                      };
                    }
                    const retryAdd = inspectMt5SearchResultAction(retryHit.element);
                    const iconToClick = !addedViaPlus
                      ? ((retryAdd.state === 'PLUS' && retryAdd.control)
                          ? retryAdd.control
                          : (retryAdd.state === 'NONE' ? retryAdd.fallbackControl : null))
                      : null;
                    if (iconToClick) {
                      singleClick(iconToClick);
                      addedViaPlus = true;
                      await wait(Math.min(300, pollMs * 2 + 100));
                      if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                      const refreshedHit = findSymbolElement(result.actualSymbol, null, VERIFIED_ALIASES);
                      selectedRow = (refreshedHit && refreshedHit.element) || retryHit.element;
                    } else {
                      selectedRow = retryHit.element;
                    }
                  }
                }

                const clicked = clickElement(selectedRow);
                if (!clicked) {
                  return {
                    success: false,
                    platform: 'MT5',
                    requestedSymbol: requested,
                    actualSymbol: result.actualSymbol,
                    error: 'MT5 symbol result could not be selected',
                    discoveredSymbols: discoverAllBrokerSymbols()
                  };
                }

                await wait(Math.min(250, pollMs + 100));
                if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };

                const chartState = verifyMt5ActiveChartSymbol(result.actualSymbol, VERIFIED_ALIASES);
                if (chartState.state === 'MISMATCH') {
                  clickElement(selectedRow);
                  await wait(Math.min(250, pollMs + 100));
                  if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                  const retryChart = verifyMt5ActiveChartSymbol(result.actualSymbol, VERIFIED_ALIASES);
                  if (retryChart.state === 'MISMATCH') {
                    return {
                      success: false,
                      platform: 'MT5',
                      requestedSymbol: requested,
                      actualSymbol: result.actualSymbol,
                      error: 'MT5 active chart stayed on ' + retryChart.symbol + ' instead of ' + result.actualSymbol,
                      discoveredSymbols: discoverAllBrokerSymbols()
                    };
                  }
                }

                return {
                  success: true,
                  platform: 'MT5',
                  broker: BROKER,
                  server: SERVER,
                  requestedSymbol: requested,
                  actualSymbol: result.actualSymbol,
                  matchRank: result.matchRank,
                  matchType: result.matchType,
                  addedViaPlus: addedViaPlus,
                  alreadyChecked: alreadyChecked,
                  confirmedInWatchlist: confirmedInWatchlist,
                  resultClicked: true,
                  $mt5ElementLine
                  discoveredSymbols: discoverAllBrokerSymbols(),
                  message: 'Matching MT5 symbol verified and selected before trading'
                };
              }

              function hasVisibleMT4ContextMenu() {
                return deepAll(".page-menu, .mt4-ctx-menu, [role='menu'], [class*='context-menu' i], [class*='ctx-menu' i], [class*='popup-menu' i]")
                  .some(isRenderedVisible);
              }

              async function pollForMT4DiscoveryMenuItems(waitFn, stepMs, maxWaitMs) {
                const start = Date.now();
                let showAll = findMT4ShowAllMenuItem();
                let symbolsItem = findMT4SymbolsMenuItem();
                const noMenuAbortMs = Math.min(140, maxWaitMs);
                while (!showAll && !symbolsItem && Date.now() - start < maxWaitMs) {
                  if (isCancelled()) return { showAll: null, symbolsItem: null };
                  if (!hasVisibleMT4ContextMenu() && Date.now() - start >= noMenuAbortMs) {
                    break;
                  }
                  await waitFn(stepMs);
                  showAll = findMT4ShowAllMenuItem();
                  symbolsItem = findMT4SymbolsMenuItem();
                }
                return { showAll: showAll, symbolsItem: symbolsItem };
              }

              function findMT4MarketWatchScrollContainers(marketWatchPanel) {
                if (!marketWatchPanel) return [];
                const seen = new Set();
                const out = [];
                function addIfScrollable(el) {
                  if (!el || seen.has(el) || !isRenderedVisible(el)) return;
                  try {
                    if (el.scrollHeight > (el.clientHeight || 0) + 16) {
                      seen.add(el);
                      out.push(el);
                    }
                  } catch (_) {}
                }
                const prioritized = [
                  'div.page-block > div',
                  '.page-block',
                  'tbody',
                  'table',
                  "[role='grid']",
                  "[role='listbox']"
                ];
                for (let p = 0; p < prioritized.length; p++) {
                  deepAll(prioritized[p], marketWatchPanel).forEach(addIfScrollable);
                }
                addIfScrollable(marketWatchPanel);
                deepAll('div', marketWatchPanel).forEach(addIfScrollable);
                return out;
              }

              function mergeDiscoveredSymbols(targetList, seenLower, additions) {
                if (!Array.isArray(additions)) return;
                for (let i = 0; i < additions.length; i++) {
                  const sym = String(additions[i] || '').trim();
                  if (!sym) continue;
                  const k = sym.toLowerCase();
                  if (!seenLower.has(k)) {
                    seenLower.add(k);
                    targetList.push(sym);
                  }
                }
              }

              async function scanMT4MarketWatchWithScroll(requested, marketWatchPanel, verifiedAliases, waitFn, pollMs, collectedList, seenLower) {
                if (!marketWatchPanel) return null;
                mergeDiscoveredSymbols(collectedList, seenLower, discoverAllBrokerSymbols(marketWatchPanel));
                let hit = findMT4MarketWatchSymbolRow(requested, marketWatchPanel, verifiedAliases);
                if (hit) return hit;

                const scrollers = findMT4MarketWatchScrollContainers(marketWatchPanel);
                for (let s = 0; s < Math.min(2, scrollers.length); s++) {
                  const box = scrollers[s];
                  const origTop = box.scrollTop || 0;
                  const maxTop = Math.max(0, (box.scrollHeight || 0) - (box.clientHeight || 0));
                  if (maxTop <= 16) continue;
                  const stepPx = Math.max(120, Math.floor((box.clientHeight || 240) * 0.75));
                  for (let nextTop = stepPx; nextTop <= maxTop + stepPx; nextTop += stepPx) {
                    if (isCancelled()) return null;
                    box.scrollTop = Math.min(maxTop, nextTop);
                    try { box.dispatchEvent(new Event('scroll', { bubbles: false })); } catch (_) {}
                    await waitFn(Math.min(70, Math.max(10, Math.floor((pollMs || 250) / 3))));
                    mergeDiscoveredSymbols(collectedList, seenLower, discoverAllBrokerSymbols(marketWatchPanel));
                    hit = findMT4MarketWatchSymbolRow(requested, marketWatchPanel, verifiedAliases);
                    if (hit) {
                      return hit;
                    }
                    if (nextTop >= maxTop) break;
                  }
                  box.scrollTop = origTop;
                }
                return null;
              }

              async function openMT4DiscoveryContextMenu(marketWatchPanel, waitFn, pollMs) {
                const menuWaitMs = Math.min(420, (pollMs || 250) + 160);
                const stepMs = Math.min(70, Math.max(15, Math.floor((pollMs || 250) / 3)));

                const targets = [];
                const seenTargetKeys = new Set();
                function pushTarget(el, selectFirst, cx, cy, tag) {
                  if (!el || !isRenderedVisible(el) || isInsideMenuContainer(el)) return;
                  const key = (tag || '') + ':' + targets.length + ':' + (cx || '') + ':' + (cy || '');
                  if (!tag) {
                    for (let k = 0; k < targets.length; k++) {
                      if (targets[k].el === el && targets[k].cx === cx && targets[k].cy === cy) return;
                    }
                  }
                  if (seenTargetKeys.has(key)) return;
                  seenTargetKeys.add(key);
                  targets.push({ el: el, selectFirst: !!selectFirst, cx: cx, cy: cy });
                }

                const sampleRows = findVisibleMarketWatchSymbolRows(marketWatchPanel);

                // 1. Right-click inside Market Watch space below the last symbol row (when empty space exists below last row)
                if (sampleRows.length > 0) {
                  try {
                    const lastRow = sampleRows[sampleRows.length - 1];
                    const lastRect = lastRow.getBoundingClientRect ? lastRow.getBoundingClientRect() : null;
                    const boxEl = deepAll('div.page-block > div, .page-block', marketWatchPanel).find(isRenderedVisible) || marketWatchPanel;
                    const boxRect = boxEl.getBoundingClientRect ? boxEl.getBoundingClientRect() : null;
                    if (lastRect && boxRect && boxRect.bottom > lastRect.bottom + 14) {
                      const belowX = boxRect.left + Math.max(12, Math.min(boxRect.width / 2, 48));
                      const belowY = Math.min(boxRect.bottom - 10, lastRect.bottom + 24);
                      pushTarget(boxEl, false, belowX, belowY, 'below-last-row');
                    }
                  } catch (_) {}
                }

                // 2. Selector-ordered Market Watch quote tables (never document-order .b/.page-block ahead of tbody/table!)
                const tableBodySelectors = [
                  'div.page-block > div > table > tbody',
                  'tbody',
                  "[role='rowgroup']",
                  'table',
                  "[role='grid']",
                  "[role='listbox']"
                ];
                for (let s = 0; s < tableBodySelectors.length; s++) {
                  const matches = deepAll(tableBodySelectors[s], marketWatchPanel);
                  for (let m = 0; m < matches.length; m++) {
                    if (matches[m] !== marketWatchPanel && !isNonMarketWatchQuoteContext(matches[m])) {
                      pushTarget(matches[m], false);
                      break;
                    }
                  }
                }

                // 3. Market Watch inner wrappers & panel container (before falling back to clicking/right-clicking symbol rows)
                deepAll('.page-block, .b', marketWatchPanel).forEach(function(el) {
                  if (el !== marketWatchPanel) pushTarget(el, false);
                });
                pushTarget(marketWatchPanel, false);

                // 4. Fallback: visible Market Watch symbol rows (direct right-click first, then select-then-right-click; Show All / Symbols ONLY, NEVER New Order)
                const fallbackRows = [];
                if (sampleRows.length > 0) fallbackRows.push(sampleRows[0]);
                if (sampleRows.length > 1) fallbackRows.push(sampleRows[sampleRows.length - 1]);
                for (let r = 0; r < fallbackRows.length; r++) {
                  const row = fallbackRows[r];
                  pushTarget(row, false);
                  const firstCell = row.querySelector ? row.querySelector('td') : null;
                  if (firstCell && firstCell !== row) {
                    pushTarget(firstCell, false);
                  }
                }
                if (sampleRows.length > 0) {
                  pushTarget(sampleRows[0], true);
                }

                let found = { showAll: null, symbolsItem: null };
                for (let i = 0; i < targets.length; i++) {
                  if (isCancelled()) return found;
                  const entry = targets[i];
                  if (entry.selectFirst) {
                    clickElement(entry.el);
                    await waitFn(Math.min(100, stepMs + 20));
                  }
                  dispatchMT4RightClick(entry.el, entry.cx, entry.cy);
                  found = await pollForMT4DiscoveryMenuItems(waitFn, stepMs, menuWaitMs);
                  if (found.showAll || found.symbolsItem) return found;
                }
                return found;
              }

              // --- SEPARATE MT4 DISCOVERY (Market Watch -> panel/row context menu -> Show All prioritised ahead of Symbols... -> row context menu New Order) ---
              // NEVER falls back to MT5 search-field method and NEVER depends on double-clicking!
              async function discoverMT4Symbol(targetSymbol) {
                const reportPhase = $phaseCall;
                const pollMs = Number($pollDelayExpr) || 250;
                const requested = String(targetSymbol || '').trim();
                if (!requested) {
                  return { success: false, platform: 'MT4', error: 'No symbol was supplied', discoveredSymbols: [] };
                }

                reportPhase('SYMBOL_SEARCHING', 'Scanning MT4 Market Watch for ' + requested + '...');
                let marketWatchPanel = await openHiddenMarketWatch(wait, pollMs);
                if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };

                const collectedSymbols = [];
                const seenSymbolsLower = new Set();

                let result = await scanMT4MarketWatchWithScroll(
                  requested, marketWatchPanel, VERIFIED_ALIASES, wait, pollMs, collectedSymbols, seenSymbolsLower
                );
                let usedShowAll = false;
                let usedSymbolsDialog = false;

                if (!result) {
                  if (!marketWatchPanel) {
                    return {
                      success: false,
                      platform: 'MT4',
                      requestedSymbol: requested,
                      error: 'MT4 Market Watch panel not found',
                      discoveredSymbols: discoverAllBrokerSymbols()
                    };
                  }

                  const initialCount = collectedSymbols.length;
                  reportPhase('SYMBOL_SEARCHING', 'Opening MT4 Market Watch menu (Show All) for ' + requested + '...');
                  const menuHits = await openMT4DiscoveryContextMenu(marketWatchPanel, wait, pollMs);
                  if (isCancelled()) {
                    return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };
                  }

                  const showAll = menuHits.showAll;
                  const symbolsItem = menuHits.symbolsItem;

                  // Part 6 requirement: prioritise Show All ahead of Symbols... when missing symbol workflow runs!
                  if (showAll) {
                    usedShowAll = true;
                    if (!clickMT4ShowAllMenuItem(showAll)) {
                      return {
                        success: false,
                        platform: 'MT4',
                        requestedSymbol: requested,
                        error: 'Could not activate MT4 Show All',
                        discoveredSymbols: collectedSymbols.length ? collectedSymbols : discoverAllBrokerSymbols(marketWatchPanel)
                      };
                    }
                    await wait(Math.min(400, pollMs * 2 + 100));
                    if (showAll.isConnected && isRenderedVisible(showAll)) {
                      clickMT4ShowAllMenuItem(showAll);
                      await wait(Math.min(250, pollMs + 60));
                    }

                    const mwStart = Date.now();
                    let lastPhaseRefresh = mwStart;
                    let didScrollScanAfterGrowth = false;
                    let lastElapsedScrollCheck = 0;
                    while (Date.now() - mwStart < 5500) {
                      if (isCancelled()) {
                        return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };
                      }
                      result = findMT4MarketWatchSymbolRow(requested, marketWatchPanel, VERIFIED_ALIASES);
                      if (result) break;
                      mergeDiscoveredSymbols(collectedSymbols, seenSymbolsLower, discoverAllBrokerSymbols(marketWatchPanel));
                      const elapsed = Date.now() - mwStart;
                      const scrollers = findMT4MarketWatchScrollContainers(marketWatchPanel);
                      const hasScrollableGrowth = scrollers.length > 0 && (!didScrollScanAfterGrowth || elapsed - lastElapsedScrollCheck >= 1600);
                      if ((collectedSymbols.length > initialCount && !didScrollScanAfterGrowth) || (hasScrollableGrowth && elapsed >= 900)) {
                        didScrollScanAfterGrowth = true;
                        lastElapsedScrollCheck = elapsed;
                        result = await scanMT4MarketWatchWithScroll(
                          requested, marketWatchPanel, VERIFIED_ALIASES, wait, pollMs, collectedSymbols, seenSymbolsLower
                        );
                        if (result) break;
                      }
                      if (elapsed - lastPhaseRefresh >= 2000) {
                        lastPhaseRefresh = Date.now();
                        reportPhase('SYMBOL_SEARCHING', 'Scanning full MT4 Market Watch list for ' + requested + ' (' + collectedSymbols.length + ' symbols)...');
                      }
                      await wait(pollMs);
                    }
                    if (!result) {
                      result = await scanMT4MarketWatchWithScroll(
                        requested, marketWatchPanel, VERIFIED_ALIASES, wait, pollMs, collectedSymbols, seenSymbolsLower
                      );
                    }
                  } else if (symbolsItem) {
                    usedSymbolsDialog = true;
                    if (!clickMT4MenuItem(symbolsItem)) {
                      return {
                        success: false,
                        platform: 'MT4',
                        requestedSymbol: requested,
                        error: 'Could not open MT4 Symbols command',
                        discoveredSymbols: collectedSymbols.length ? collectedSymbols : discoverAllBrokerSymbols(marketWatchPanel)
                      };
                    }
                    await wait(Math.min(300, pollMs + 80));
                    if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };

                    let dialog = findMT4SymbolsDialog();
                    const dStart = Date.now();
                    while (!dialog && Date.now() - dStart < 2000) {
                      if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };
                      await wait(pollMs);
                      dialog = findMT4SymbolsDialog();
                    }
                    if (dialog) {
                      for (let pass = 0; pass < 3; pass++) {
                        const toggled = expandMT4SymbolGroups(dialog);
                        if (!toggled) break;
                        await wait(Math.min(200, pollMs + 40));
                      }
                      if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };

                      const dlgHit = findSymbolElement(requested, dialog, VERIFIED_ALIASES);
                      if (dlgHit) {
                        singleClick(dlgHit.element);
                        await wait(Math.min(180, pollMs + 30));
                        if (!isMT4DialogRowAlreadyShown(dlgHit.element)) {
                          const showBtn = findExactVisibleText('Show', "button, [role='button']", dialog);
                          if (showBtn && !showBtn.disabled) {
                            singleClick(showBtn);
                          } else {
                            doubleClickMT4Row(dlgHit.element);
                          }
                          await wait(Math.min(240, pollMs + 40));
                        }
                      }
                      closeMT4SymbolsDialog(dialog);
                    }
                    await wait(Math.min(350, pollMs + 100));
                    const mwStart = Date.now();
                    while (Date.now() - mwStart < 2500) {
                      if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };
                      result = findMT4MarketWatchSymbolRow(requested, marketWatchPanel, VERIFIED_ALIASES);
                      if (result) break;
                      await wait(pollMs);
                    }
                    if (!result) {
                      result = await scanMT4MarketWatchWithScroll(
                        requested, marketWatchPanel, VERIFIED_ALIASES, wait, pollMs, collectedSymbols, seenSymbolsLower
                      );
                    }
                  } else {
                    return {
                      success: false,
                      platform: 'MT4',
                      requestedSymbol: requested,
                      error: 'MT4 Show All or Symbols menu option not found',
                      discoveredSymbols: collectedSymbols.length ? collectedSymbols : discoverAllBrokerSymbols(marketWatchPanel)
                    };
                  }

                  // If Show All was tried and still missing, check Symbols... dialog fallback once
                  if (!result && usedShowAll) {
                    const fbHits = await openMT4DiscoveryContextMenu(marketWatchPanel, wait, pollMs);
                    const fbSymItem = fbHits.symbolsItem || findMT4SymbolsMenuItem();
                    if (fbSymItem && clickMT4MenuItem(fbSymItem)) {
                      usedSymbolsDialog = true;
                      await wait(Math.min(300, pollMs + 80));
                      let dlg = findMT4SymbolsDialog();
                      if (dlg) {
                        for (let pass = 0; pass < 3; pass++) {
                          if (!expandMT4SymbolGroups(dlg)) break;
                          await wait(Math.min(180, pollMs));
                        }
                        const dlgHit = findSymbolElement(requested, dlg, VERIFIED_ALIASES);
                        if (dlgHit) {
                          singleClick(dlgHit.element);
                          const showBtn = findExactVisibleText('Show', "button, [role='button']", dlg);
                          if (showBtn && !showBtn.disabled) singleClick(showBtn);
                          else doubleClickMT4Row(dlgHit.element);
                          await wait(Math.min(200, pollMs));
                        }
                        closeMT4SymbolsDialog(dlg);
                      }
                      result = await scanMT4MarketWatchWithScroll(
                        requested, marketWatchPanel, VERIFIED_ALIASES, wait, pollMs, collectedSymbols, seenSymbolsLower
                      );
                    }
                  }
                }

                mergeDiscoveredSymbols(collectedSymbols, seenSymbolsLower, discoverAllBrokerSymbols(marketWatchPanel));

                if (!result) {
                  return {
                    success: false,
                    platform: 'MT4',
                    requestedSymbol: requested,
                    error: 'error:Symbol ' + requested + ' not found',
                    discoveredSymbols: collectedSymbols
                  };
                }

                try {
                  if (typeof result.element.scrollIntoView === 'function') {
                    result.element.scrollIntoView({ block: 'center', inline: 'nearest' });
                  }
                } catch (_) {}
                clickElement(result.element);

                return {
                  success: true,
                  platform: 'MT4',
                  broker: BROKER,
                  server: SERVER,
                  requestedSymbol: requested,
                  actualSymbol: result.actualSymbol,
                  matchRank: result.matchRank,
                  matchType: result.matchType,
                  usedShowAll: usedShowAll,
                  usedSymbolsDialog: usedSymbolsDialog,
                  $mt4ElementLine
                  discoveredSymbols: collectedSymbols,
                  message: 'Matching MT4 Market Watch row located; ready for row context-menu New Order'
                };
              }
        """.trimIndent()
    }

    /**
     * Generates standalone RoboTrader-style symbol discovery script for MT4 or MT5 without
     * interacting with any Buy/Sell controls. Useful for scanning all offered broker symbols
     * and testing MT4/MT5 discovery paths independently.
     */
    fun buildSymbolDiscoveryScript(
        platform: String,
        requestedSymbol: String,
        broker: String = "",
        server: String = "",
        verifiedAliasesJson: String = "{}",
        pollDelayMs: Int = 250,
    ): String {
        val safePlatform = JSONObject.quote(platform.uppercase())
        val safeSymbol = JSONObject.quote(requestedSymbol)
        val safeBroker = JSONObject.quote(broker)
        val safeServer = JSONObject.quote(server)
        val safeAliases = verifiedAliasesJson.ifBlank { "{}" }
        val safePollMs = pollDelayMs.coerceIn(5, 1000)

        return """
            (function() {
              'use strict';
              window.__btechTradeCancelled = false;
              const RUN_ID = (window.__btechTradeRunId || 0) + 1;
              window.__btechTradeRunId = RUN_ID;
              if (Array.isArray(window.__btechTradeTimers)) {
                window.__btechTradeTimers.forEach(function(id) { try { clearTimeout(id); } catch (_) {} });
              }
              window.__btechTradeTimers = [];
              if (Array.isArray(window.__btechTradeWaitResolvers)) {
                window.__btechTradeWaitResolvers.forEach(function(fn) { try { fn(); } catch (_) {} });
              }
              window.__btechTradeWaitResolvers = [];

              function isCancelled() {
                return window.__btechTradeCancelled === true || window.__btechTradeRunId !== RUN_ID;
              }

              function wait(ms) {
                return new Promise(function(resolve) {
                  if (isCancelled()) { resolve(); return; }
                  let settled = false;
                  function finish() {
                    if (settled) return;
                    settled = true;
                    resolve();
                  }
                  const id = setTimeout(finish, ms);
                  window.__btechTradeTimers.push(id);
                  window.__btechTradeWaitResolvers.push(finish);
                });
              }

              const PLATFORM = $safePlatform;
              const TARGET_SYMBOL = $safeSymbol;
              const BROKER = $safeBroker;
              const SERVER = $safeServer;
              const VERIFIED_ALIASES = $safeAliases;
              const POLL_MS = $safePollMs;

              $DEEP_DOM_HELPERS
              $ROBOTRADER_DISCOVERY_HELPERS
              ${buildSharedPlatformDiscoveryRoutines("POLL_MS", withPhases = false, includeElementRef = false)}

              window.BtechRoboTraderDiscovery = {
                normalise: normalise,
                stripKnownSuffix: stripKnownSuffix,
                matchRank: matchRank,
                extractSymbolTokens: extractSymbolTokens,
                detectLoadedTerminalPlatform: detectLoadedTerminalPlatform,
                detectPlatformMismatchError: detectPlatformMismatchError,
                findSymbolElement: findSymbolElement,
                waitForSymbol: waitForSymbol,
                discoverAllBrokerSymbols: discoverAllBrokerSymbols,
                findExactVisibleText: findExactVisibleText,
                inspectMt5SearchResultAction: inspectMt5SearchResultAction,
                findMT4SymbolsMenuItem: findMT4SymbolsMenuItem,
                findMT4ShowAllMenuItem: findMT4ShowAllMenuItem,
                findMT4NewOrderMenuItem: findMT4NewOrderMenuItem,
                openMT4ContextMenu: openMT4ContextMenu,
                openMT4RowContextMenu: openMT4RowContextMenu,
                findMarketWatchPanel: findMarketWatchPanel,
                openHiddenMarketWatch: openHiddenMarketWatch,
                doubleClickMT4Row: doubleClickMT4Row,
                discoverMT4Symbol: discoverMT4Symbol,
                discoverMT5Symbol: discoverMT5Symbol
              };

              async function run() {
                if (isCancelled()) return { success: false, cancelled: true };
                const mismatch = detectPlatformMismatchError(PLATFORM);
                if (mismatch) {
                  const errRes = { success: false, platform: PLATFORM, error: mismatch, discoveredSymbols: [] };
                  if (window.BtechBridge && typeof window.BtechBridge.onSymbolDiscovered === 'function') {
                    try { window.BtechBridge.onSymbolDiscovered(JSON.stringify(errRes)); } catch (_) {}
                  }
                  return errRes;
                }
                const res = PLATFORM === 'MT4'
                  ? await discoverMT4Symbol(TARGET_SYMBOL)
                  : await discoverMT5Symbol(TARGET_SYMBOL);
                if (!isCancelled() && window.BtechBridge && typeof window.BtechBridge.onSymbolDiscovered === 'function') {
                  try {
                    window.BtechBridge.onSymbolDiscovered(JSON.stringify(res));
                  } catch (_) {}
                }
                return res;
              }

              window.__btechLastDiscoveryPromise = run();
            })();
        """.trimIndent()
    }

    /**
     * Generates JS that traverses DOM/Shadow DOM/same-origin iframes to:
     * 1) Authenticate session state before discovery/execution,
     * 2) Run separate MT4 (Market Watch + contextmenu + Show All + doubleClickMT4Row) OR
     *    MT5 (Search Symbol input + click search result) discovery — NEVER falling MT4 back to MT5 search,
     * 3) Collect all visible broker symbols without artificial 8-symbol cap & report to Kotlin cache,
     * 4) Verify active platform, selected instrument matches expected broker symbol, BUY/SELL button,
     *    volume, SL, TP, and comment before submitting each order once.
     */
    fun buildTradeScript(
        symbol: String,
        direction: String,
        lot: String,
        stopLoss: String,
        takeProfit: String,
        comment: String,
        tradeCount: Int,
        platform: String = "MT5",
        broker: String = "",
        server: String = "",
        verifiedAliasesJson: String = "{}",
        signalId: String = "",
        confirmTimeoutPolls: Int = 60,
        accountLogin: String = "",
        kotlinRunId: Long = 1L,
    ): String {
        val safeSymbol = JSONObject.quote(symbol)
        val safeDir = JSONObject.quote(direction)
        val safeLot = JSONObject.quote(lot)
        val safeSl = JSONObject.quote(stopLoss)
        val safeTp = JSONObject.quote(takeProfit)
        val safeComment = JSONObject.quote(comment)
        val safePlatform = JSONObject.quote(platform.uppercase())
        val safeBroker = JSONObject.quote(broker)
        val safeServer = JSONObject.quote(server)
        val safeAliases = verifiedAliasesJson.ifBlank { "{}" }
        val safeSignalId = JSONObject.quote(signalId)
        val safeConfirmPolls = confirmTimeoutPolls.coerceIn(2, 120)
        val safeAccountLogin = JSONObject.quote(accountLogin)

        return """
            (function() {
              'use strict';
              const KOTLIN_RUN_ID = $kotlinRunId;
              window.__btechTradeCancelled = false;
              const RUN_ID = (window.__btechTradeRunId || 0) + 1;
              window.__btechTradeRunId = RUN_ID;
              window.__btechActiveKotlinRunId = KOTLIN_RUN_ID;
              window.__btechCurrentStage = 'SIGNAL';
              window.__btechDiscoveryRunning = false;
              window.__btechOrderTicketOpen = false;
              if (Array.isArray(window.__btechTradeTimers)) {
                window.__btechTradeTimers.forEach(function(id) { try { clearTimeout(id); } catch (_) {} });
              }
              window.__btechTradeTimers = [];
              if (Array.isArray(window.__btechTradeWaitResolvers)) {
                window.__btechTradeWaitResolvers.forEach(function(fn) { try { fn(); } catch (_) {} });
              }
              window.__btechTradeWaitResolvers = [];

              function isCancelled() {
                return window.__btechTradeCancelled === true || window.__btechTradeRunId !== RUN_ID;
              }

              function schedule(fn, delay) {
                const id = setTimeout(function() {
                  if (!isCancelled()) fn();
                }, delay);
                window.__btechTradeTimers.push(id);
                return id;
              }

              function wait(ms) {
                return new Promise(function(resolve) {
                  if (isCancelled()) { resolve(); return; }
                  let settled = false;
                  function finish() {
                    if (settled) return;
                    settled = true;
                    resolve();
                  }
                  const id = setTimeout(finish, ms);
                  window.__btechTradeTimers.push(id);
                  window.__btechTradeWaitResolvers.push(finish);
                });
              }

              const SYMBOL = $safeSymbol;
              const DIRECTION = $safeDir;
              const LOT = $safeLot;
              const SL = $safeSl;
              const TP = $safeTp;
              const COMMENT = $safeComment;
              const COUNT = $tradeCount;
              const PLATFORM = $safePlatform;
              const BROKER = $safeBroker;
              const SERVER = $safeServer;
              const VERIFIED_ALIASES = $safeAliases;
              const SIGNAL_ID = $safeSignalId;
              const MAX_CONFIRM_POLLS = $safeConfirmPolls;
              const ACCOUNT_LOGIN = $safeAccountLogin;
              const ACCOUNT_LOGIN_DIGITS = String(ACCOUNT_LOGIN || '').replace(/\D+/g, '');

              $DEEP_DOM_HELPERS
              $ROBOTRADER_DISCOVERY_HELPERS

              function setPhase(phaseKey, detail) {
                if (isCancelled()) return;
                const keyStr = String(phaseKey);
                window.__btechCurrentStage = keyStr;
                if (keyStr === 'SYMBOL_SEARCHING' || keyStr === 'SYMBOL' || keyStr === 'SYMBOL_SELECTING') {
                  window.__btechDiscoveryRunning = true;
                } else if (keyStr === 'SYMBOL_FOUND' || keyStr === 'SYMBOL_SELECTED' || keyStr === 'ERROR' || keyStr === 'DONE' || keyStr === 'UNKNOWN') {
                  window.__btechDiscoveryRunning = false;
                }
                if (keyStr.indexOf('PREPARE') === 0 || keyStr.indexOf('TRADE_') === 0 || keyStr === 'SUBMIT' || keyStr === 'CONFIRM') {
                  window.__btechOrderTicketOpen = true;
                } else if (keyStr === 'ERROR' || keyStr === 'DONE' || keyStr === 'UNKNOWN') {
                  window.__btechOrderTicketOpen = false;
                }
                if (window.BtechBridge) {
                  if (typeof window.BtechBridge.onTradePhaseForRun === 'function') {
                    window.BtechBridge.onTradePhaseForRun(KOTLIN_RUN_ID, keyStr, String(detail || ''));
                  } else if (typeof window.BtechBridge.onTradePhase === 'function') {
                    window.BtechBridge.onTradePhase(keyStr, String(detail || ''));
                  }
                }
              }

              function log(msg, err, done) {
                if (isCancelled()) return;
                if (done) {
                  window.__btechDiscoveryRunning = false;
                  window.__btechOrderTicketOpen = false;
                }
                if (window.BtechBridge) {
                  if (typeof window.BtechBridge.onTradeLogForRun === 'function') {
                    window.BtechBridge.onTradeLogForRun(KOTLIN_RUN_ID, String(msg), !!err, !!done);
                  } else if (typeof window.BtechBridge.onTradeLog === 'function') {
                    window.BtechBridge.onTradeLog(String(msg), !!err, !!done);
                  }
                }
              }

              function emitSymbolDiscovered(res) {
                if (isCancelled()) return;
                if (window.BtechBridge && typeof window.BtechBridge.onSymbolDiscovered === 'function') {
                  try {
                    window.BtechBridge.onSymbolDiscovered(JSON.stringify({
                      runId: KOTLIN_RUN_ID,
                      signalId: SIGNAL_ID,
                      success: !!res.success,
                      requestedSymbol: res.requestedSymbol || SYMBOL,
                      actualSymbol: res.actualSymbol || null,
                      broker: BROKER,
                      platform: PLATFORM,
                      server: SERVER,
                      matchType: res.matchType || (res.success ? 'MATCHED' : 'NOT_FOUND'),
                      message: res.error || res.message || '',
                      discoveredSymbols: Array.isArray(res.discoveredSymbols) ? res.discoveredSymbols : []
                    }));
                  } catch (_) {}
                }
              }

              function emitTradeResult(payload) {
                if (isCancelled()) return;
                if (window.BtechBridge && typeof window.BtechBridge.onTradeResult === 'function') {
                  try {
                    const enriched = Object.assign({ runId: KOTLIN_RUN_ID, signalId: SIGNAL_ID }, payload || {});
                    window.BtechBridge.onTradeResult(JSON.stringify(enriched));
                  } catch (_) {}
                }
              }

              ${buildSharedPlatformDiscoveryRoutines("250", withPhases = true, includeElementRef = true)}

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

              // Supports both attribute-labeled fields and .trade-input / MT5 unlabeled stepper rows
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

              function dispatchEnter(el) {
                if (!el) return;
                const view = getViewFor(el);
                try {
                  ['keydown', 'keypress', 'keyup'].forEach(ev => {
                    el.dispatchEvent(new view.KeyboardEvent(ev, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true }));
                  });
                } catch (_) {}
              }

              function dispatchF9TradeFormShortcut() {
                try {
                  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F9', code: 'F9', keyCode: 120, which: 120, bubbles: true, composed: true }));
                  return true;
                } catch (_) {
                  return false;
                }
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

              function isIgnoredChartOrSettingsControl(node) {
                if (!node) return false;
                const meta = (
                  (node.innerText || node.textContent || '') + ' ' +
                  (node.getAttribute && node.getAttribute('aria-label') || '') + ' ' +
                  (node.getAttribute && node.getAttribute('title') || '') + ' ' +
                  (node.getAttribute && node.getAttribute('data-testid') || '') + ' ' +
                  (typeof node.className === 'string' ? node.className : '') + ' ' +
                  (node.id || '')
                ).replace(/\s+/g, ' ').trim().toLowerCase();
                if (!meta) return false;
                if (meta.includes('show trade form') || meta.includes('new order') || meta.includes('place order')) {
                  return false;
                }
                return /\b(?:drawing|drawings|trendline|crosshair|fibonacci|indicator|indicators|settings|gear|properties|timeframe|object|objects|zoom|grid|fullscreen|full\s*screen|layout|template|candlestick|bar\s*chart|line\s*chart|alerts|notifications)\b/i.test(meta);
              }

              // Checks Buy/Sell controls, then Show Trade Form (F9) title, then New Order text/aria/title,
              // strictly ignoring chart drawing tools, indicators, and settings buttons.
              function openMT5TradeForm() {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const buttons = queryDeepAll('button, [role="button"]').filter(isRenderedVisible);
                const buyOrSellButton = buttons.find(button => {
                  const text = normalize(button.innerText || button.textContent);
                  return text === 'Buy by Market' || text === 'Sell by Market';
                });
                if (buyOrSellButton) return 'ready';

                const titledNodes = queryDeepAll('[title], [aria-label]').filter(isRenderedVisible);
                const titleTarget = titledNodes.find(node => {
                  if (isIgnoredChartOrSettingsControl(node)) return false;
                  const attr = normalize((node.getAttribute && node.getAttribute('title')) || '') + ' ' +
                    normalize((node.getAttribute && node.getAttribute('aria-label')) || '');
                  return attr.includes('Show Trade Form (F9)') ||
                    /show trade form|new order/i.test(attr);
                });
                if (titleTarget) {
                  const clickable = (titleTarget.closest && (titleTarget.closest('button') || titleTarget.closest('[role="button"]'))) || titleTarget;
                  if (!isIgnoredChartOrSettingsControl(clickable)) {
                    singleClick(clickable);
                    return 'opened:title';
                  }
                }

                const textTargets = queryDeepAll('button, [role="button"], a, span, div').filter(isRenderedVisible);
                const newOrderTarget = textTargets.find(node => {
                  if (node.children && node.children.length > 3) return false;
                  if (node.closest && node.closest('table, tr, [role="row"]')) return false;
                  if (isIgnoredChartOrSettingsControl(node)) return false;
                  const t = normalize(node.innerText || node.textContent).toLowerCase();
                  return t === 'new order' || t === 'place order' || t === 'show trade form';
                });
                if (newOrderTarget) {
                  const clickable =
                    (newOrderTarget.closest && (newOrderTarget.closest('button') || newOrderTarget.closest('[role="button"]'))) ||
                    (newOrderTarget.parentElement && newOrderTarget.parentElement.closest && (newOrderTarget.parentElement.closest('button') || newOrderTarget.parentElement.closest('[role="button"]'))) ||
                    newOrderTarget;
                  if (clickable && !isIgnoredChartOrSettingsControl(clickable)) {
                    singleClick(clickable);
                    return 'opened:text';
                  }
                }
                return 'loading:trade_form_trigger_missing';
              }

              function findStrictNewOrderButton() {
                const btns = queryDeepAll('button, [role="button"], input[type="button"], a').filter(isVisible);
                const strictKeys = PLATFORM === 'MT5'
                  ? ['new order', 'show trade form', 'f9', 'add order', 'create order', 'place order']
                  : ['new order', 'add order', 'create order', 'place order'];
                for (const k of strictKeys) {
                  const hit = btns.find(b => {
                    if (isIgnoredChartOrSettingsControl(b)) return false;
                    if (b.closest && b.closest('table, tr, [role="row"]')) return false;
                    const t = ((b.innerText || b.value || b.textContent || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                    return t.includes(k);
                  });
                  if (hit) return hit;
                }
                return null;
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

              function inspectOrderExecutionType(nearInput) {
                const scope = findOrderScope(nearInput) || (nearInput && nearInput.closest && nearInput.closest('.trade-form, [role="dialog"], [class*="order"], [class*="modal"]')) || null;
                let selectedTypeText = '';
                let selectEl = null;
                let options = [];
                let marketOption = null;
                const typeSelectors = scope
                  ? Array.from(scope.querySelectorAll("select, [role='combobox'], [class*='select' i], [class*='type' i], button"))
                  : queryDeepAll("select, [role='combobox']").filter(isRenderedVisible);
                for (let i = 0; i < typeSelectors.length; i++) {
                  const el = typeSelectors[i];
                  if (!isRenderedVisible(el)) continue;
                  if (el.tagName === 'SELECT') {
                    const opts = Array.from(el.options || []);
                    const optTexts = opts.map(o => String(o.textContent || o.value || '').replace(/\s+/g, ' ').trim());
                    const looksLikeExecSelect = optTexts.some(t =>
                      /^(?:market execution|instant execution|exchange execution|request execution|buy limit|sell limit|buy stop|sell stop|buy stop limit|sell stop limit|pending order)$/i.test(t)
                    );
                    if (!looksLikeExecSelect) continue;
                    selectEl = el;
                    options = optTexts;
                    marketOption = opts.find(o => {
                      const ot = String(o.textContent || o.value || '').replace(/\s+/g, ' ').trim().toLowerCase();
                      return ot === 'market execution' || ot === 'instant execution' || ot === 'exchange execution' || ot === 'market';
                    }) || null;
                    const idx = el.selectedIndex >= 0 ? el.selectedIndex : 0;
                    const selOpt = opts[idx];
                    selectedTypeText = selOpt ? String(selOpt.textContent || selOpt.value || '').replace(/\s+/g, ' ').trim().toLowerCase() : '';
                    break;
                  } else {
                    const txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                    if (!txt || txt.length > 48) continue;
                    if (/^(?:market execution|instant execution|exchange execution|request execution|buy limit|sell limit|buy stop|sell stop|buy stop limit|sell stop limit)$/.test(txt)) {
                      selectedTypeText = txt;
                      break;
                    }
                  }
                }
                const isPending = /\b(?:limit|stop|pending)\b/.test(selectedTypeText);
                const pendingDir = isPending
                  ? (selectedTypeText.includes('sell') ? 'SELL' : (selectedTypeText.includes('buy') ? 'BUY' : ''))
                  : '';
                return {
                  mode: isPending ? 'PENDING' : 'MARKET',
                  rawType: selectedTypeText || 'market execution',
                  pendingDirection: pendingDir,
                  selectEl: selectEl,
                  options: options,
                  hasMarketOption: !!marketOption,
                  marketOption: marketOption
                };
              }

              function findPendingEntryPriceInput(nearInput) {
                const scope = findOrderScope(nearInput) || (nearInput && nearInput.closest && nearInput.closest('.trade-form, [role="dialog"], [class*="order"], [class*="modal"]')) || null;
                const scopedInputs = scope
                  ? Array.from(scope.querySelectorAll('input')).filter(isVisible)
                  : visibleInputs();
                return findOrderFieldInput(
                  scopedInputs,
                  ['at price', 'open price', 'entry price', 'trigger price', 'limit price', 'price'],
                  ['stop loss', 'take profit', 'volume', 'lot', 'comment', 'sl', 'tp']
                );
              }

              // Actively aligns execution dropdown for market signals when mixed Market + Pending options exist;
              // rejects pending mode cleanly if an entry-price field is required and absent.
              function ensureExecutionTypeForSignal(nearInput) {
                let info = inspectOrderExecutionType(nearInput);
                if (info.mode === 'PENDING' && info.selectEl && info.marketOption) {
                  setNativeValue(info.selectEl, info.marketOption.value);
                  info = inspectOrderExecutionType(nearInput);
                }
                if (info.mode === 'PENDING') {
                  const priceInput = findPendingEntryPriceInput(nearInput);
                  if (priceInput && !String(priceInput.value || '').trim()) {
                    return {
                      ok: false,
                      reason: 'Pending order mode (' + info.rawType + ') requires an entry price not supplied by current market signal'
                    };
                  }
                }
                return { ok: true, execInfo: info };
              }

              // Detects the actual visible order action control from the active ticket and order type:
              // - Market Execution: "Buy by Market" / "Sell by Market" / "Buy" / "Sell"
              // - Pending Order: "Place" / "Submit" / "Place Order"
              // Never force-enables disabled buttons and never assumes fixed button counts per platform.
              function findOrderActionButton(dir, nearInput) {
                const target = dir === 'SELL' ? 'sell' : 'buy';
                const opposite = dir === 'SELL' ? 'buy' : 'sell';
                const execInfo = inspectOrderExecutionType(nearInput);
                const scope = findOrderScope(nearInput);

                let candidates = [];
                if (scope) {
                  candidates = Array.from(scope.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"], a, div, span')).filter(isRenderedVisible);
                } else {
                  const dialogs = queryDeepAll('[role="dialog"], [role="alertdialog"], [class*="dialog"], [class*="modal"], [class*="window"], [class*="page-window"], [class*="order-ticket"], [class*="order-panel"], [class*="trade-form"]').filter(isRenderedVisible);
                  if (dialogs.length > 0) {
                    dialogs.forEach(d => {
                      const dLower = (d.textContent || '').toLowerCase();
                      if (dLower.includes('market watch') && dLower.includes('balance')) return;
                      Array.from(d.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"], a, div, span'))
                        .filter(isRenderedVisible)
                        .forEach(c => candidates.push(c));
                    });
                  } else if (nearInput) {
                    candidates = queryDeepAll('button, [role="button"], input[type="button"], input[type="submit"], a, div, span').filter(isRenderedVisible);
                  } else {
                    return null;
                  }
                }

                const nearRect = nearInput ? nearInput.getBoundingClientRect() : null;
                let bestBtn = null;
                let bestScore = -1;

                for (const el of candidates) {
                  if (el.closest && el.closest('table, tr, [role="row"], [class*="history"], [class*="position"], [class*="deal"], [class*="toast"], [role="alert"], [class*="closed"]')) {
                    continue;
                  }
                  let rect = null;
                  try { rect = el.getBoundingClientRect(); } catch (_) {}
                  if (!rect || rect.width < 42 || rect.height < 22 || rect.width > window.innerWidth * 0.9 || rect.height > 140) {
                    continue;
                  }

                  const raw = ((el.innerText || el.value || el.textContent || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                  if (!raw || raw.length > 60) continue;
                  if (raw.includes('alert') || raw.includes('history') || raw.includes('close position') || raw.includes('modify')) continue;

                  const isPendingPlaceBtn = execInfo.mode === 'PENDING' && /^(?:place|submit|place order)$/.test(raw);
                  const isMarketBtn = !raw.includes(opposite) && new RegExp('\\b' + target + '\\b', 'i').test(raw);
                  if (!isMarketBtn && !isPendingPlaceBtn) continue;

                  const isSemantic = (el.tagName === 'BUTTON' || el.getAttribute('role') === 'button' || el.tagName === 'A' || el.tagName === 'INPUT');
                  if (!isSemantic) {
                    if (el.children && el.children.length > 3) continue;
                    if (el.closest && el.closest('button, [role="button"]')) continue;
                  }

                  let score = 0;
                  const exactMarket = raw === (target + ' by market');
                  const exactWord = raw === target;
                  const pricePlusWord = /^[\d\s.,+\-]*\b(sell|buy)\b[\d\s.,+\-]*$/i.test(raw);

                  if (isPendingPlaceBtn) score += 62;
                  else if (exactMarket) score += 65;
                  else if (exactWord) score += (PLATFORM === 'MT5' ? 58 : 48);
                  else if (pricePlusWord) score += 42;
                  else if (raw.includes(target + ' by market') || raw.includes(target + ' now')) score += 38;
                  else score += 15;

                  if (el.classList && el.classList.contains('input-button')) score += 12;
                  if (isSemantic) score += 30;
                  try {
                    const st = getViewFor(el).getComputedStyle(el);
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

              function cleanSymbolLabel(raw) {
                const text = (raw || '').split(/[\r\n,;]+/)[0].trim();
                const tok = text.split(/\s+/)[0] || text;
                return tok.trim();
              }

              function findSymbolSelectOption(targetSym) {
                const selects = queryDeepAll('select').filter(isVisible);
                for (const sel of selects) {
                  const opts = Array.from(sel.options || []);
                  let best = null;
                  let bestRank = 0;
                  opts.forEach(o => {
                    const txtSym = cleanSymbolLabel(o.text);
                    const valSym = cleanSymbolLabel(o.value);
                    const r1 = matchRank(txtSym, targetSym, VERIFIED_ALIASES);
                    const r2 = matchRank(valSym, targetSym, VERIFIED_ALIASES);
                    const r = Math.max(r1, r2);
                    if (r > bestRank) {
                      bestRank = r;
                      best = { sel: sel, opt: o, symbol: (r2 >= r1 ? valSym : txtSym), rank: r };
                    }
                  });
                  if (best) return best;
                }
                return null;
              }

              let activeSymbolName = cleanSymbolLabel(SYMBOL) || SYMBOL;
              let discoveredMatchType = 'EXACT';
              let lastSelectedSymbolElement = null;

              // Discovery routines are unified above via buildSharedPlatformDiscoveryRoutines().

              // Expanded with RoboTrader Part 2 L + JustMarkets MT5 screenshot order-ticket error phrases
              const BROKER_ERROR_TERMS = [
                'not enough money',
                'no money',
                'insufficient funds',
                'market closed',
                'market is closed',
                'off quotes',
                'requote',
                'price changed',
                'unsupported filling mode',
                'request rejected',
                'order rejected',
                'trading for this account is disabled on the server',
                'trading for this account is disabled',
                'disabled on the server',
                'trade is disabled',
                'trade disabled',
                'trading disabled',
                'volume is required',
                'invalid trade parameters',
                'invalid stops',
                'invalid sl',
                'invalid tp',
                'invalid volume',
                'invalid price',
                'autotrading disabled',
                'failed to execute',
                'context is busy',
                'too many requests'
              ];

              function isBrokerErrorText(text) {
                const value = String(text || '').toLowerCase();
                if (!value) return false;
                if (BROKER_ERROR_TERMS.some(phrase => value.includes(phrase))) return true;
                return [
                  'invalid', 'error', 'failed', 'timeout', 'rejected',
                  'denied', 'not allowed', 'prohibited', 'no prices', 'no connection'
                ].some(phrase => new RegExp('\\b' + phrase + '\\b', 'i').test(value));
              }

              function isValidTicketToken(rawDigits) {
                const d = String(rawDigits || '').replace(/\D+/g, '');
                if (!d || d.length < 4 || d.length > 14) return false;
                if (ACCOUNT_LOGIN_DIGITS && d === ACCOUNT_LOGIN_DIGITS) return false;
                return true;
              }

              function collectTradeRowText() {
                const rows = queryDeepAll('tr, [role="row"], [class*="position"], [class*="order"], [class*="deal"], [class*="trade-row"], [class*="toast"], [role="alert"], [role="dialog"], [class*="modal"]');
                let combined = '';
                for (const r of rows) {
                  try {
                    if (typeof isOrderPanelContainerBox === 'function' && isOrderPanelContainerBox(r) && !isResultPanelContainerBox(r)) continue;
                    if (r.querySelector && r.querySelector('#volume, input[name="volume"], .trade-input') && (!r.textContent || !/\bdone\b/i.test(r.textContent))) continue;
                    const t = (r.textContent || '').replace(/\s+/g, ' ').trim();
                    if (t && t.length <= 240 && !isBrokerErrorText(t)) combined += '\n' + t;
                  } catch (_) {}
                }
                return combined;
              }

              function collectTicketIds(text) {
                const ids = new Set();
                const src = text || '';
                const srcLines = src.split(/\r?\n/);
                for (const line of srcLines) {
                  const lineLower = line.toLowerCase();
                  if (isBrokerErrorText(lineLower)) continue;
                  const hashMatches = line.match(/#\d{4,}/g) || [];
                  for (const m of hashMatches) {
                    const digits = m.slice(1);
                    if (new RegExp('\\b(?:account|login|server|user|client)\\b[\\s:#]*' + digits + '\\b', 'i').test(line)) continue;
                    if (isValidTicketToken(digits)) ids.add('#' + digits);
                  }
                  const labeledMatches = line.match(/(?:\b(?:id|ticket|order|deal|position)\b[\s:#]*)\d{5,12}\b/gi) || [];
                  for (const m of labeledMatches) {
                    const digits = (m.match(/\d{5,12}/) || [])[0];
                    if (isValidTicketToken(digits)) ids.add('#' + digits);
                  }
                }
                const rowText = collectTradeRowText();
                const rowLines = rowText.split(/\n+/);
                for (const line of rowLines) {
                  if (isBrokerErrorText(line)) continue;
                  if (/\b(?:account|login|server|balance|equity|free\s*margin|margin)\b/i.test(line)) continue;
                  if (/\b(?:buy|sell)\b/i.test(line) || /\b(?:order|deal|position|ticket|opened|filled)\b/i.test(line)) {
                    const numIds = line.match(/\b\d{7,12}\b/g) || [];
                    for (const d of numIds) {
                      if (isValidTicketToken(d)) ids.add('#' + d);
                    }
                  }
                }
                return ids;
              }

              function extractFirstNewTicket(combinedText, prevTickets) {
                const allTickets = collectTicketIds(combinedText);
                let newTicket = '';
                allTickets.forEach(function(t) {
                  if (!newTicket && !prevTickets.has(t)) newTicket = t;
                });
                return newTicket;
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

              function cleanPriceString(raw) {
                const trimmed = String(raw || '').trim();
                if (!trimmed) return '';
                const compact = trimmed.replace(/[\s\u00A0]+/g, '');
                const numericCandidate = compact.replace(/,(?=\d{3}(?:\.|$))/g, '').replace(/,/g, '.');
                const m = numericCandidate.match(/^(-?\d+)\.(\d+)$/);
                if (!m) return trimmed;
                const frac = m[2];
                const hasZeroArtifact = /[1-9]0{5,}\d+$/.test(frac) || (frac.length > 8 && /0{5,}\d+$/.test(frac));
                const hasNineArtifact = /9{5,}\d*$/.test(frac);
                if (!hasZeroArtifact && !hasNineArtifact) {
                  return trimmed;
                }
                const num = Number(numericCandidate);
                if (!isFinite(num)) return trimmed;
                const roundedStr = num.toPrecision(12);
                const cleaned = (roundedStr.includes('e') || roundedStr.includes('E'))
                  ? Number(roundedStr).toFixed(Math.min(8, frac.length)).replace(/\.?0+$/, '')
                  : String(parseFloat(roundedStr));
                return cleaned || trimmed;
              }

              function cleanPricesInText(text) {
                return String(text || '').replace(/\b\d[\d\s\u00A0,]*\.\d{6,}\b/g, function(match) {
                  return cleanPriceString(match);
                });
              }

              function extractDealSummary(text, prevTickets, tradeIdx, statusText) {
                const combinedText = (text || '') + '\n' + collectTradeRowText();
                const newTicket = extractFirstNewTicket(combinedText, prevTickets);
                const dirWord = DIRECTION === 'SELL' ? 'sell' : 'buy';
                const detailRe = new RegExp('(#\\d{4,})[^\\n]{0,35}\\b(buy|sell)\\b[^\\n]{0,35}\\b([\\d.]+)\\b[^\\n]{0,25}\\bat\\s+(\\d[\\d.,]*(?:\\s\\d{3}[\\d.,]*)*)', 'i');
                const detailMatch = combinedText.match(detailRe);
                if (detailMatch && !prevTickets.has(detailMatch[1]) && isValidTicketToken(detailMatch[1])) {
                  const cleanPx = cleanPriceString(detailMatch[4]);
                  return 'Trade ' + tradeIdx + '/' + COUNT + ' confirmed · ' + detailMatch[1] + ' ' + detailMatch[2].toLowerCase() + ' ' + detailMatch[3] + ' ' + activeSymbolName + ' at ' + cleanPx;
                }
                const cleanStatus = cleanPricesInText(statusText || '');
                if (newTicket) {
                  const pxMatch = cleanStatus.match(/\bat\s+(\d[\d.,]*(?:\s\d{3}[\d.,]*)*)/i) ||
                    combinedText.match(new RegExp(newTicket + '[^\\n]{0,80}\\bat\\s+(\\d[\\d.,]*(?:\\s\\d{3}[\\d.,]*)*)', 'i'));
                  const pxSuffix = (pxMatch && pxMatch[1]) ? (' at ' + cleanPriceString(pxMatch[1])) : '';
                  return 'Trade ' + tradeIdx + '/' + COUNT + ' confirmed · ' + newTicket + ' ' + dirWord + ' ' + LOT + ' ' + activeSymbolName + pxSuffix;
                }
                if (cleanStatus) {
                  return 'Trade ' + tradeIdx + '/' + COUNT + ' confirmed · ' + cleanStatus.slice(0, 120);
                }
                return 'Trade ' + tradeIdx + '/' + COUNT + ' confirmed · ' + dirWord + ' ' + LOT + ' ' + activeSymbolName;
              }

              // --- MT5 Lab-field helpers (RoboTrader Part 2 I & J) ---
              function findMt5TradeInputByLabel(labelText) {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const labels = queryDeepAll('.trade-input .label, .trade-input [slot="label"], .trade-input span');
                const exactLabel = labels.find(node => normalize(node.textContent) === labelText);
                if (exactLabel && exactLabel.closest) {
                  return exactLabel.closest('.trade-input')?.querySelector('input, textarea') || null;
                }
                const prefixedLabel = labels.find(node => normalize(node.textContent).startsWith(labelText));
                if (prefixedLabel && prefixedLabel.closest) {
                  return prefixedLabel.closest('.trade-input')?.querySelector('input, textarea') || null;
                }
                return null;
              }

              function findMt5CommentInput() {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const labels = queryDeepAll('.comment .label, .comment [class*="label"]');
                const commentLabel = labels.find(node => normalize(node.textContent) === 'Comment');
                return (commentLabel && commentLabel.closest && commentLabel.closest('.comment')?.querySelector('input, textarea')) || null;
              }

              function dispatchMt5TradeValue(input, desiredRaw) {
                if (!input) return false;
                const view = getViewFor(input);
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const normalizedDigits = value => normalize(value).replace(/\u00A0/g, ' ').replace(/,/g, '.');
                const desiredValue = String(desiredRaw || '').replace(/,/g, '.');

                try { input.focus(); } catch (_) {}
                input.value = '';
                try {
                  const delEv = typeof view.InputEvent === 'function'
                    ? new view.InputEvent('input', { bubbles: true, cancelable: true, data: null, inputType: 'deleteContentBackward' })
                    : new view.Event('input', { bubbles: true, cancelable: true });
                  input.dispatchEvent(delEv);
                } catch (_) {
                  input.dispatchEvent(new view.Event('input', { bubbles: true, cancelable: true }));
                }

                const proto = input.tagName === 'TEXTAREA' ? view.HTMLTextAreaElement.prototype : view.HTMLInputElement.prototype;
                const desc = Object.getOwnPropertyDescriptor(proto, 'value');
                if (desc && desc.set) desc.set.call(input, desiredValue);
                else input.value = desiredValue;

                try {
                  if (typeof view.InputEvent === 'function') {
                    input.dispatchEvent(new view.InputEvent('beforeinput', { bubbles: true, cancelable: true, data: desiredValue, inputType: 'insertText' }));
                  }
                } catch (_) {}
                input.dispatchEvent(new view.Event('input', { bubbles: true, cancelable: true }));
                input.dispatchEvent(new view.Event('change', { bubbles: true, cancelable: true }));
                try {
                  input.dispatchEvent(new view.KeyboardEvent('keydown', { bubbles: true, key: 'Tab', code: 'Tab' }));
                  input.dispatchEvent(new view.KeyboardEvent('keyup', { bubbles: true, key: 'Tab', code: 'Tab' }));
                } catch (_) {}
                input.dispatchEvent(new view.Event('blur', { bubbles: true, cancelable: true }));

                const actualValue = normalizedDigits(input.value);
                const expectedValue = normalizedDigits(desiredValue);
                const actualNumeric = Number(actualValue);
                const expectedNumeric = Number(expectedValue);
                const sameNumeric = Number.isFinite(actualNumeric) &&
                  Number.isFinite(expectedNumeric) &&
                  Math.abs(actualNumeric - expectedNumeric) < 0.00001;
                return actualValue === expectedValue || sameNumeric;
              }

              // RoboTrader Part 2 I wrapper
              function mt5SetTradeField(fieldKind, label, fieldValue) {
                const input = fieldKind === 'comment'
                  ? findMt5CommentInput()
                  : findMt5TradeInputByLabel(label);
                if (!input) return 'loading:' + label + '_field_not_ready';
                if (!dispatchMt5TradeValue(input, fieldValue)) return 'error:Invalid ' + label + ' value';
                return 'filled';
              }

              // RoboTrader Part 2 J exact recovered minimum-volume verification logic + min/max check
              function verifyMT5MinimumVolume(volInputOverride) {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const volumeInput = volInputOverride || findMt5TradeInputByLabel('Volume');
                if (!volumeInput) return 'loading:Volume_field_not_ready';
                const decrementButton = volumeInput.previousElementSibling;
                const actualVolume = Number(
                  normalize(volumeInput.value).replace(/\u00A0/g, ' ').replace(/,/g, '.')
                );
                const isAtMinimum = !!(decrementButton &&
                  ((decrementButton.matches && decrementButton.matches('button')) || decrementButton.tagName === 'BUTTON') &&
                  decrementButton.disabled);
                return Number.isFinite(actualVolume) && actualVolume > 0 && isAtMinimum
                  ? 'filled'
                  : 'loading:minimum_volume_not_ready';
              }

              // RoboTrader Part 2 E MT4 field-setting path (#volume, #sl click->'0'->change->value, #tp, #comment)
              function mt4PopulateOrderFields() {
                const selMatch = findSymbolSelectOption(activeSymbolName);
                if (selMatch) {
                  setNativeValue(selMatch.sel, selMatch.opt.value);
                  activeSymbolName = cleanSymbolLabel(selMatch.symbol) || activeSymbolName;
                }

                const inputs = visibleInputs();
                let volInput = document.getElementById('volume');
                if (volInput && !isVisible(volInput)) volInput = null;
                if (!volInput) volInput = findOrderFieldInput(inputs, ['volume', 'lot', 'size', 'qty'], ['stop loss', 'take profit', 'comment']);

                let slInput = document.getElementById('sl');
                if (slInput && !isVisible(slInput)) slInput = null;
                if (!slInput) slInput = findOrderFieldInput(inputs, ['sl', 'stop loss', 'stoploss'], ['volume', 'take profit', 'comment']);

                let tpInput = document.getElementById('tp');
                if (tpInput && !isVisible(tpInput)) tpInput = null;
                if (!tpInput) tpInput = findOrderFieldInput(inputs, ['tp', 'take profit', 'takeprofit'], ['volume', 'stop loss', 'comment']);

                let commentInput = document.querySelector('#comment');
                if (commentInput && !isVisible(commentInput)) commentInput = null;
                if (!commentInput) commentInput = findOrderFieldInput(inputs, ['comment', 'note'], ['volume', 'stop loss', 'take profit']);

                if (volInput) {
                  setNativeValue(volInput, LOT);
                }
                if (slInput && SL) {
                  try {
                    const view = getViewFor(slInput);
                    slInput.dispatchEvent(new view.MouseEvent('click', { bubbles: true }));
                    slInput.value = '0';
                    slInput.dispatchEvent(new view.Event('change', { bubbles: true, cancelable: true }));
                  } catch (_) {}
                  setNativeValue(slInput, SL);
                }
                if (tpInput && TP) {
                  try {
                    const view = getViewFor(tpInput);
                    tpInput.dispatchEvent(new view.MouseEvent('click', { bubbles: true }));
                    tpInput.value = '0';
                    tpInput.dispatchEvent(new view.Event('change', { bubbles: true, cancelable: true }));
                  } catch (_) {}
                  setNativeValue(tpInput, TP);
                }
                if (commentInput && COMMENT) {
                  setNativeValue(commentInput, COMMENT);
                }
                applyOrderDeviationIfPresent(volInput);

                return {
                  volInput: volInput,
                  slInput: slInput,
                  tpInput: tpInput,
                  commentInput: commentInput
                };
              }

              function mt5PopulateOrderFields() {
                const selMatch = findSymbolSelectOption(activeSymbolName);
                if (selMatch) {
                  setNativeValue(selMatch.sel, selMatch.opt.value);
                  activeSymbolName = cleanSymbolLabel(selMatch.symbol) || activeSymbolName;
                }

                const inputs = visibleInputs();
                const tradeInputs = queryDeepAll('input.trade-input, .trade-input input').filter(isVisible);

                let volInput = findMt5TradeInputByLabel('Volume')
                  || findOrderFieldInput(inputs, ['volume', 'lot', 'size', 'qty'], ['stop loss', 'take profit', 'comment'])
                  || (tradeInputs.length > 0 ? tradeInputs[0] : null);
                let slInput = findMt5TradeInputByLabel('Stop Loss')
                  || findOrderFieldInput(inputs, ['sl', 'stop loss', 'stoploss'], ['volume', 'take profit', 'comment']);
                let tpInput = findMt5TradeInputByLabel('Take Profit')
                  || findOrderFieldInput(inputs, ['tp', 'take profit', 'takeprofit'], ['volume', 'stop loss', 'comment']);
                let commentInput = findMt5CommentInput()
                  || findOrderFieldInput(inputs, ['comment', 'note'], ['volume', 'stop loss', 'take profit']);

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

                if (volInput) dispatchMt5TradeValue(volInput, LOT);
                if (slInput && SL) dispatchMt5TradeValue(slInput, SL);
                if (tpInput && TP) dispatchMt5TradeValue(tpInput, TP);
                if (commentInput && COMMENT) dispatchMt5TradeValue(commentInput, COMMENT);
                applyOrderDeviationIfPresent(volInput);

                return {
                  volInput: volInput,
                  slInput: slInput,
                  tpInput: tpInput,
                  commentInput: commentInput
                };
              }

              // Enables and applies a small Instant Execution deviation/slippage tolerance if exposed
              // on the order ticket (only when currently unset or 0).
              function applyOrderDeviationIfPresent(nearInput) {
                const scope = findOrderScope(nearInput)
                  || (nearInput && nearInput.closest && nearInput.closest('.trade-form, [role="dialog"], [class*="order"], [class*="modal"], .page-window'))
                  || null;
                if (!scope) return false;
                let applied = false;

                const checkboxes = Array.from(scope.querySelectorAll('input[type="checkbox"], [role="checkbox"], [role="switch"]')).filter(isRenderedVisible);
                for (let i = 0; i < checkboxes.length; i++) {
                  const cb = checkboxes[i];
                  if (cb.disabled || cb.getAttribute('aria-disabled') === 'true') continue;
                  const wrap = cb.closest('label, .checkbox, .trade-input, div, tr') || cb.parentElement;
                  const meta = (
                    (cb.id || '') + ' ' + (cb.name || '') + ' ' +
                    (cb.getAttribute('aria-label') || '') + ' ' +
                    (wrap ? (wrap.innerText || wrap.textContent || '') : '')
                  ).replace(/\s+/g, ' ').trim().toLowerCase();
                  if (!/(?:deviation|slippage|max\s*dev)/.test(meta)) continue;
                  const checked = cb.type === 'checkbox' ? !!cb.checked : cb.getAttribute('aria-checked') === 'true';
                  if (!checked) {
                    singleClick(cb);
                    applied = true;
                  }
                }

                const scopedInputs = Array.from(scope.querySelectorAll('input')).filter(isVisible);
                const devInput = findOrderFieldInput(
                  scopedInputs,
                  ['deviation', 'slippage', 'max deviation', 'max dev'],
                  ['volume', 'lot', 'size', 'qty', 'stop loss', 'take profit', 'comment', 'sl', 'tp', 'price']
                );
                if (devInput && !devInput.disabled && !devInput.readOnly) {
                  const curVal = String(devInput.value || '').replace(/\s+/g, '').trim();
                  const curNum = parseFloat(curVal.replace(',', '.'));
                  if (!curVal || curNum === 0) {
                    if (PLATFORM === 'MT5') {
                      dispatchMt5TradeValue(devInput, '20');
                    } else {
                      setNativeValue(devInput, '20');
                    }
                    applied = true;
                  }
                }
                return applied;
              }

              function populateOrderFields() {
                return PLATFORM === 'MT4' ? mt4PopulateOrderFields() : mt5PopulateOrderFields();
              }

              // Verifies active platform, order form belongs to activeSymbolName, order execution type & direction, volume (+ min/max/decrement check), SL/TP & comment
              function verifySelectedInstrumentAndOrderForm(fields, finalBtn) {
                const volInput = fields ? fields.volInput : null;
                if (!volInput || !finalBtn) {
                  return { ok: false, reason: 'Order controls not ready for ' + activeSymbolName };
                }
                if (finalBtn.closest && finalBtn.closest('table, tr, [role="row"], [class*="history"], [class*="position"], [class*="deal"], [role="alert"], [class*="closed"]')) {
                  return { ok: false, reason: 'Rejected non-order control (history/position/alert)' };
                }

                const execInfo = inspectOrderExecutionType(volInput);
                const dirWord = DIRECTION === 'SELL' ? 'sell' : 'buy';
                const oppWord = DIRECTION === 'SELL' ? 'buy' : 'sell';
                const btnLabel = ((finalBtn.innerText || finalBtn.value || finalBtn.textContent || '') + ' ' + (finalBtn.getAttribute('aria-label') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                if (execInfo.mode === 'PENDING') {
                  if (execInfo.pendingDirection && execInfo.pendingDirection !== DIRECTION) {
                    return { ok: false, reason: 'Pending order direction mismatch: form is set to ' + execInfo.rawType + ', signal requested ' + DIRECTION };
                  }
                  if (!/^(?:place|submit|place order)$/.test(btnLabel) && !new RegExp('\\b' + dirWord + '\\b', 'i').test(btnLabel)) {
                    return { ok: false, reason: 'Pending order control mismatch: found "' + btnLabel + '"' };
                  }
                } else {
                  if (!new RegExp('\\b' + dirWord + '\\b', 'i').test(btnLabel) || new RegExp('\\b' + oppWord + '\\b', 'i').test(btnLabel)) {
                    return { ok: false, reason: 'Direction button mismatch: expected ' + DIRECTION + ', found "' + btnLabel + '"' };
                  }
                }

                const scope = findOrderScope(volInput) || finalBtn.closest("[role='dialog'], [role='alertdialog'], [class*='dialog'], [class*='modal'], [class*='order'], [class*='trade-form']") || volInput.parentElement;
                let observedTicketSymbol = '';
                if (scope) {
                  const symEls = Array.from(scope.querySelectorAll("select, [data-symbol], [data-name], [class*='symbol' i], h1, h2, h3, [class*='title'], [class*='header'], input[name*='symbol' i], [role='combobox']"));
                  let bestTicketMatch = null;
                  let firstForeignSymbol = '';
                  for (let i = 0; i < symEls.length; i++) {
                    const el = symEls[i];
                    if (el.tagName === 'SELECT') {
                      const selOpt = el.options && el.options[el.selectedIndex];
                      const optVal = selOpt ? cleanSymbolLabel(selOpt.value || selOpt.textContent) : '';
                      if (optVal) {
                        if (matchRank(optVal, activeSymbolName, VERIFIED_ALIASES) > 0) {
                          bestTicketMatch = optVal;
                        } else if (!firstForeignSymbol) {
                          firstForeignSymbol = optVal;
                        }
                      }
                      continue;
                    }
                    const tokens = extractPreservedCaseTokens(getElementText(el));
                    for (let j = 0; j < tokens.length; j++) {
                      const tok = tokens[j];
                      if (matchRank(tok, activeSymbolName, VERIFIED_ALIASES) > 0) {
                        bestTicketMatch = tok;
                      } else if (
                        !firstForeignSymbol &&
                        /^(?:[A-Z]{6}(?:[._#-][A-Za-z0-9]+|[mc])?|XAU|XAG|GOLD|SILVER|BTC|ETH|US30|US500|NAS100|USTEC|DE30|DE40|GER40)/i.test(tok) &&
                        el.getAttribute && (el.getAttribute('data-symbol') || /symbol/i.test((el.className || '') + ' ' + (el.id || '')))
                      ) {
                        firstForeignSymbol = tok;
                      }
                    }
                  }
                  if (bestTicketMatch) {
                    observedTicketSymbol = bestTicketMatch;
                  } else if (firstForeignSymbol) {
                    return {
                      ok: false,
                      reason: 'Order form instrument mismatch: terminal order ticket is set to ' + firstForeignSymbol + ' instead of ' + activeSymbolName
                    };
                  }
                }

                const actualLotNum = parseFloat(String(volInput.value || '').replace(/\u00A0/g, '').replace(',', '.'));
                const expectedLotNum = parseFloat(String(LOT || '').replace(/\u00A0/g, '').replace(',', '.'));
                const minAttr = parseFloat(String(volInput.getAttribute('min') || volInput.getAttribute('aria-valuemin') || ''));
                const maxAttr = parseFloat(String(volInput.getAttribute('max') || volInput.getAttribute('aria-valuemax') || ''));

                if (!isNaN(expectedLotNum) && !isNaN(minAttr) && expectedLotNum < minAttr - 0.00001) {
                  return {
                    ok: false,
                    reason: 'Volume ' + LOT + ' is below broker minimum (' + minAttr + ') for ' + activeSymbolName
                  };
                }
                if (!isNaN(expectedLotNum) && !isNaN(maxAttr) && expectedLotNum > maxAttr + 0.00001) {
                  return {
                    ok: false,
                    reason: 'Volume ' + LOT + ' exceeds broker maximum (' + maxAttr + ') for ' + activeSymbolName
                  };
                }
                if (!isNaN(expectedLotNum) && (isNaN(actualLotNum) || Math.abs(actualLotNum - expectedLotNum) > 0.00001)) {
                  const minState = PLATFORM === 'MT5' ? verifyMT5MinimumVolume(volInput) : '';
                  const minHint = minState === 'filled' && !isNaN(actualLotNum) && actualLotNum > expectedLotNum
                    ? ' (terminal clamped to minimum ' + volInput.value + ')'
                    : '';
                  return {
                    ok: false,
                    reason: 'Volume mismatch in order ticket: expected ' + LOT + ', got "' + (volInput.value || '') + '"' + minHint
                  };
                }

                if (SL) {
                  if (!fields.slInput) {
                    return { ok: false, reason: 'Stop Loss field missing when SL=' + SL + ' was required' };
                  }
                  const actualSl = parseFloat(String(fields.slInput.value || '').replace(',', '.'));
                  const expectedSl = parseFloat(String(SL).replace(',', '.'));
                  if (!isNaN(expectedSl) && (isNaN(actualSl) || Math.abs(actualSl - expectedSl) > 0.0001)) {
                    return { ok: false, reason: 'Stop Loss mismatch: expected ' + SL + ', got "' + (fields.slInput.value || '') + '"' };
                  }
                }

                if (TP) {
                  if (!fields.tpInput) {
                    return { ok: false, reason: 'Take Profit field missing when TP=' + TP + ' was required' };
                  }
                  const actualTp = parseFloat(String(fields.tpInput.value || '').replace(',', '.'));
                  const expectedTp = parseFloat(String(TP).replace(',', '.'));
                  if (!isNaN(expectedTp) && (isNaN(actualTp) || Math.abs(actualTp - expectedTp) > 0.0001)) {
                    return { ok: false, reason: 'Take Profit mismatch: expected ' + TP + ', got "' + (fields.tpInput.value || '') + '"' };
                  }
                }

                if (COMMENT && fields.commentInput && String(fields.commentInput.value || '').trim() !== COMMENT.trim()) {
                  return { ok: false, reason: 'Comment mismatch: expected "' + COMMENT + '", got "' + (fields.commentInput.value || '') + '"' };
                }

                return {
                  ok: true,
                  verifiedSymbol: observedTicketSymbol || activeSymbolName,
                  buttonText: (finalBtn.innerText || finalBtn.value || finalBtn.textContent || '').replace(/\s+/g, ' ').trim()
                };
              }

              // Safety guard: distinguishes real result/order-entry modals from open positions/pending-orders/history rows
              function isInsidePositionOrOrderTable(el) {
                return !!(el && el.closest && el.closest('table, tbody, tr, [role="row"], [class*="position" i], [class*="positions" i], [class*="history" i], [class*="deal" i], [class*="orders" i]'));
              }

              function isUnsafePositionActionLabel(text) {
                const lower = String(text || '').toLowerCase();
                return lower.includes('close position') ||
                  lower.includes('close all') ||
                  lower.includes('close by') ||
                  lower.includes('cancel order') ||
                  lower.includes('delete') ||
                  lower.includes('modify') ||
                  lower === 'remove';
              }

              function isDialogLikeContainer(el) {
                return !!(el && el.closest && el.closest('[role="dialog"], [role="alertdialog"], [class*="modal" i], [class*="dialog" i], [class*="receipt" i], [class*="popup" i], [class*="trade-form" i], [class*="order-ticket" i], [class*="order-panel" i], [class*="trade-result" i], [class*="page-window" i], [class*="alert" i]'));
              }

              function isOrderSubmitOrNavLabel(lowerText) {
                return /^(?:buy|sell|buy by market|sell by market|place|place order|submit|connect|connect to account|login|sign in|search|quotes|chart|trade|history|new order)$/.test(lowerText);
              }

              function isWorkspaceContainerText(lowerText) {
                const l = String(lowerText || '');
                if (!l) return false;
                if (l.length > 900) return true;
                if ((l.includes('market watch') || l.includes('search symbol')) && l.includes('balance') && l.includes('equity')) return true;
                if (l.includes('balance') && l.includes('equity') && (
                  l.includes('free margin') ||
                  l.includes('margin:') ||
                  l.includes('account history') ||
                  l.includes('journal') ||
                  l.includes('positions')
                )) return true;
                if (l.includes('positions') && l.includes('balance:') && l.includes('equity:') && l.length > 260) return true;
                return false;
              }

              function isMarketWatchOrMenuBox(box) {
                if (!box) return true;
                const cls = ((box.className || '') + ' ' + (box.getAttribute && box.getAttribute('aria-label') || '')).toLowerCase();
                if (cls.includes('market-watch') || cls.includes('mt4-ctx-menu') || cls.includes('page-menu') || cls.includes('toolbox') || cls.includes('journal')) return true;
                const txt = (box.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (!txt) return true;
                if (txt.includes('market watch') && txt.includes('balance') && !txt.includes('buy') && !txt.includes('ok')) return true;
                if (txt.includes('symbols') && (txt.includes('specification') || (txt.includes('show') && txt.includes('hide')))) return true;
                if (txt.includes('connect to account') && !txt.includes('buy') && !txt.includes('sell')) return true;
                return false;
              }

              function isOrderPanelContainerBox(box) {
                if (!box || isInsidePositionOrOrderTable(box) || isMarketWatchOrMenuBox(box)) return false;
                const txt = (box.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (!txt || isWorkspaceContainerText(txt)) return false;
                if (txt.includes('buy by market') || txt.includes('sell by market')) return true;
                if ((txt.includes('market execution') || txt.includes('instant execution') || txt.includes('pending order')) &&
                  (txt.includes('volume') || txt.includes('stop loss'))) {
                  return true;
                }
                return !!(box.querySelector && box.querySelector('#volume, input[name="volume"], .trade-input'));
              }

              function hasExplicitDialogSemantics(box) {
                if (!box) return false;
                const role = (box.getAttribute && box.getAttribute('role') || '').toLowerCase();
                if (role === 'dialog' || role === 'alertdialog') return true;
                const cls = (typeof box.className === 'string' ? box.className : '').toLowerCase();
                return /(?:modal|dialog|receipt|popup|trade-result|order-dialog|mt4-order-ticket|page-window)/.test(cls);
              }

              function isResultPanelContainerBox(box) {
                if (!box || isInsidePositionOrOrderTable(box) || isMarketWatchOrMenuBox(box)) return false;
                const txt = (box.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (!txt || isWorkspaceContainerText(txt)) return false;
                if (txt.includes('close position') || (txt.includes('balance') && txt.includes('equity'))) return false;
                const isOrderForm = isOrderPanelContainerBox(box);
                if (!isOrderForm) {
                  if (/#\d{4,}/.test(txt) && /\b(?:done|buy|sell|executed|filled|opened|placed)\b/.test(txt)) return true;
                  if (/\bdone\b/.test(txt) && /\b(?:buy|sell)\b/.test(txt) && /\bat\s+\d/.test(txt)) return true;
                  if (/\bdone\b/.test(txt) && /\bok\b/.test(txt) && txt.length <= 320 && !txt.includes('buy by market') && !txt.includes('sell by market')) return true;
                  if (isBrokerErrorText(txt) && txt.length <= 320 && !txt.includes('market watch')) return true;
                } else if (hasExplicitDialogSemantics(box)) {
                  if (/\bdone\b/.test(txt) && (/#\d{4,}/.test(txt) || /\bok\b/.test(txt) || !!findTopRightCloseControlInBox(box))) return true;
                }
                return false;
              }

              function findBoundedResultOrOrderBoxFor(el) {
                if (!el) return null;
                let cur = el.parentElement;
                for (let depth = 0; depth < 7 && cur && cur !== document.body && cur !== document.documentElement; depth++) {
                  if (isInsidePositionOrOrderTable(cur)) break;
                  const txtLower = (cur.innerText || cur.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  if (!txtLower) {
                    cur = cur.parentElement;
                    continue;
                  }
                  if (isWorkspaceContainerText(txtLower)) break;
                  if (isResultPanelContainerBox(cur)) return { box: cur, isResult: true, isOrder: false };
                  if (isOrderPanelContainerBox(cur)) return { box: cur, isResult: false, isOrder: true };
                  const ownCls = ((cur.className || '') + ' ' + (cur.getAttribute && cur.getAttribute('role') || '')).toLowerCase();
                  if (!isMarketWatchOrMenuBox(cur) && /dialog|modal|receipt|popup|trade-result|order-ticket|order-panel|trade-form|page-window/.test(ownCls)) {
                    return { box: cur, isResult: false, isOrder: false };
                  }
                  cur = cur.parentElement;
                }
                return null;
              }

              function findScopedResultOkButton() {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const semanticBtns = queryDeepAll('button, [role="button"], input[type="button"], input[type="submit"]')
                  .filter(isVisible)
                  .filter(x => !isInsidePositionOrOrderTable(x));
                const candidates = queryDeepAll('button, [role="button"], input[type="button"], input[type="submit"], a, div, span')
                  .filter(isRenderedVisible);
                let best = null;
                let bestScore = -1;
                for (let i = 0; i < candidates.length; i++) {
                  const b = candidates[i];
                  if (isInsidePositionOrOrderTable(b) || isButtonBusyOrDisabled(b)) continue;
                  if (b.children && b.children.length > 3) continue;
                  const isSemantic = b.tagName === 'BUTTON' || b.tagName === 'INPUT' || b.getAttribute('role') === 'button' || b.tagName === 'A';
                  if (!isSemantic && b.closest && b.closest('button, [role="button"]')) continue;
                  const txt = normalize(b.innerText || b.value || b.textContent || b.getAttribute('aria-label'));
                  if (!txt) continue;
                  const isExactUpperOk = txt === 'OK';
                  const scopedPanel = findBoundedResultOrOrderBoxFor(b);
                  const inDialogScope = isDialogLikeContainer(b) || !!scopedPanel;
                  if (!isExactUpperOk && !((isSemantic || inDialogScope) && /^ok$/i.test(txt))) continue;
                  if (!inDialogScope && semanticBtns.length > 4) continue;
                  let score = (isSemantic ? 40 : 10) + (isExactUpperOk ? 20 : 5) + (scopedPanel ? 15 : 0);
                  if (score > bestScore) {
                    bestScore = score;
                    best = b;
                  }
                }
                return best;
              }

              function findTopRightCloseControlInBox(box) {
                if (!box || isInsidePositionOrOrderTable(box)) return null;
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const controls = Array.from(box.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"], a, [aria-label*="close" i], [title*="close" i], [class*="close" i], [data-testid*="close" i], .x, div, span')).filter(isRenderedVisible);
                const labeledHit = controls.find(btn => {
                  if (isInsidePositionOrOrderTable(btn) || isButtonBusyOrDisabled(btn)) return false;
                  if (btn.children && btn.children.length > 3) return false;
                  const isSemantic = btn.tagName === 'BUTTON' || btn.tagName === 'INPUT' || btn.tagName === 'A' || btn.getAttribute('role') === 'button';
                  const closeMeta = ((btn.getAttribute('aria-label') || '') + ' ' + (btn.getAttribute('title') || '') + ' ' + (typeof btn.className === 'string' ? btn.className : '') + ' ' + (btn.getAttribute('data-testid') || '')).toLowerCase();
                  const txt = normalize(btn.innerText || btn.value || btn.textContent || btn.getAttribute('aria-label') || btn.getAttribute('title'));
                  if (isUnsafePositionActionLabel(txt)) return false;
                  const tl = txt.toLowerCase();
                  if (isOrderSubmitOrNavLabel(tl)) return false;
                  const hasCloseAttr = /close|dismiss/i.test(closeMeta);
                  const hasCloseClassX = /(?:^|\s|[-_])x(?:\s|$|[-_])/.test(closeMeta);
                  if (!txt && !hasCloseAttr && !hasCloseClassX) return false;
                  if (!txt && hasCloseClassX) {
                    try {
                      const r = btn.getBoundingClientRect();
                      return isSemantic || (r && r.width > 0 && r.width <= 60 && r.height > 0 && r.height <= 60);
                    } catch (_) {
                      return true;
                    }
                  }
                  if (tl === 'done') return isSemantic;
                  if (tl === 'close' || tl === 'cancel' || tl === 'dismiss' || tl === 'got it' || tl === '×' || tl === '✕' || tl === '✖' || tl === 'close dialog' || tl === 'close order' || tl === 'close form') {
                    return true;
                  }
                  if (tl === 'x') {
                    try {
                      const r = btn.getBoundingClientRect();
                      return isSemantic || hasCloseAttr || hasCloseClassX || (r && r.width <= 54 && r.height <= 54);
                    } catch (_) {
                      return isSemantic || hasCloseAttr || hasCloseClassX;
                    }
                  }
                  return hasCloseAttr && txt.length <= 24;
                });
                if (labeledHit) return labeledHit;

                // Geometric top-right header close icon fallback (MT5 drawer blue × SVG / MT4 .page-window red [x])
                let boxRect = null;
                try { boxRect = box.getBoundingClientRect(); } catch (_) {}
                if (!boxRect || boxRect.width < 100 || boxRect.height < 60) return null;

                const iconNodes = Array.from(box.querySelectorAll('button, [role="button"], a, div, span, svg')).filter(isRenderedVisible);
                let bestIcon = null;
                let bestIconScore = -1;
                for (let i = 0; i < iconNodes.length; i++) {
                  const el = iconNodes[i];
                  if (isInsidePositionOrOrderTable(el) || isButtonBusyOrDisabled(el)) continue;
                  let r = null;
                  try { r = el.getBoundingClientRect(); } catch (_) {}
                  if (!r || r.width < 8 || r.width > 56 || r.height < 8 || r.height > 56) continue;
                  if (r.top < boxRect.top - 4 || r.top > boxRect.top + 64) continue;
                  if (r.right < boxRect.right - 80 || r.right > boxRect.right + 4) continue;
                  const t = normalize(el.innerText || el.textContent);
                  const tl = t.toLowerCase();
                  if (t && tl !== 'x' && t !== '×' && t !== '✕' && t !== '✖') continue;
                  const isSemantic = el.tagName === 'BUTTON' || el.tagName === 'A' || el.getAttribute('role') === 'button';
                  const hasSvg = el.tagName === 'SVG' || !!(el.querySelector && el.querySelector('svg'));
                  const closeMeta = ((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (typeof el.className === 'string' ? el.className : '')).toLowerCase();
                  const hasMetaHint = /close|dismiss/i.test(closeMeta) || /(?:^|\s|[-_])x(?:\s|$|[-_])/.test(closeMeta);
                  let pointerOrBg = false;
                  try {
                    const st = getViewFor(el).getComputedStyle(el);
                    pointerOrBg = !!(st && (
                      st.cursor === 'pointer' ||
                      (st.backgroundColor && st.backgroundColor !== 'rgba(0, 0, 0, 0)' && st.backgroundColor !== 'transparent') ||
                      (st.backgroundImage && st.backgroundImage !== 'none')
                    ));
                  } catch (_) {}
                  if (!isSemantic && !hasSvg && !t && !pointerOrBg && !hasMetaHint) continue;
                  if (el.tagName === 'SVG' && el.parentElement && el.parentElement !== box) {
                    const pTag = el.parentElement.tagName;
                    if (pTag === 'BUTTON' || pTag === 'A' || el.parentElement.getAttribute('role') === 'button') continue;
                  }
                  const score = (isSemantic ? 40 : 10) + (t ? 25 : 0) + (hasSvg ? 15 : 0) + (hasMetaHint ? 20 : 0) + (pointerOrBg ? 10 : 0);
                  if (score > bestIconScore) {
                    bestIconScore = score;
                    bestIcon = el;
                  }
                }
                return bestIcon;
              }

              function findOpenOrderOrResultPanelBoxes() {
                const seen = [];
                function addUnique(box) {
                  if (!box || seen.indexOf(box) >= 0) return;
                  if (isInsidePositionOrOrderTable(box) || isMarketWatchOrMenuBox(box)) return;
                  const txtLower = (box.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  if (!txtLower || isWorkspaceContainerText(txtLower)) return;
                  seen.push(box);
                }

                const modalBoxes = queryDeepAll('[role="dialog"], [role="alertdialog"], [class*="modal" i], [class*="dialog" i], [class*="receipt" i], [class*="popup" i], [class*="trade-result" i], [class*="order-ticket" i], [class*="order-panel" i], [class*="order-dialog" i], [class*="page-window" i], .trade-form').filter(isRenderedVisible);
                for (let i = 0; i < modalBoxes.length; i++) {
                  const box = modalBoxes[i];
                  const cls = ((box.className || '') + ' ' + (box.getAttribute('role') || '')).toLowerCase();
                  const isPageWindowOnly = cls.includes('page-window') && !/(?:dialog|modal|receipt|trade-result|order|trade-form)/.test(cls);
                  if (isPageWindowOnly && !isResultPanelContainerBox(box) && !isOrderPanelContainerBox(box)) continue;
                  // Permanent docked inline order forms with no modal/dialog semantics and no close/OK control
                  // stay mounted in the workspace layout and must not falsely trigger blocking-dialog halts.
                  if (!isResultPanelContainerBox(box) && !hasExplicitDialogSemantics(box) && !findTopRightCloseControlInBox(box)) {
                    continue;
                  }
                  addUnique(box);
                }

                // Docked MT5/Svelte/React result drawer without dialog/modal class names
                const resultMarkers = queryDeepAll('button, [role="button"], div, span, p').filter(isRenderedVisible);
                for (let i = 0; i < resultMarkers.length; i++) {
                  const node = resultMarkers[i];
                  if (isInsidePositionOrOrderTable(node)) continue;
                  const t = (node.innerText || node.textContent || '').replace(/\s+/g, ' ').trim();
                  if (!t || t.length > 160) continue;
                  if (/^(?:done|ok)$/i.test(t) || /#\d{4,}/.test(t)) {
                    const anc = findBoundedResultOrOrderBoxFor(node);
                    if (anc && anc.isResult) addUnique(anc.box);
                  }
                }
                return seen;
              }

              function findHeaderCloseIconControl() {
                const boxes = findOpenOrderOrResultPanelBoxes();
                for (let i = 0; i < boxes.length; i++) {
                  const hit = findTopRightCloseControlInBox(boxes[i]);
                  if (hit) return hit;
                }
                return null;
              }

              function findScopedDialogDismissControl() {
                const okBtn = findScopedResultOkButton();
                if (okBtn) return okBtn;
                return findHeaderCloseIconControl();
              }

              // Dismisses only scoped result or order dialogs; never interacts with open positions/pending orders tables.
              function dismissTradeResult() {
                const okButton = findScopedResultOkButton();
                if (okButton) {
                  singleClick(okButton);
                  return 'dismissed:ok';
                }
                const closeButton = findScopedDialogDismissControl();
                if (closeButton) {
                  singleClick(closeButton);
                  return 'dismissed:close';
                }
                return 'dismissed:none';
              }

              function findStaleOrderEntryCloseButton() {
                const orderContainers = findOpenOrderOrResultPanelBoxes();
                for (let i = 0; i < orderContainers.length; i++) {
                  const box = orderContainers[i];
                  if (!isOrderPanelContainerBox(box) && !isResultPanelContainerBox(box)) continue;
                  const closeBtn = findTopRightCloseControlInBox(box);
                  if (closeBtn) return closeBtn;
                }
                return null;
              }

              function hasOpenBlockingModalContainer() {
                const boxes = findOpenOrderOrResultPanelBoxes();
                return boxes.some(box => {
                  const cls = ((box.className || '') + ' ' + (box.getAttribute('role') || '')).toLowerCase();
                  if (isResultPanelContainerBox(box) || isOrderPanelContainerBox(box)) return true;
                  return /(?:dialog|modal|receipt|popup|trade-result|order-dialog)/.test(cls);
                });
              }

              function hasOpenResultOrErrorBlockingDialog() {
                if (findScopedResultOkButton()) return true;
                const boxes = findOpenOrderOrResultPanelBoxes();
                return boxes.some(box => isResultPanelContainerBox(box) || !isOrderPanelContainerBox(box));
              }

              function hasOpenOrderOrResultDialog() {
                if (findScopedResultOkButton()) return true;
                if (findHeaderCloseIconControl()) return true;
                if (hasOpenBlockingModalContainer()) return true;
                return false;
              }

              // Closes blocking order-entry/result/rejection dialogs safely (never open positions/pending orders),
              // clicks OK first, escalates to top-right × / red [x] if OK leaves the drawer open, retries refused clicks,
              // and confirms the blocking modal actually disappeared from the DOM.
              async function dismissBlockingDialogsAndConfirmClear(maxAttempts, waitTimeoutMs) {
                const fnStart = Date.now();
                const attemptsLimit = Number(maxAttempts || 8);
                const verifyTimeout = Number(waitTimeoutMs || 1800);
                let actions = 0;
                let okClicks = 0;
                let headerCloseClicks = 0;
                let consecutiveNoControlTicks = 0;

                for (let step = 0; step < attemptsLimit; step++) {
                  if (isCancelled()) return { cleared: false, cancelled: true, reason: 'Cancelled' };
                  if (!hasOpenOrderOrResultDialog()) {
                    return { cleared: true, actions: actions };
                  }
                  const okBtn = findScopedResultOkButton();
                  const headerClose = findHeaderCloseIconControl() || findStaleOrderEntryCloseButton();
                  let targetBtn = null;
                  if (okBtn && okClicks < 2) {
                    okClicks++;
                    targetBtn = okBtn;
                  } else if (headerClose && headerCloseClicks < 3) {
                    headerCloseClicks++;
                    targetBtn = headerClose;
                  } else if (okBtn && okClicks < 3) {
                    okClicks++;
                    targetBtn = okBtn;
                  }

                  if (targetBtn) {
                    consecutiveNoControlTicks = 0;
                    singleClick(targetBtn);
                    actions++;
                    await wait(180);
                  } else if (actions > 0) {
                    // Already clicked OK / close — break cleanly into verifyTimeout polling so async DOM closure finishes
                    break;
                  } else {
                    consecutiveNoControlTicks++;
                    if (consecutiveNoControlTicks >= 5) {
                      break;
                    }
                    await wait(120);
                  }
                }

                if (hasOpenOrderOrResultDialog() && (actions > 0 || consecutiveNoControlTicks < 5)) {
                  let pollNoCtrl = 0;
                  while (Date.now() - fnStart < verifyTimeout) {
                    if (isCancelled()) return { cleared: false, cancelled: true, reason: 'Cancelled' };
                    if (!hasOpenOrderOrResultDialog()) {
                      return { cleared: true, actions: actions };
                    }
                    const retryOk = findScopedResultOkButton();
                    const retryHeaderClose = findHeaderCloseIconControl() || findStaleOrderEntryCloseButton();
                    let retryBtn = null;
                    if (retryOk && okClicks < 2) {
                      okClicks++;
                      retryBtn = retryOk;
                    } else if (retryHeaderClose && headerCloseClicks < 3) {
                      headerCloseClicks++;
                      retryBtn = retryHeaderClose;
                    } else if (retryOk && okClicks < 3) {
                      okClicks++;
                      retryBtn = retryOk;
                    }
                    if (retryBtn) {
                      pollNoCtrl = 0;
                      singleClick(retryBtn);
                      actions++;
                    } else if (actions === 0) {
                      pollNoCtrl++;
                      if (pollNoCtrl >= 5) break;
                    }
                    await wait(120);
                  }
                }

                const stillOpen = hasOpenOrderOrResultDialog();
                return {
                  cleared: !stillOpen,
                  actions: actions,
                  reason: stillOpen ? 'Blocking order or result dialog remained open in DOM' : ''
                };
              }

              // Closes ONLY stale order-entry and result dialogs from a previous signal (never open positions/pending orders),
              // and waits to confirm the old modal/dialog state cleared before proceeding.
              async function clearStaleDialogsBeforeNewSignal() {
                const res = await dismissBlockingDialogsAndConfirmClear(8, 1200);
                return !!res.cleared;
              }

              // Inspects visible .error / [role="alert"] / [aria-live="assertive"] nodes (RoboTrader Part 2 K & L)
              function findVisibleAlertOrErrorText(ignoreSet) {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const nodes = queryDeepAll('.error, [role="alert"], [aria-live="assertive"]').filter(isVisible);
                for (let i = 0; i < nodes.length; i++) {
                  const text = normalize(nodes[i].innerText || nodes[i].textContent);
                  if (!text || text === 'OK' || text.length >= 500) continue;
                  if (ignoreSet && ignoreSet.has(text)) continue;
                  return text;
                }
                return '';
              }

              // RoboTrader Part 2 L status/result extraction without counting bare OK alone as success
              function extractMt5StatusText(okButton) {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const statusSelectors = ['.status', '.message', '.error', '[role="alert"]', '[aria-live="assertive"]'];
                const statusNode = queryDeepAll(statusSelectors.join(',')).find(node => {
                  const text = normalize(node.innerText || node.textContent);
                  return isVisible(node) && text && text !== 'OK' && text.length < 500;
                });
                let statusText = statusNode ? normalize(statusNode.innerText || statusNode.textContent) : '';

                if (!statusText && okButton) {
                  let parent = okButton.parentElement;
                  for (let i = 0; i < 6 && parent && parent !== document.body && parent !== document.documentElement; i += 1) {
                    const candidate = normalize(parent.innerText || parent.textContent);
                    const cLower = candidate.toLowerCase();
                    if (cLower.includes('market watch') && cLower.includes('balance')) break;
                    if (candidate && candidate !== 'OK' && candidate.length < 500) {
                      statusText = candidate;
                      break;
                    }
                    parent = parent.parentElement;
                  }
                }
                return statusText.replace(/\s+OK$/, '').trim();
              }

              function isExplicitAcceptanceStatusText(statusText) {
                if (!statusText) return false;
                if (isBrokerErrorText(statusText)) return false;
                const lower = statusText.toLowerCase();
                if (/\b(?:notice|warning|confirm|cannot|can't|unable|denied|cancel)\b/i.test(lower)) return false;
                return /#\d{4,}|\b(?:done|executed|filled|opened|placed|accepted|bought|sold)\b/i.test(lower);
              }

              function isButtonBusyOrDisabled(btn) {
                if (!btn || !btn.isConnected) return true;
                if (btn.disabled || btn.getAttribute('aria-disabled') === 'true') return true;
                try { if (btn.matches && btn.matches(':disabled')) return true; } catch (_) {}
                const cls = ((btn.className || '') + ' ' + (btn.getAttribute('data-state') || '')).toLowerCase();
                if (cls.includes('disabled') || cls.includes('loading')) return true;
                const spinner = btn.querySelector ? btn.querySelector('[role="progressbar"], [class*="spinner"], [class*="loader"]') : null;
                return !!spinner;
              }

              // RoboTrader Part 2 K invalid-field inspection when trade button is disabled
              function findInvalidFieldMessage() {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const invalidInput = queryDeepAll('.trade-input input[aria-invalid="true"], .trade-input input:invalid, input[aria-invalid="true"], input:invalid').find(isRenderedVisible);
                if (!invalidInput) return '';
                const label = normalize(invalidInput.closest('.trade-input')?.querySelector('.label, [slot="label"], span')?.textContent || invalidInput.name || invalidInput.placeholder || '');
                return label ? ('Invalid ' + label + ' value') : 'Trade cannot be submitted. Check Volume, Stop Loss and Take Profit.';
              }

              // Screenshot 4 order-ticket footer/tooltip banner inspection (e.g., "Trading for this account is disabled on the server", "Volume is required")
              function findOrderFormErrorBanner(nearInput) {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const scope = findOrderScope(nearInput) || queryDeepAll('.trade-form, [class*="order-ticket"], [class*="order-panel"], [role="dialog"]').find(isRenderedVisible) || null;
                if (!scope) return '';
                const candidates = Array.from(scope.querySelectorAll('div, span, p, [role="alert"], .error, .warning, [class*="error" i], [class*="disabled" i], [class*="tooltip" i], [class*="footer" i]')).filter(isRenderedVisible);
                for (let i = 0; i < candidates.length; i++) {
                  const node = candidates[i];
                  if (node.children && node.children.length > 3) continue;
                  const txt = normalize(node.innerText || node.textContent);
                  if (!txt || txt.length > 200) continue;
                  if (isBrokerErrorText(txt)) return txt;
                }
                return '';
              }

              function findBlockingBrokerErrorInDialogs() {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const alertErr = findVisibleAlertOrErrorText(null);
                if (alertErr && isBrokerErrorText(alertErr)) return alertErr;

                const formBannerErr = findOrderFormErrorBanner(null);
                if (formBannerErr && isBrokerErrorText(formBannerErr)) return formBannerErr;

                const boxes = findOpenOrderOrResultPanelBoxes();
                const extraModals = queryDeepAll('[role="dialog"], [role="alertdialog"], .page-window, [class*="modal" i], [class*="dialog" i], [class*="popup" i], [class*="order" i]').filter(isRenderedVisible);
                const allBoxes = [];
                boxes.concat(extraModals).forEach(b => {
                  if (b && allBoxes.indexOf(b) < 0 && !isInsidePositionOrOrderTable(b) && !isMarketWatchOrMenuBox(b)) {
                    allBoxes.push(b);
                  }
                });

                for (let i = 0; i < allBoxes.length; i++) {
                  const box = allBoxes[i];
                  const fullText = normalize(box.innerText || box.textContent);
                  if (!fullText || fullText.length > 900 || isWorkspaceContainerText(fullText.toLowerCase())) continue;
                  const nodes = Array.from(box.querySelectorAll('div, span, p, td, th, label, [role="alert"], .error, .warning')).filter(isRenderedVisible);
                  for (let j = 0; j < nodes.length; j++) {
                    const n = nodes[j];
                    if (n.children && n.children.length > 3) continue;
                    const t = normalize(n.innerText || n.textContent);
                    if (!t || t.length > 200) continue;
                    if (isBrokerErrorText(t)) return t;
                  }
                  if (isBrokerErrorText(fullText)) {
                    const lower = fullText.toLowerCase();
                    for (let k = 0; k < BROKER_ERROR_TERMS.length; k++) {
                      if (lower.includes(BROKER_ERROR_TERMS[k])) {
                        const escaped = BROKER_ERROR_TERMS[k].replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\${'$'}&');
                        const re = new RegExp('[^.;\\n]*' + escaped + '[^.;\\n]*', 'i');
                        const m = fullText.match(re);
                        return (m && m[0]) ? normalize(m[0]).slice(0, 140) : BROKER_ERROR_TERMS[k];
                      }
                    }
                    return fullText.slice(0, 140);
                  }
                }
                return '';
              }

              // Finds the Accept or Reject button inside an active Instant Execution requote prompt.
              function findRequoteDialogAction(actionKind) {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const boxes = findOpenOrderOrResultPanelBoxes();
                const extraModals = queryDeepAll('[role="dialog"], [role="alertdialog"], .page-window, [class*="modal" i], [class*="dialog" i], [class*="popup" i], [class*="order" i]').filter(isRenderedVisible);
                const allBoxes = [];
                boxes.concat(extraModals).forEach(b => {
                  if (b && allBoxes.indexOf(b) < 0 && !isInsidePositionOrOrderTable(b) && !isMarketWatchOrMenuBox(b)) {
                    allBoxes.push(b);
                  }
                });

                const targetRe = actionKind === 'reject'
                  ? /^(?:reject|decline)$/i
                  : /^(?:accept|accept\s+new\s+price|accept\s+price)$/i;

                for (let i = 0; i < allBoxes.length; i++) {
                  const box = allBoxes[i];
                  const boxLower = normalize(box.innerText || box.textContent).toLowerCase();
                  if (!boxLower || boxLower.length > 1000 || isWorkspaceContainerText(boxLower)) continue;
                  const btns = Array.from(box.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"], a')).filter(isRenderedVisible);
                  const hasAcceptAndReject =
                    btns.some(b => /^(?:accept|accept\s+new\s+price|accept\s+price)$/i.test(normalize(b.innerText || b.value || b.textContent))) &&
                    btns.some(b => /^(?:reject|decline)$/i.test(normalize(b.innerText || b.value || b.textContent)));
                  const isRequoteBox = boxLower.includes('requote') || boxLower.includes('price changed') || hasAcceptAndReject;
                  if (!isRequoteBox) continue;

                  for (let j = 0; j < btns.length; j++) {
                    const btn = btns[j];
                    if (isButtonBusyOrDisabled(btn)) continue;
                    const lbl = normalize(btn.innerText || btn.value || btn.textContent || btn.getAttribute('aria-label'));
                    if (targetRe.test(lbl)) return btn;
                  }
                }
                return null;
              }

              async function openMT4NewOrderForRow(rowEl) {
                const mwPanel = findMarketWatchPanel();
                const rowTarget = (rowEl && rowEl.isConnected && isRenderedVisible(rowEl) ? rowEl : null) ||
                  (mwPanel ? (findMT4MarketWatchSymbolRow(activeSymbolName, mwPanel, VERIFIED_ALIASES) || {}).element : null);
                if (!rowTarget) return false;
                lastSelectedSymbolElement = rowTarget;
                clickElement(rowTarget);
                await wait(60);
                if (!openMT4RowContextMenu(rowTarget)) return false;
                const start = Date.now();
                let newOrderMi = findMT4NewOrderMenuItem();
                while (!newOrderMi && Date.now() - start < 600) {
                  if (isCancelled()) return false;
                  await wait(80);
                  newOrderMi = findMT4NewOrderMenuItem();
                }
                if (newOrderMi) {
                  return clickMT4MenuItem(newOrderMi);
                }
                // Fallback if broker binds right-click contextmenu on a child cell
                const firstCell = rowTarget.querySelector ? rowTarget.querySelector('td') : null;
                if (firstCell && firstCell !== rowTarget && openMT4RowContextMenu(firstCell)) {
                  await wait(120);
                  const cellNewOrderMi = findMT4NewOrderMenuItem();
                  if (cellNewOrderMi) return clickMT4MenuItem(cellNewOrderMi);
                }
                return false;
              }

              async function openOrderFormForCurrentPlatform() {
                if (PLATFORM === 'MT5') {
                  const formStatus = openMT5TradeForm();
                  if (formStatus === 'loading:trade_form_trigger_missing') {
                    const newOrderBtn = findStrictNewOrderButton();
                    if (newOrderBtn) singleClick(newOrderBtn);
                    else dispatchF9TradeFormShortcut();
                  }
                  return true;
                } else {
                  if (await openMT4NewOrderForRow(lastSelectedSymbolElement)) {
                    return true;
                  }
                  const newOrderBtn = findStrictNewOrderButton();
                  if (newOrderBtn) {
                    singleClick(newOrderBtn);
                    return true;
                  }
                  dispatchF9TradeFormShortcut();
                  return false;
                }
              }

              function executeSequentialTrades() {
                let currentTrade = 0;
                let confirmedCount = 0;

                async function runNextTrade() {
                  if (isCancelled()) return;
                  currentTrade++;
                  setPhase(
                    'PREPARE:' + currentTrade + ':' + COUNT,
                    'Trade ' + currentTrade + '/' + COUNT + ': ' + (DIRECTION === 'SELL' ? 'sell' : 'buy') + ' ' + LOT + ' ' + activeSymbolName + '...'
                  );
                  // On Trade 1, clear ONLY stale result/error dialogs (a clean ready/docked order ticket for activeSymbolName must not self-block).
                  // On Trade 2..N, enforce strict clearance of BOTH prior result AND prior order dialogs before opening a fresh ticket.
                  const needsStrictClear = currentTrade === 1
                    ? hasOpenResultOrErrorBlockingDialog()
                    : hasOpenOrderOrResultDialog();
                  if (needsStrictClear) {
                    const preClear = await dismissBlockingDialogsAndConfirmClear(6, 1200);
                    if (isCancelled()) return;
                    if (!preClear.cleared) {
                      const blockedMsg = 'Previous blocking dialog remained open before Trade ' + currentTrade + '/' + COUNT + ': ' + preClear.reason;
                      emitTradeResult({
                        status: 'REJECTED',
                        platform: PLATFORM,
                        requestedSymbol: SYMBOL,
                        actualSymbol: activeSymbolName,
                        direction: DIRECTION,
                        lot: LOT,
                        tradeIndex: currentTrade,
                        tradeCount: COUNT,
                        confirmedCount: confirmedCount,
                        ticket: '',
                        rawMessage: blockedMsg,
                        retried: false
                      });
                      setPhase('ERROR', blockedMsg);
                      log(blockedMsg, true, true);
                      return;
                    }
                  }
                  ensureOrderPanelAndSubmit(0);
                }

                async function ensureOrderPanelAndSubmit(openWaitTicks) {
                  if (isCancelled()) return;

                  const preVolCheck = (PLATFORM === 'MT5' ? findMt5TradeInputByLabel('Volume') : document.getElementById('volume'))
                    || findOrderFieldInput(visibleInputs(), ['volume', 'lot', 'size', 'qty'], ['stop loss', 'take profit', 'comment']);
                  const preBtnCheck = findOrderActionButton(DIRECTION, preVolCheck);

                  if (!preBtnCheck || !preVolCheck) {
                    if (openWaitTicks === 0 || openWaitTicks === 5) {
                      await openOrderFormForCurrentPlatform();
                      if (isCancelled()) return;
                    }
                    const postOpenInputs = visibleInputs();
                    const postVol = (PLATFORM === 'MT5' ? findMt5TradeInputByLabel('Volume') : document.getElementById('volume'))
                      || findOrderFieldInput(postOpenInputs, ['volume', 'lot', 'size', 'qty'], ['stop loss', 'take profit', 'comment']);
                    const postBtn = findOrderActionButton(DIRECTION, postVol);
                    if (!postVol || !postBtn) {
                      const blockingBrokerErr = findBlockingBrokerErrorInDialogs();
                      if (blockingBrokerErr) {
                        const errMsg = confirmedCount > 0
                          ? ('Trade ' + currentTrade + '/' + COUNT + ' rejected (' + confirmedCount + '/' + COUNT + ' opened): ' + blockingBrokerErr)
                          : ('Trade ' + currentTrade + '/' + COUNT + ' rejected: ' + blockingBrokerErr);
                        await dismissBlockingDialogsAndConfirmClear(6, 1200);
                        emitTradeResult({
                          status: 'REJECTED',
                          platform: PLATFORM,
                          requestedSymbol: SYMBOL,
                          actualSymbol: activeSymbolName,
                          direction: DIRECTION,
                          lot: LOT,
                          tradeIndex: currentTrade,
                          tradeCount: COUNT,
                          confirmedCount: confirmedCount,
                          ticket: '',
                          rawMessage: blockingBrokerErr,
                          retried: false
                        });
                        setPhase('ERROR', errMsg);
                        log(errMsg, true, true);
                        return;
                      }
                      if (openWaitTicks < 14) {
                        schedule(function() {
                          ensureOrderPanelAndSubmit(openWaitTicks + 1);
                        }, 300);
                        return;
                      }
                      const errText = confirmedCount > 0
                        ? ('Order dialog did not reopen after ' + confirmedCount + '/' + COUNT + ' confirmed trade(s) on ' + activeSymbolName)
                        : ('Order controls not ready (' + activeSymbolName + ' ' + DIRECTION + ' ' + LOT + '). Open New Order in ' + PLATFORM + ' Web Terminal to inspect.');
                      emitTradeResult({
                        status: 'REJECTED',
                        platform: PLATFORM,
                        requestedSymbol: SYMBOL,
                        actualSymbol: activeSymbolName,
                        direction: DIRECTION,
                        lot: LOT,
                        tradeIndex: currentTrade,
                        tradeCount: COUNT,
                        confirmedCount: confirmedCount,
                        ticket: '',
                        rawMessage: errText,
                        retried: false
                      });
                      setPhase('ERROR', errText);
                      log(errText, true, true);
                      return;
                    }
                  }

                  const fields = populateOrderFields();
                  const execCheck = ensureExecutionTypeForSignal(fields.volInput);
                  if (!execCheck.ok) {
                    await dismissBlockingDialogsAndConfirmClear(6, 1000);
                    emitTradeResult({
                      status: 'REJECTED',
                      platform: PLATFORM,
                      requestedSymbol: SYMBOL,
                      actualSymbol: activeSymbolName,
                      direction: DIRECTION,
                      lot: LOT,
                      tradeIndex: currentTrade,
                      tradeCount: COUNT,
                      confirmedCount: confirmedCount,
                      ticket: '',
                      rawMessage: execCheck.reason,
                      retried: false
                    });
                    setPhase('ERROR', execCheck.reason);
                    log(execCheck.reason, true, true);
                    return;
                  }

                  const refreshedFields = populateOrderFields();
                  const finalBtn = findOrderActionButton(DIRECTION, refreshedFields.volInput);
                  if (!finalBtn) {
                    const msg = 'Order submit control missing for ' + activeSymbolName + ' ' + DIRECTION;
                    await dismissBlockingDialogsAndConfirmClear(6, 1000);
                    emitTradeResult({
                      status: 'REJECTED',
                      platform: PLATFORM,
                      requestedSymbol: SYMBOL,
                      actualSymbol: activeSymbolName,
                      direction: DIRECTION,
                      lot: LOT,
                      tradeIndex: currentTrade,
                      tradeCount: COUNT,
                      confirmedCount: confirmedCount,
                      ticket: '',
                      rawMessage: msg,
                      retried: false
                    });
                    setPhase('ERROR', msg);
                    log(msg, true, true);
                    return;
                  }

                  // Pre-click visible error node + ticket footer banner + modal inspection (Screenshot 4 / MT4 Trade is disabled)
                  const preError = findBlockingBrokerErrorInDialogs() || findVisibleAlertOrErrorText(null) || findOrderFormErrorBanner(refreshedFields.volInput);
                  if (preError && isBrokerErrorText(preError)) {
                    const errMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected before submit: ' + preError;
                    await dismissBlockingDialogsAndConfirmClear(6, 1000);
                    emitTradeResult({
                      status: 'REJECTED',
                      platform: PLATFORM,
                      requestedSymbol: SYMBOL,
                      actualSymbol: activeSymbolName,
                      direction: DIRECTION,
                      lot: LOT,
                      tradeIndex: currentTrade,
                      tradeCount: COUNT,
                      confirmedCount: confirmedCount,
                      ticket: '',
                      rawMessage: preError,
                      retried: false
                    });
                    setPhase('ERROR', errMsg);
                    log(errMsg, true, true);
                    return;
                  }

                  const verification = verifySelectedInstrumentAndOrderForm(refreshedFields, finalBtn);
                  if (!verification.ok) {
                    await dismissBlockingDialogsAndConfirmClear(6, 1000);
                    emitTradeResult({
                      status: 'REJECTED',
                      platform: PLATFORM,
                      requestedSymbol: SYMBOL,
                      actualSymbol: activeSymbolName,
                      direction: DIRECTION,
                      lot: LOT,
                      tradeIndex: currentTrade,
                      tradeCount: COUNT,
                      confirmedCount: confirmedCount,
                      ticket: '',
                      rawMessage: verification.reason,
                      retried: false
                    });
                    setPhase('ERROR', verification.reason);
                    log(verification.reason, true, true);
                    return;
                  }

                  let readyTicks = 0;
                  async function waitUntilReadyAndSubmitOnce() {
                    if (isCancelled()) return;
                    // Never force-enable a disabled trade button! Respect terminal disabled state and inspect validation errors.
                    if (isButtonBusyOrDisabled(finalBtn)) {
                      const bannerErr = findOrderFormErrorBanner(refreshedFields.volInput);
                      const invalidFieldErr = findInvalidFieldMessage() || bannerErr;
                      if (invalidFieldErr) {
                        const errMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected (' + confirmedCount + '/' + COUNT + ' opened): ' + invalidFieldErr;
                        await dismissBlockingDialogsAndConfirmClear(6, 1000);
                        emitTradeResult({
                          status: 'REJECTED',
                          platform: PLATFORM,
                          requestedSymbol: SYMBOL,
                          actualSymbol: activeSymbolName,
                          direction: DIRECTION,
                          lot: LOT,
                          tradeIndex: currentTrade,
                          tradeCount: COUNT,
                          confirmedCount: confirmedCount,
                          ticket: '',
                          rawMessage: invalidFieldErr,
                          retried: false
                        });
                        setPhase('ERROR', errMsg);
                        log(errMsg, true, true);
                        return;
                      }
                      if (readyTicks < 16) {
                        readyTicks++;
                        schedule(waitUntilReadyAndSubmitOnce, 300);
                        return;
                      }
                      const busyMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected: Order button stayed disabled (' + activeSymbolName + '). Check market hours or Volume/SL/TP.';
                      await dismissBlockingDialogsAndConfirmClear(6, 1000);
                      emitTradeResult({
                        status: 'REJECTED',
                        platform: PLATFORM,
                        requestedSymbol: SYMBOL,
                        actualSymbol: activeSymbolName,
                        direction: DIRECTION,
                        lot: LOT,
                        tradeIndex: currentTrade,
                        tradeCount: COUNT,
                        confirmedCount: confirmedCount,
                        ticket: '',
                        rawMessage: busyMsg,
                        retried: false
                      });
                      setPhase('ERROR', busyMsg);
                      log(busyMsg, true, true);
                      return;
                    }

                    setPhase(
                      'PREPARED:' + currentTrade + ':' + COUNT,
                      'Prepared Trade ' + currentTrade + '/' + COUNT + ' (' + DIRECTION + ' ' + LOT + ' ' + verification.verifiedSymbol + ')'
                    );

                    schedule(function() {
                      if (isCancelled()) return;
                      const baseText = collectDeepText();
                      const baseLower = baseText.toLowerCase();
                      const baseTickets = collectTicketIds(baseText);
                      const basePositions = countBrokerPositions();
                      const baseMargin = readUsedMarginNumeric(baseText);
                      const baseDoneHits = countReceiptDoneHits(baseText);
                      const baseErrorSet = new Set(BROKER_ERROR_TERMS.filter(t => baseLower.includes(t)));
                      const baseAlertTexts = new Set(
                        queryDeepAll('.status, .message, .error, [role="alert"], [aria-live="assertive"]')
                          .filter(isVisible)
                          .map(n => (n.innerText || n.textContent || '').replace(/\s+/g, ' ').trim())
                          .filter(Boolean)
                      );

                      setPhase(
                        'TRADE_SUBMIT:' + currentTrade + ':' + COUNT,
                        'Trade ' + currentTrade + '/' + COUNT + ': executing ' + DIRECTION + ' ' + LOT + ' ' + verification.verifiedSymbol + '...'
                      );

                      // Fire EXACTLY ONE click per authorized live trade — button click alone is NEVER counted as success!
                      singleClick(finalBtn);

                      setPhase(
                        'TRADE_CONFIRM:' + currentTrade + ':' + COUNT,
                        'Waiting for broker confirmation (Trade ' + currentTrade + '/' + COUNT + ')...'
                      );

                      let polls = 0;
                      let requoteAcceptCount = 0;
                      const maxPolls = MAX_CONFIRM_POLLS;

                      async function finishRejectedAndCleanup(rawRejectMsg, formattedErrMsg) {
                        await dismissBlockingDialogsAndConfirmClear(8, 1400);
                        if (isCancelled()) return;
                        emitTradeResult({
                          status: 'REJECTED',
                          platform: PLATFORM,
                          requestedSymbol: SYMBOL,
                          actualSymbol: activeSymbolName,
                          direction: DIRECTION,
                          lot: LOT,
                          tradeIndex: currentTrade,
                          tradeCount: COUNT,
                          confirmedCount: confirmedCount,
                          ticket: '',
                          rawMessage: rawRejectMsg,
                          retried: false
                        });
                        setPhase('ERROR', formattedErrMsg);
                        log(formattedErrMsg, true, true);
                      }

                      async function finishAcceptedAndCleanup(summary, statusText, newTicket) {
                        confirmedCount++;
                        setPhase('TRADE_CONFIRMED:' + currentTrade + ':' + COUNT, summary);
                        log(summary, false, false);

                        const postClear = await dismissBlockingDialogsAndConfirmClear(10, 2200);
                        if (isCancelled()) return;
                        if (!postClear.cleared && currentTrade < COUNT) {
                          const haltErr = 'Trade ' + currentTrade + '/' + COUNT + ' confirmed, but blocking dialog remained open — halted Trade ' + (currentTrade + 1) + '/' + COUNT + '.';
                          emitTradeResult({
                            status: 'REJECTED',
                            platform: PLATFORM,
                            requestedSymbol: SYMBOL,
                            actualSymbol: activeSymbolName,
                            direction: DIRECTION,
                            lot: LOT,
                            tradeIndex: currentTrade + 1,
                            tradeCount: COUNT,
                            confirmedCount: confirmedCount,
                            ticket: newTicket,
                            rawMessage: haltErr,
                            retried: false
                          });
                          setPhase('ERROR', haltErr);
                          log(haltErr, true, true);
                          return;
                        }

                        if (currentTrade >= COUNT) {
                          emitTradeResult({
                            status: 'ACCEPTED',
                            platform: PLATFORM,
                            requestedSymbol: SYMBOL,
                            actualSymbol: activeSymbolName,
                            direction: DIRECTION,
                            lot: LOT,
                            tradeIndex: currentTrade,
                            tradeCount: COUNT,
                            confirmedCount: confirmedCount,
                            ticket: newTicket,
                            rawMessage: statusText || summary,
                            retried: false
                          });
                          const finalMsg = COUNT > 1
                            ? 'All ' + COUNT + '/' + COUNT + ' trades confirmed · ' + summary
                            : summary;
                          setPhase('DONE', finalMsg);
                          log(finalMsg, false, true);
                        } else {
                          setPhase('TERMINAL_CLEAR:' + currentTrade + ':' + COUNT, 'Terminal clear — preparing Trade ' + (currentTrade + 1) + '/' + COUNT + '...');
                          schedule(runNextTrade, 2000);
                        }
                      }

                      async function pollConfirmation() {
                        if (isCancelled()) return;
                        polls++;

                        // 0. Instant Execution Requote handling (Option A): auto-accept updated price up to 2 times
                        const requoteAcceptBtn = findRequoteDialogAction('accept');
                        if (requoteAcceptBtn) {
                          if (requoteAcceptCount < 2) {
                            requoteAcceptCount++;
                            singleClick(requoteAcceptBtn);
                            setPhase(
                              'TRADE_CONFIRM:' + currentTrade + ':' + COUNT,
                              'Requoted price accepted (' + requoteAcceptCount + '/2) — confirming Trade ' + currentTrade + '/' + COUNT + '...'
                            );
                            log('Requote received — accepted updated price (' + requoteAcceptCount + '/2)', false, false);
                            polls = Math.min(polls, Math.max(0, maxPolls - 12));
                            schedule(pollConfirmation, 450);
                            return;
                          }
                          const requoteRejectBtn = findRequoteDialogAction('reject');
                          if (requoteRejectBtn) {
                            singleClick(requoteRejectBtn);
                          }
                          const maxRequoteErr = 'Trade ' + currentTrade + '/' + COUNT + ' rejected (' + confirmedCount + '/' + COUNT + ' opened): Requoted more than 2 times';
                          await finishRejectedAndCleanup('requote', maxRequoteErr);
                          return;
                        }

                        const curText = collectDeepText();
                        const curLower = curText.toLowerCase();
                        const okBtn = findScopedResultOkButton();
                        const rawStatusText = extractMt5StatusText(okBtn);
                        const statusText = (rawStatusText && !baseAlertTexts.has(rawStatusText)) ? rawStatusText : '';

                        // 1. Inspect visible alert/dialog/status nodes FIRST for broker rejections (e.g. "Not enough money", "Trade is disabled")
                        const dialogBrokerErr = findBlockingBrokerErrorInDialogs();
                        if (dialogBrokerErr && isBrokerErrorText(dialogBrokerErr)) {
                          const errMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected (' + confirmedCount + '/' + COUNT + ' opened): ' + dialogBrokerErr.slice(0, 160);
                          await finishRejectedAndCleanup(dialogBrokerErr, errMsg);
                          return;
                        }
                        if (statusText && isBrokerErrorText(statusText)) {
                          const errMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected (' + confirmedCount + '/' + COUNT + ' opened): ' + statusText.slice(0, 160);
                          await finishRejectedAndCleanup(statusText, errMsg);
                          return;
                        }

                        // 2. Also inspect deep terminal text diff for broker rejection phrases
                        for (const term of BROKER_ERROR_TERMS) {
                          if (!baseErrorSet.has(term) && curLower.includes(term)) {
                            const lines = curText.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
                            const snippet = (lines.find(l => l.toLowerCase().includes(term)) || term).slice(0, 140);
                            const errMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected (' + confirmedCount + '/' + COUNT + ' opened): ' + snippet;
                            await finishRejectedAndCleanup(snippet, errMsg);
                            return;
                          }
                        }

                        // 3. Real confirmation evidence ONLY (generic OK button or button click ALONE is NEVER sufficient)
                        const combinedForTickets = curText + '\n' + collectTradeRowText();
                        const newTicket = extractFirstNewTicket(combinedForTickets, baseTickets);
                        const hasNewTicket = !!newTicket;

                        const curPositions = countBrokerPositions();
                        const positionsIncreased = curPositions > basePositions;

                        const curMargin = readUsedMarginNumeric(curText);
                        const marginIncreased = baseMargin >= 0 && curMargin > baseMargin + 0.01;

                        const curDoneHits = countReceiptDoneHits(curText);
                        const hasNewReceiptPhrase =
                          (/\border\s+(?:has\s+been\s+)?(?:executed|placed|filled|opened)\b/i.test(curText) && !/\border\s+(?:has\s+been\s+)?(?:executed|placed|filled|opened)\b/i.test(baseText)) ||
                          (/\b(?:bought|sold)\s+[\d.]+\b/i.test(curText) && !/\b(?:bought|sold)\s+[\d.]+\b/i.test(baseText)) ||
                          (curDoneHits > baseDoneHits);

                        const hasExplicitStatusAccept = !!(statusText && (okBtn || hasNewReceiptPhrase || hasNewTicket) && isExplicitAcceptanceStatusText(statusText));

                        if (hasNewTicket || positionsIncreased || marginIncreased || hasNewReceiptPhrase || hasExplicitStatusAccept) {
                          const summary = extractDealSummary(curText, baseTickets, currentTrade, statusText);
                          await finishAcceptedAndCleanup(summary, statusText, newTicket);
                          return;
                        }

                        if (polls >= maxPolls) {
                          // No-blind-retry rule: clean up blocking UI if possible, report UNKNOWN, and stop!
                          const unknownClear = await dismissBlockingDialogsAndConfirmClear(6, 1000);
                          if (isCancelled()) return;
                          const unknownMsg = 'Trade ' + currentTrade + '/' + COUNT + ' status UNKNOWN (' + confirmedCount + '/' + COUNT + ' confirmed): error:Unable to confirm trade submission. The trade was not retried.' +
                            (unknownClear.cleared ? '' : ' [terminal dialog remained open]');
                          emitTradeResult({
                            status: 'UNKNOWN',
                            platform: PLATFORM,
                            requestedSymbol: SYMBOL,
                            actualSymbol: activeSymbolName,
                            direction: DIRECTION,
                            lot: LOT,
                            tradeIndex: currentTrade,
                            tradeCount: COUNT,
                            confirmedCount: confirmedCount,
                            ticket: '',
                            rawMessage: 'error:Unable to confirm trade submission. The trade was not retried.',
                            retried: false
                          });
                          setPhase('UNKNOWN', unknownMsg);
                          log(unknownMsg, true, true);
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

              async function waitForOrderControlsThenRun(tries) {
                if (isCancelled()) return;
                const inputs = visibleInputs();
                const vol = (PLATFORM === 'MT5' ? findMt5TradeInputByLabel('Volume') : document.getElementById('volume'))
                  || findOrderFieldInput(inputs, ['volume', 'lot', 'size', 'qty'], ['stop loss', 'take profit', 'comment']);
                const btn = findOrderActionButton(DIRECTION, vol);
                if (vol && btn) {
                  setPhase('SYMBOL_SELECTED', 'Selected ' + activeSymbolName);
                  schedule(executeSequentialTrades, 200);
                  return;
                }
                const earlyErr = findBlockingBrokerErrorInDialogs();
                if (earlyErr) {
                  setPhase('SYMBOL_SELECTED', 'Selected ' + activeSymbolName);
                  schedule(executeSequentialTrades, 50);
                  return;
                }
                if (tries === 0 || tries === 5) {
                  await openOrderFormForCurrentPlatform();
                  if (isCancelled()) return;
                  const afterOpenErr = findBlockingBrokerErrorInDialogs();
                  if (afterOpenErr) {
                    setPhase('SYMBOL_SELECTED', 'Selected ' + activeSymbolName);
                    schedule(executeSequentialTrades, 50);
                    return;
                  }
                }
                if (tries < 12) {
                  schedule(function() { waitForOrderControlsThenRun(tries + 1); }, 300);
                } else {
                  setPhase('SYMBOL_SELECTED', 'Selected ' + activeSymbolName);
                  schedule(executeSequentialTrades, 200);
                }
              }

              async function startPlatformSymbolDiscovery() {
                if (isCancelled()) return;

                setPhase('SYMBOL_SEARCHING', 'Checking previous terminal dialogs before ' + SYMBOL + '...');

                // Ensure any stale order-entry or result/rejection modal from a previous signal is cleared first
                // (never closing open broker positions or pending orders).
                const dialogsCleared = await clearStaleDialogsBeforeNewSignal();
                if (isCancelled()) return;
                if (!dialogsCleared) {
                  const errMsg = 'Previous order or result dialog could not be cleared cleanly before ' + SYMBOL + ' — terminal blocked';
                  emitTradeResult({
                    status: 'REJECTED',
                    platform: PLATFORM,
                    requestedSymbol: SYMBOL,
                    actualSymbol: activeSymbolName,
                    direction: DIRECTION,
                    lot: LOT,
                    tradeIndex: 1,
                    tradeCount: COUNT,
                    confirmedCount: 0,
                    ticket: '',
                    rawMessage: errMsg,
                    retried: false
                  });
                  setPhase('ERROR', errMsg);
                  log(errMsg, true, true);
                  return;
                }

                log(
                  'Running ' + PLATFORM + ' symbol discovery for ' + SYMBOL +
                    (SIGNAL_ID ? ' (Signal ' + SIGNAL_ID + ')' : '') + '...',
                  false,
                  false
                );

                const discoveryRes = PLATFORM === 'MT4'
                  ? await discoverMT4Symbol(SYMBOL)
                  : await discoverMT5Symbol(SYMBOL);

                if (isCancelled() || (discoveryRes && discoveryRes.cancelled)) return;

                emitSymbolDiscovered(discoveryRes);

                if (!discoveryRes || !discoveryRes.success) {
                  const seen = (discoveryRes && Array.isArray(discoveryRes.discoveredSymbols)) ? discoveryRes.discoveredSymbols : [];
                  const baseErr = (discoveryRes && discoveryRes.error) ? discoveryRes.error : ('error:Symbol ' + SYMBOL + ' not found');
                  const hint = seen.length > 0
                    ? ' Discovered on ' + PLATFORM + ' (' + seen.length + '): ' + seen.slice(0, 12).join(', ') + (seen.length > 12 ? ', ...' : '')
                    : '';
                  const failMsg = baseErr + hint;
                  emitTradeResult({
                    status: 'REJECTED',
                    platform: PLATFORM,
                    requestedSymbol: SYMBOL,
                    actualSymbol: activeSymbolName,
                    direction: DIRECTION,
                    lot: LOT,
                    tradeIndex: 1,
                    tradeCount: COUNT,
                    confirmedCount: 0,
                    ticket: '',
                    rawMessage: baseErr,
                    retried: false
                  });
                  setPhase('ERROR', failMsg);
                  log(failMsg, true, true);
                  return;
                }

                activeSymbolName = cleanSymbolLabel(discoveryRes.actualSymbol) || SYMBOL;
                discoveredMatchType = discoveryRes.matchType || 'EXACT';
                lastSelectedSymbolElement = discoveryRes.element || null;

                setPhase('SYMBOL_FOUND', 'Symbol found: ' + activeSymbolName);
                log('Matched ' + PLATFORM + ' symbol ' + activeSymbolName + ' (' + discoveredMatchType + ') for ' + SYMBOL, false, false);

                await wait(350);
                if (isCancelled()) return;

                setPhase('SYMBOL_SELECTING', 'Selecting symbol ' + activeSymbolName + '...');
                await wait(200);
                if (isCancelled()) return;

                setPhase('SYMBOL_SELECTED', 'Selected ' + activeSymbolName);
                schedule(executeSequentialTrades, 180);
              }

              function emitLiveBalanceSnapshotFromText(rawText) {
                if (!window.BtechBridge || typeof window.BtechBridge.onBalanceUpdate !== 'function') return;
                try {
                  const pick = function(res) {
                    for (let i = 0; i < res.length; i++) {
                      const m = rawText.match(res[i]);
                      if (m && m[1]) return m[1].replace(/\s+/g, ' ').trim();
                    }
                    return '';
                  };
                  const bal = pick([
                    /\bbalance\b[\s:]*([+\-$€£¥]{0,3}\s*-?\d[\d\s,]{0,12}\.\d{2}(?:\s+[A-Z]{3}(?![a-z]))?)/i,
                    /\bbalance\b[^\d\-+]{1,18}(-?\d[\d\s,]{0,12}\.\d{2})/i
                  ]);
                  const eq = pick([
                    /\bequity\b[\s:]*([+\-$€£¥]{0,3}\s*-?\d[\d\s,]{0,12}\.\d{2}(?:\s+[A-Z]{3}(?![a-z]))?)/i,
                    /\bequity\b[^\d\-+]{1,18}(-?\d[\d\s,]{0,12}\.\d{2})/i
                  ]);
                  const mar = pick([
                    /\bfree\s*margin\b[\s:]*([+\-$€£¥]{0,3}\s*-?\d[\d\s,]{0,12}\.\d{2}(?:\s+[A-Z]{3}(?![a-z]))?)/i,
                    /\bmargin\b[\s:]*([+\-$€£¥]{0,3}\s*-?\d[\d\s,]{0,12}\.\d{2}(?:\s+[A-Z]{3}(?![a-z]))?)/i
                  ]);
                  if (bal || eq) {
                    window.BtechBridge.onBalanceUpdate(JSON.stringify({
                      connected: true,
                      balance: bal || '—',
                      equity: eq || '—',
                      margin: mar || '—',
                      profit: '',
                      status: 'Live Web Terminal telemetry'
                    }));
                  }
                } catch (_) {}
              }

              let terminalVerified = false;
              let terminalPolls = 0;
              let authPolls = 0;
              let reconnectClicks = 0;
              let lastReconnectAttemptPoll = -10;
              function checkAuthAndStart() {
                if (isCancelled()) return;

                const platformMismatch = detectPlatformMismatchError(PLATFORM);
                if (platformMismatch) {
                  emitTradeResult({
                    status: 'REJECTED',
                    platform: PLATFORM,
                    requestedSymbol: SYMBOL,
                    actualSymbol: activeSymbolName,
                    direction: DIRECTION,
                    lot: LOT,
                    tradeIndex: 1,
                    tradeCount: COUNT,
                    confirmedCount: 0,
                    ticket: '',
                    rawMessage: platformMismatch,
                    retried: false
                  });
                  setPhase('ERROR', platformMismatch);
                  log(platformMismatch, true, true);
                  return;
                }

                const rawText = collectDeepText();
                const bodyLower = rawText.toLowerCase();
                const href = String((window.location && window.location.href) || '');
                const readyState = String((typeof document !== 'undefined' && document.readyState) || 'complete');
                const hasPwd = queryDeepAll("input[type='password'], input[name='password'], #password").filter(isVisible).length > 0;
                const hasAccountsPicker = bodyLower.includes('trading accounts');
                const isReconnectPrompt = hasMt5ReconnectScreen(bodyLower, hasPwd);
                const isCloudflare =
                  (bodyLower.includes('just a moment') ||
                    bodyLower.includes('verify you are human') ||
                    bodyLower.includes('checking your browser') ||
                    document.title.toLowerCase().includes('just a moment')) && !hasPwd;
                const hasDomNodes =
                  (typeof document !== 'undefined' && document.body && document.body.children && document.body.children.length > 0) ||
                  bodyLower.trim().length > 0 ||
                  hasPwd;

                if (!terminalVerified) {
                  const terminalDomReady =
                    readyState !== 'loading' &&
                    !/^about:blank$/i.test(href) &&
                    hasDomNodes &&
                    !isCloudflare;
                  if (!terminalDomReady) {
                    if (terminalPolls < 20) {
                      terminalPolls++;
                      setPhase('TERMINAL', 'Waiting for ' + PLATFORM + ' Web Terminal to finish opening...');
                      schedule(checkAuthAndStart, 400);
                      return;
                    }
                    const termErr = isCloudflare
                      ? 'Broker terminal showed a security challenge. Open Inspect Live Web Terminal to complete verification.'
                      : (PLATFORM + ' Web Terminal did not finish opening (' + (readyState || 'blank') + '). Open Inspect Live Web Terminal.');
                    setPhase('ERROR', termErr);
                    log(termErr, true, true);
                    return;
                  }
                  terminalVerified = true;
                  setPhase('TERMINAL_OK', PLATFORM + ' Web Terminal confirmed open');
                }

                const verifiedSession = hasVerifiedAccountSession(rawText, bodyLower, PLATFORM);
                const hasSpinner = queryDeepAll('[role="progressbar"], [class*="spinner"], [class*="loader"], [class*="loading"]').some(isVisible);

                // Screenshot 1 reconnect flow: if terminal lost internet and returned to "Trading accounts" /
                // "Connection closed or not established", tap green "Connect to account" button (NEVER "Remove") and wait for genuine connected telemetry!
                if (isReconnectPrompt && !verifiedSession) {
                  const connectBtn = findConnectToAccountButton(ACCOUNT_LOGIN);
                  if (connectBtn && reconnectClicks < 2 && (authPolls - lastReconnectAttemptPoll) >= 8) {
                    reconnectClicks++;
                    lastReconnectAttemptPoll = authPolls;
                    authPolls++;
                    setPhase('AUTH', 'Reconnecting via Connect to account...');
                    log('Terminal disconnected — tapping Connect to account' + (ACCOUNT_LOGIN ? ' (' + ACCOUNT_LOGIN + ')' : '') + '...', false, false);
                    singleClick(connectBtn);
                    schedule(checkAuthAndStart, 500);
                    return;
                  }
                  if (reconnectClicks > 0 && authPolls < 28) {
                    authPolls++;
                    setPhase('AUTH', 'Waiting for account reconnect...');
                    schedule(checkAuthAndStart, 500);
                    return;
                  }
                }

                if (hasPwd || (hasAccountsPicker && !verifiedSession)) {
                  setPhase('AUTH', 'Logging in to Web Terminal...');
                  if (window.BtechBridge) {
                    if (typeof window.BtechBridge.onTradeRequiresAuthForRun === 'function') {
                      window.BtechBridge.onTradeRequiresAuthForRun(KOTLIN_RUN_ID);
                    } else if (typeof window.BtechBridge.onTradeRequiresAuth === 'function') {
                      window.BtechBridge.onTradeRequiresAuth();
                    } else {
                      const msg = 'Web terminal session is showing a login form. Open Web Terminal to log in first.';
                      setPhase('ERROR', msg);
                      log(msg, true, true);
                    }
                  } else {
                    const msg = 'Web terminal session is showing a login form. Open Web Terminal to log in first.';
                    setPhase('ERROR', msg);
                    log(msg, true, true);
                  }
                  return;
                }

                if (!verifiedSession || hasSpinner) {
                  if (authPolls < 16) {
                    authPolls++;
                    setPhase('AUTH', 'Confirming live balance & account session inside ' + PLATFORM + '...');
                    schedule(checkAuthAndStart, 400);
                    return;
                  }
                  if (window.BtechBridge) {
                    if (typeof window.BtechBridge.onTradeRequiresAuthForRun === 'function') {
                      window.BtechBridge.onTradeRequiresAuthForRun(KOTLIN_RUN_ID);
                    } else if (typeof window.BtechBridge.onTradeRequiresAuth === 'function') {
                      window.BtechBridge.onTradeRequiresAuth();
                    } else {
                      const msg = 'Account session not confirmed inside ' + PLATFORM + ' Web Terminal (' + (hasSpinner ? 'still connecting' : 'no live Balance or Market Watch quotes') + '). Open Web Terminal to verify.';
                      setPhase('ERROR', msg);
                      log(msg, true, true);
                    }
                  } else {
                    const msg = 'Account session not confirmed inside ' + PLATFORM + ' Web Terminal (' + (hasSpinner ? 'still connecting' : 'no live Balance or Market Watch quotes') + '). Open Web Terminal to verify.';
                    setPhase('ERROR', msg);
                    log(msg, true, true);
                  }
                  return;
                }

                emitLiveBalanceSnapshotFromText(rawText);
                setPhase('AUTH_OK', 'Account authenticated inside ' + PLATFORM + ' Web Terminal');
                log('Confirmed active ' + PLATFORM + ' session (' + BROKER + ' · ' + SERVER + ')', false, false);
                setPhase('SYMBOL_SEARCHING', 'Preparing & searching ' + SYMBOL + ' on ' + PLATFORM + '...');
                schedule(function() {
                  Promise.resolve(startPlatformSymbolDiscovery()).catch(function(err) {
                    const errMsg = 'Symbol discovery error: ' + (err && err.message ? err.message : err);
                    setPhase('ERROR', errMsg);
                    log(errMsg, true, true);
                  });
                }, 80);
              }

              window.BtechRoboTraderDiscovery = {
                normalise: normalise,
                stripKnownSuffix: stripKnownSuffix,
                matchRank: matchRank,
                extractSymbolTokens: extractSymbolTokens,
                detectLoadedTerminalPlatform: detectLoadedTerminalPlatform,
                detectPlatformMismatchError: detectPlatformMismatchError,
                findConnectToAccountButton: findConnectToAccountButton,
                findSymbolElement: findSymbolElement,
                waitForSymbol: waitForSymbol,
                discoverAllBrokerSymbols: discoverAllBrokerSymbols,
                findExactVisibleText: findExactVisibleText,
                inspectMt5SearchResultAction: inspectMt5SearchResultAction,
                findMT4SymbolsMenuItem: findMT4SymbolsMenuItem,
                findMT4NewOrderMenuItem: findMT4NewOrderMenuItem,
                openMT4ContextMenu: openMT4ContextMenu,
                openMT4RowContextMenu: openMT4RowContextMenu,
                openMT4NewOrderForRow: openMT4NewOrderForRow,
                findMarketWatchPanel: findMarketWatchPanel,
                openHiddenMarketWatch: openHiddenMarketWatch,
                findMT4ShowAllMenuItem: findMT4ShowAllMenuItem,
                clickMT4ShowAllMenuItem: clickMT4ShowAllMenuItem,
                findMT4MarketWatchSymbolRow: findMT4MarketWatchSymbolRow,
                doubleClickMT4Row: doubleClickMT4Row,
                discoverMT4Symbol: discoverMT4Symbol,
                discoverMT5Symbol: discoverMT5Symbol,
                openMT5TradeForm: openMT5TradeForm,
                mt5SetTradeField: mt5SetTradeField,
                verifyMT5MinimumVolume: verifyMT5MinimumVolume,
                dismissTradeResult: dismissTradeResult,
                dismissBlockingDialogsAndConfirmClear: dismissBlockingDialogsAndConfirmClear,
                hasOpenOrderOrResultDialog: hasOpenOrderOrResultDialog,
                clearStaleDialogsBeforeNewSignal: clearStaleDialogsBeforeNewSignal,
                isBrokerErrorText: isBrokerErrorText,
                findOrderFormErrorBanner: findOrderFormErrorBanner,
                findBlockingBrokerErrorInDialogs: findBlockingBrokerErrorInDialogs,
                collectTicketIds: collectTicketIds,
                cleanPriceString: cleanPriceString,
                extractDealSummary: extractDealSummary,
                inspectOrderExecutionType: inspectOrderExecutionType,
                ensureExecutionTypeForSignal: ensureExecutionTypeForSignal,
                verifySelectedInstrumentAndOrderForm: verifySelectedInstrumentAndOrderForm
              };

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
