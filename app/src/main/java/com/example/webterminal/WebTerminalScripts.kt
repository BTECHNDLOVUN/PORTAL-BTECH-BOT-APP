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
          try {
            if (typeof el.click === 'function') {
              el.click();
            } else {
              const rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
              const cx = rect ? (rect.left + rect.width / 2) : 0;
              const cy = rect ? (rect.top + rect.height / 2) : 0;
              el.dispatchEvent(new view.MouseEvent('click', {
                bubbles: true,
                cancelable: true,
                composed: true,
                view: view,
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

        function openMT4ContextMenu(target) {
          if (!target && typeof document === 'undefined') return false;
          try {
            // RoboTrader Part 2 A exact Market Watch first-row selector, falling back to first visible row or panel
            let dispatchTarget = null;
            try {
              dispatchTarget = document.querySelector(
                'body > div.page-window.market-watch.compact > div > div.b > div.page-block > div > table > tbody > tr:nth-child(1)'
              );
            } catch (_) {}
            if (!dispatchTarget && target && target.querySelector) {
              dispatchTarget = target.querySelector("table tbody tr:first-child, tr, [role='row']") || target;
            }
            if (!dispatchTarget) dispatchTarget = target;
            if (!dispatchTarget) return false;

            const view = getViewFor(dispatchTarget);
            const rect = dispatchTarget.getBoundingClientRect ? dispatchTarget.getBoundingClientRect() : null;
            const cx = rect ? (rect.left + Math.min(40, Math.max(1, rect.width / 2))) : 0;
            const cy = rect ? (rect.top + Math.min(20, Math.max(1, rect.height / 2))) : 0;

            dispatchTarget.dispatchEvent(
              new view.MouseEvent('mousedown', {
                bubbles: true,
                cancelable: false,
                composed: true,
                view: view,
                button: 2,
                buttons: 2,
                clientX: cx,
                clientY: cy
              })
            );
            dispatchTarget.dispatchEvent(
              new view.MouseEvent('mouseup', {
                bubbles: true,
                cancelable: false,
                composed: true,
                view: view,
                button: 2,
                buttons: 0,
                clientX: cx,
                clientY: cy
              })
            );
            dispatchTarget.dispatchEvent(
              new view.MouseEvent('contextmenu', {
                bubbles: true,
                cancelable: true,
                composed: true,
                view: view,
                button: 2,
                buttons: 0,
                clientX: cx,
                clientY: cy
              })
            );
            return true;
          } catch (_) {
            return false;
          }
        }

        // RoboTrader Part 2 B: preserves positional 7th-child check ONLY when visible label reads "Show All",
        // otherwise locates visible "Show All" menu item within open context menus first before broad search.
        function findMT4ShowAllMenuItem() {
          try {
            const positional = document.querySelector(
              'body > div.page-menu.context.expanded > div > div > span.box > span > div:nth-child(7)'
            );
            if (positional && isRenderedVisible(positional)) {
              const txt = (positional.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
              if (txt === 'show all') return positional;
            }
          } catch (_) {}
          return (
            findExactVisibleText('Show All', ".page-menu [role='menuitem'], .page-menu span, .page-menu div, [role='menu'] [role='menuitem'], [role='menu'] span, [role='menu'] div") ||
            findExactVisibleText('Show All', "[role='menuitem'], button, li, a") ||
            findExactVisibleText('Show All', "span, div")
          );
        }

        function findMT4SymbolsMenuItem() {
          return (
            findExactVisibleText('Symbols', ".page-menu [role='menuitem'], .page-menu span, .page-menu div, [role='menu'] [role='menuitem'], [role='menu'] span, [role='menu'] div") ||
            findExactVisibleText('Symbols...', ".page-menu [role='menuitem'], .page-menu span, .page-menu div, [role='menu'] [role='menuitem'], [role='menu'] span, [role='menu'] div") ||
            findExactVisibleText('Symbols', "[role='menuitem'], button, li, a") ||
            findExactVisibleText('Symbols...', "[role='menuitem'], button, li, a") ||
            findExactVisibleText('Symbols', "span, div") ||
            findExactVisibleText('Symbols...', "span, div")
          );
        }

        function clickMT4MenuItem(el) {
          if (!el || !isRenderedVisible(el)) return false;
          try {
            const view = getViewFor(el);
            el.dispatchEvent(new view.MouseEvent('mouseover', { bubbles: true, cancelable: true, view: view }));
          } catch (_) {}
          return clickElement(el);
        }

        function clickMT4ShowAllMenuItem(el) {
          return clickMT4MenuItem(el);
        }

        function findMT4SymbolsDialog() {
          const candidates = deepAll("[role='dialog'], [role='alertdialog'], .page-window, [class*='dialog' i], [class*='modal' i], [class*='symbols' i]").filter(isRenderedVisible);
          return candidates.find(function(el) {
            const cls = ((el.className || '') + ' ' + (el.getAttribute && el.getAttribute('aria-label') || '')).toLowerCase();
            if (cls.includes('market-watch')) return false;
            const txt = (el.textContent || '').toLowerCase();
            if (txt.includes('market watch') && txt.includes('balance')) return false;
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

        function findMarketWatchPanel() {
          try {
            const exactMw = document.querySelector('body > div.page-window.market-watch.compact');
            if (exactMw && isRenderedVisible(exactMw)) return exactMw;
          } catch (_) {}

          const marketWatchLabel =
            deepAll("[class*='market-watch' i], [data-testid*='market-watch' i], [aria-label*='Market Watch' i]").find(isRenderedVisible) ||
            findExactVisibleText('Market Watch', "div, span, button, label, th, h1, h2, h3, [role='heading']") ||
            null;

          let marketWatchPanel = marketWatchLabel;
          for (let i = 0; i < 5 && marketWatchPanel; i++) {
            if (marketWatchPanel.querySelectorAll) {
              const rowCount = marketWatchPanel.querySelectorAll("tr, [role='row'], [data-symbol], li, [class*='symbol']").length;
              const panelText = (marketWatchPanel.textContent || '').toLowerCase();
              if (panelText.includes('market watch') && (rowCount > 0 || i >= 2)) {
                break;
              }
            }
            marketWatchPanel = marketWatchPanel.parentElement;
          }
          return marketWatchPanel || marketWatchLabel;
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
            // Must look like an instrument ticker: uppercase pair/commodity/index or prefixed/suffixed symbol
            const looksLikeInstrument =
              /^(?:[A-Z]{6}(?:[._#-][A-Za-z0-9]+|[mc])?|XAU|XAG|GOLD|SILVER|BTC|ETH|LTC|XRP|SOL|US30|US500|NAS100|USTEC|DE30|DE40|GER40|UK100|SPX500|JP225|USOIL|UKOIL|XTIUSD|XBRUSD|XNGUSD)/i.test(clean) ||
              (/[._#][A-Za-z0-9]{1,6}$/.test(clean) && /^[A-Z0-9]{2,10}/.test(clean)) ||
              (/^#[A-Za-z0-9._-]{2,14}$/.test(clean)) ||
              (/^[A-Z]{2,6}\d{2,4}$/.test(clean));
            if (!looksLikeInstrument) return;
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
    fun buildCancelTradeScript(): String {
        return """
            (function() {
              window.__btechTradeCancelled = true;
              window.__btechTradeRunId = (window.__btechTradeRunId || 0) + 1;
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

              async function waitForSymbol(symbol, timeoutMs, scopeRoot, aliases, stopOnNoSymbols) {
                const start = Date.now();
                let seenNoSymbolsAt = 0;
                while (Date.now() - start < timeoutMs) {
                  if (isCancelled()) return { cancelled: true };
                  const hit = findSymbolElement(symbol, scopeRoot, aliases);
                  if (hit) return hit;
                  if (stopOnNoSymbols && hasMt5NoSymbolsFound()) {
                    if (!seenNoSymbolsAt) seenNoSymbolsAt = Date.now();
                    if (Date.now() - seenNoSymbolsAt >= POLL_MS) {
                      return { noSymbolsFound: true };
                    }
                  } else {
                    seenNoSymbolsAt = 0;
                  }
                  await wait(POLL_MS);
                }
                return null;
              }

              async function discoverMT5Symbol(targetSymbol) {
                const requested = String(targetSymbol || '').trim();
                if (!requested) {
                  return { success: false, platform: 'MT5', error: 'No symbol was supplied', discoveredSymbols: [] };
                }

                const searchSelectors = [
                  "input[placeholder='Search symbol']",
                  "input[placeholder*='Search symbol' i]",
                  "input[aria-label*='Search symbol' i]",
                  "input[placeholder*='search' i]",
                  "input[aria-label*='search' i]",
                  "input[type='search']"
                ];

                function findMT5SearchField() {
                  for (let i = 0; i < searchSelectors.length; i++) {
                    const hit = deepAll(searchSelectors[i]).find(isVisible);
                    if (hit) return hit;
                  }
                  return null;
                }

                let searchInput = findMT5SearchField();
                if (!searchInput) {
                  const toggleEl = deepAll('button, [role="button"], [role="tab"], a').filter(isVisible).find(function(b) {
                    const t = ((b.innerText || b.textContent || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                    return t.includes('quotes') || t.includes('market watch') || t.includes('search symbol') || t.includes('add symbol');
                  });
                  if (toggleEl) {
                    singleClick(toggleEl);
                    await wait(Math.min(350, POLL_MS + 150));
                    if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                    searchInput = findMT5SearchField();
                  }
                }

                if (!searchInput) {
                  return {
                    success: false,
                    platform: 'MT5',
                    requestedSymbol: requested,
                    error: 'MT5 symbol search field not found',
                    discoveredSymbols: discoverAllBrokerSymbols()
                  };
                }

                if (!setNativeInputValue(searchInput, requested)) {
                  return {
                    success: false,
                    platform: 'MT5',
                    requestedSymbol: requested,
                    error: 'Could not enter symbol in MT5 search field',
                    discoveredSymbols: discoverAllBrokerSymbols()
                  };
                }

                let result = await waitForSymbol(requested, 1800, null, VERIFIED_ALIASES, true);
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
                    setNativeInputValue(searchInput, aliasTarget);
                    result = await waitForSymbol(requested, 1500, null, VERIFIED_ALIASES, true);
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

                const addAction = inspectMt5SearchResultAction(result.element);
                const hasClearBtn = !!findMt5ClearSearchButton(searchInput);
                let addedViaPlus = false;
                let alreadyChecked = false;

                if (addAction.state === 'PLUS' && addAction.control) {
                  singleClick(addAction.control);
                  addedViaPlus = true;
                  await wait(Math.min(350, POLL_MS * 2 + 120));
                  if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                } else if (addAction.state === 'CHECK') {
                  alreadyChecked = true;
                }

                let selectedRow = result.element;
                let confirmedInWatchlist = false;
                if (addAction.state !== 'NONE' || hasClearBtn) {
                  clearMt5SearchBox(searchInput);
                  await wait(Math.min(350, POLL_MS * 2 + 120));
                  if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };

                  let watchlistHit = findMt5DisplayedWatchlistRow(result.actualSymbol, VERIFIED_ALIASES);
                  const wStart = Date.now();
                  while (!watchlistHit && Date.now() - wStart < 1200) {
                    if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true, error: 'Discovery cancelled' };
                    await wait(POLL_MS);
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
                        await wait(Math.min(100, POLL_MS));
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
                    // Virtualized/unadded fallback: re-enter symbol in Search symbol, click unrecognized '+' icon if present, and select row directly
                    setNativeInputValue(searchInput, result.actualSymbol);
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
                      await wait(Math.min(350, POLL_MS * 2 + 120));
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

                await wait(Math.min(250, POLL_MS + 100));
                const chartState = verifyMt5ActiveChartSymbol(result.actualSymbol, VERIFIED_ALIASES);
                if (chartState.state === 'MISMATCH') {
                  clickElement(selectedRow);
                  await wait(Math.min(250, POLL_MS + 100));
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
                  discoveredSymbols: discoverAllBrokerSymbols(),
                  message: 'Matching MT5 symbol verified and selected before trading'
                };
              }

              async function discoverMT4Symbol(targetSymbol) {
                const requested = String(targetSymbol || '').trim();
                if (!requested) {
                  return { success: false, platform: 'MT4', error: 'No symbol was supplied', discoveredSymbols: [] };
                }

                let marketWatchPanel = findMarketWatchPanel();
                if (!marketWatchPanel) {
                  const mwToggle = deepAll('button, [role="button"], [title*="Market Watch" i], [aria-label*="Market Watch" i], a').filter(isVisible).find(function(b) {
                    const t = ((b.innerText || b.textContent || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                    return t.includes('market watch');
                  });
                  if (mwToggle) {
                    singleClick(mwToggle);
                    await wait(Math.min(350, POLL_MS + 150));
                    marketWatchPanel = findMarketWatchPanel();
                  }
                }

                let result = findMT4MarketWatchSymbolRow(requested, marketWatchPanel, VERIFIED_ALIASES);
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

                  openMT4ContextMenu(marketWatchPanel);
                  await wait(Math.min(300, POLL_MS + 50));
                  if (isCancelled()) {
                    return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };
                  }

                  const symbolsItem = findMT4SymbolsMenuItem();
                  const showAll = findMT4ShowAllMenuItem();

                  if (symbolsItem) {
                    usedSymbolsDialog = true;
                    if (!clickMT4MenuItem(symbolsItem)) {
                      return {
                        success: false,
                        platform: 'MT4',
                        requestedSymbol: requested,
                        error: 'Could not open MT4 Symbols command',
                        discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel)
                      };
                    }
                    await wait(Math.min(350, POLL_MS + 100));
                    if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };

                    let dialog = findMT4SymbolsDialog();
                    const dStart = Date.now();
                    while (!dialog && Date.now() - dStart < 2500) {
                      if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };
                      await wait(POLL_MS);
                      dialog = findMT4SymbolsDialog();
                    }

                    if (dialog) {
                      for (let pass = 0; pass < 3; pass++) {
                        const toggled = expandMT4SymbolGroups(dialog);
                        if (!toggled) break;
                        await wait(Math.min(200, POLL_MS + 50));
                      }
                      if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };

                      const dlgHit = findSymbolElement(requested, dialog, VERIFIED_ALIASES);
                      if (dlgHit) {
                        singleClick(dlgHit.element);
                        await wait(Math.min(180, POLL_MS + 30));
                        if (!isMT4DialogRowAlreadyShown(dlgHit.element)) {
                          const showBtn = findExactVisibleText('Show', "button, [role='button']", dialog);
                          if (showBtn && !showBtn.disabled) {
                            singleClick(showBtn);
                          } else {
                            doubleClickMT4Row(dlgHit.element);
                          }
                          await wait(Math.min(250, POLL_MS + 50));
                        }
                      }
                      closeMT4SymbolsDialog(dialog);
                    }
                  } else if (showAll) {
                    if (!clickMT4ShowAllMenuItem(showAll)) {
                      return {
                        success: false,
                        platform: 'MT4',
                        requestedSymbol: requested,
                        error: 'Could not activate MT4 Show All',
                        discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel)
                      };
                    }
                  } else {
                    return {
                      success: false,
                      platform: 'MT4',
                      requestedSymbol: requested,
                      error: 'MT4 Symbols or Show All menu option not found',
                      discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel)
                    };
                  }

                  await wait(Math.min(500, POLL_MS * 2 + 100));
                  const mwStart = Date.now();
                  const initialWaitMs = usedSymbolsDialog ? 2500 : 5000;
                  while (Date.now() - mwStart < initialWaitMs) {
                    if (isCancelled()) {
                      return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };
                    }
                    result = findMT4MarketWatchSymbolRow(requested, marketWatchPanel, VERIFIED_ALIASES);
                    if (result) break;
                    await wait(POLL_MS);
                  }

                  // Fallback: if Symbols dialog tree couldn't reveal the instrument, try Show All once
                  if (!result && usedSymbolsDialog) {
                    openMT4ContextMenu(marketWatchPanel);
                    await wait(Math.min(250, POLL_MS + 50));
                    const fallbackShowAll = findMT4ShowAllMenuItem();
                    if (fallbackShowAll && clickMT4ShowAllMenuItem(fallbackShowAll)) {
                      await wait(Math.min(400, POLL_MS + 100));
                      const fbStart = Date.now();
                      while (Date.now() - fbStart < 3500) {
                        if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true, error: 'Discovery cancelled' };
                        result = findMT4MarketWatchSymbolRow(requested, marketWatchPanel, VERIFIED_ALIASES);
                        if (result) break;
                        await wait(POLL_MS);
                      }
                    }
                  }
                }

                if (!result) {
                  return {
                    success: false,
                    platform: 'MT4',
                    requestedSymbol: requested,
                    error: 'error:Symbol ' + requested + ' not found',
                    discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel)
                  };
                }

                clickElement(result.element);
                const opened = doubleClickMT4Row(result.element);
                if (!opened) {
                  return {
                    success: false,
                    platform: 'MT4',
                    requestedSymbol: requested,
                    actualSymbol: result.actualSymbol,
                    error: 'Could not open the MT4 instrument row',
                    discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel)
                  };
                }

                return {
                  success: true,
                  platform: 'MT4',
                  broker: BROKER,
                  server: SERVER,
                  requestedSymbol: requested,
                  actualSymbol: result.actualSymbol,
                  matchRank: result.matchRank,
                  matchType: result.matchType,
                  usedSymbolsDialog: usedSymbolsDialog,
                  doubleClickSent: true,
                  discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel),
                  message: 'Matching MT4 Market Watch row selected; verify the order ticket before trading'
                };
              }

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
                openMT4ContextMenu: openMT4ContextMenu,
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

              $DEEP_DOM_HELPERS
              $ROBOTRADER_DISCOVERY_HELPERS

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

              function emitSymbolDiscovered(res) {
                if (isCancelled()) return;
                if (window.BtechBridge && typeof window.BtechBridge.onSymbolDiscovered === 'function') {
                  try {
                    window.BtechBridge.onSymbolDiscovered(JSON.stringify({
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
                    window.BtechBridge.onTradeResult(JSON.stringify(payload));
                  } catch (_) {}
                }
              }

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
                while (Date.now() - start < timeoutMs) {
                  if (isCancelled()) return null;
                  const result = findSymbolElement(targetSym, scopeRoot, aliases);
                  if (result) return result;
                  if (stopOnNoSymbols && hasMt5NoSymbolsFound()) {
                    if (!seenNoSymbolsAt) seenNoSymbolsAt = Date.now();
                    if (Date.now() - seenNoSymbolsAt >= 200) {
                      return { noSymbolsFound: true };
                    }
                  } else {
                    seenNoSymbolsAt = 0;
                  }
                  await wait(250);
                }
                return null;
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

              // RoboTrader Part 2 H (exact recovered sequence adapted to safe BTECH DOM):
              // Checks Buy by Market / Sell by Market, then Show Trade Form (F9) title, then New Order text/aria/title.
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
                  const attr = normalize((node.getAttribute && node.getAttribute('title')) || '') + ' ' +
                    normalize((node.getAttribute && node.getAttribute('aria-label')) || '');
                  return attr.includes('Show Trade Form (F9)') ||
                    /show trade form|new order/i.test(attr);
                });
                if (titleTarget) {
                  const clickable = (titleTarget.closest && (titleTarget.closest('button') || titleTarget.closest('[role="button"]'))) || titleTarget;
                  singleClick(clickable);
                  return 'opened:title';
                }

                const textTargets = queryDeepAll('button, [role="button"], a, span, div').filter(isRenderedVisible);
                const newOrderTarget = textTargets.find(node => {
                  if (node.children && node.children.length > 3) return false;
                  if (node.closest && node.closest('table, tr, [role="row"]')) return false;
                  const t = normalize(node.innerText || node.textContent).toLowerCase();
                  return t === 'new order' || t === 'place order' || t === 'show trade form';
                });
                if (newOrderTarget) {
                  const clickable =
                    (newOrderTarget.closest && (newOrderTarget.closest('button') || newOrderTarget.closest('[role="button"]'))) ||
                    (newOrderTarget.parentElement && newOrderTarget.parentElement.closest && (newOrderTarget.parentElement.closest('button') || newOrderTarget.parentElement.closest('[role="button"]'))) ||
                    newOrderTarget;
                  if (clickable) {
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
                const typeSelectors = scope
                  ? Array.from(scope.querySelectorAll("select, [role='combobox'], [class*='select' i], [class*='type' i], button"))
                  : [];
                for (let i = 0; i < typeSelectors.length; i++) {
                  const el = typeSelectors[i];
                  if (!isRenderedVisible(el)) continue;
                  let txt = '';
                  if (el.tagName === 'SELECT' && el.options && el.options[el.selectedIndex >= 0 ? el.selectedIndex : 0]) {
                    txt = el.options[el.selectedIndex >= 0 ? el.selectedIndex : 0].textContent || el.value || '';
                  } else {
                    txt = el.innerText || el.textContent || '';
                  }
                  txt = txt.replace(/\s+/g, ' ').trim().toLowerCase();
                  if (!txt || txt.length > 48) continue;
                  if (/^(?:market execution|instant execution|exchange execution|request execution|buy limit|sell limit|buy stop|sell stop|buy stop limit|sell stop limit)$/.test(txt)) {
                    selectedTypeText = txt;
                    break;
                  }
                }
                if (/\b(?:limit|stop)\b/.test(selectedTypeText)) {
                  const pendingDir = selectedTypeText.includes('sell') ? 'SELL' : (selectedTypeText.includes('buy') ? 'BUY' : '');
                  return { mode: 'PENDING', rawType: selectedTypeText, pendingDirection: pendingDir };
                }
                return { mode: 'MARKET', rawType: selectedTypeText || 'market execution', pendingDirection: '' };
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

              // --- SEPARATE MT5 DISCOVERY (Search symbol -> + / check -> Watchlist or Search fallback -> Chart verify) ---
              async function discoverMT5Symbol(targetSymbol) {
                const requested = String(targetSymbol || '').trim();
                if (!requested) {
                  return { success: false, platform: 'MT5', error: 'No symbol was supplied', discoveredSymbols: [] };
                }

                // RoboTrader Part 2 G: exact "Search symbol" selector first, plus fallback selectors
                const searchSelectors = [
                  "input[placeholder='Search symbol']",
                  "input[placeholder*='Search symbol' i]",
                  "input[aria-label*='Search symbol' i]",
                  "input[placeholder*='search' i]",
                  "input[aria-label*='search' i]",
                  "input[type='search']"
                ];

                function findMT5SearchField() {
                  for (let i = 0; i < searchSelectors.length; i++) {
                    const hit = deepAll(searchSelectors[i]).find(isVisible);
                    if (hit) return hit;
                  }
                  return null;
                }

                let searchInput = findMT5SearchField();
                if (!searchInput) {
                  setPhase('SYMBOL_SEARCHING', 'Locating MT5 Search symbol input for ' + requested + '...');
                  const waitSearchStart = Date.now();
                  let toggledQuotes = false;
                  while (!searchInput && Date.now() - waitSearchStart < 1800) {
                    if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };
                    if (!toggledQuotes) {
                      const quotesOrSearchBtn = findButton(['quotes', 'market watch', 'search symbol', 'add symbol', 'symbols']);
                      if (quotesOrSearchBtn) {
                        singleClick(quotesOrSearchBtn);
                        toggledQuotes = true;
                      }
                    }
                    await wait(250);
                    searchInput = findMT5SearchField();
                  }
                }

                if (!searchInput) {
                  return {
                    success: false,
                    platform: 'MT5',
                    requestedSymbol: requested,
                    error: 'MT5 symbol search field not found',
                    discoveredSymbols: discoverAllBrokerSymbols()
                  };
                }

                if (searchInput.value && normalise(searchInput.value) !== normalise(requested)) {
                  clearMt5SearchBox(searchInput);
                  await wait(120);
                  if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };
                }

                setPhase('SYMBOL_SEARCHING', 'Searching ' + requested + ' in MT5 Quotes...');
                if (!setNativeInputValue(searchInput, requested)) {
                  return {
                    success: false,
                    platform: 'MT5',
                    requestedSymbol: requested,
                    error: 'Could not enter symbol in MT5 search field',
                    discoveredSymbols: discoverAllBrokerSymbols()
                  };
                }
                dispatchEnter(searchInput);

                let result = await waitForSymbol(requested, 2600, null, VERIFIED_ALIASES, true);
                if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };
                if (result && result.noSymbolsFound) result = null;

                if (!result && VERIFIED_ALIASES && typeof VERIFIED_ALIASES === 'object') {
                  const aliasKey = Object.keys(VERIFIED_ALIASES).find(function(k) {
                    return normalise(k) === normalise(requested);
                  });
                  const aliasTarget = aliasKey ? String(VERIFIED_ALIASES[aliasKey] || '').trim() : '';
                  if (aliasTarget && normalise(aliasTarget) !== normalise(requested)) {
                    setPhase('SYMBOL_SEARCHING', 'Checking verified alias ' + aliasTarget + ' on MT5...');
                    setNativeInputValue(searchInput, aliasTarget);
                    dispatchEnter(searchInput);
                    result = await waitForSymbol(requested, 1800, null, VERIFIED_ALIASES, true);
                    if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };
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

                setPhase('SYMBOL_SEARCHING', 'Verifying ' + result.actualSymbol + ' in MT5 Quotes...');
                const addAction = inspectMt5SearchResultAction(result.element);
                const hasClearBtn = !!findMt5ClearSearchButton(searchInput);
                let addedViaPlus = false;
                let alreadyChecked = false;

                if (addAction.state === 'PLUS' && addAction.control) {
                  singleClick(addAction.control);
                  addedViaPlus = true;
                  await wait(300);
                  if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };
                } else if (addAction.state === 'CHECK') {
                  alreadyChecked = true;
                }

                let selectedRow = result.element;
                let confirmedInWatchlist = false;
                if (addAction.state !== 'NONE' || hasClearBtn) {
                  clearMt5SearchBox(searchInput);
                  await wait(250);
                  if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };

                  let watchlistHit = findMt5DisplayedWatchlistRow(result.actualSymbol, VERIFIED_ALIASES);
                  const wStart = Date.now();
                  while (!watchlistHit && Date.now() - wStart < 1200) {
                    if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };
                    await wait(200);
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
                        await wait(70);
                        if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };
                        watchlistHit = findMt5DisplayedWatchlistRow(result.actualSymbol, VERIFIED_ALIASES);
                      }
                      if (!watchlistHit) box.scrollTop = prevTop;
                    }
                  }

                  if (watchlistHit) {
                    selectedRow = watchlistHit.element;
                    confirmedInWatchlist = true;
                  } else {
                    // Virtualized/unadded fallback: re-enter symbol in Search symbol, click unrecognized '+' icon if present, and select row directly
                    setPhase('SYMBOL_SEARCHING', 'Selecting ' + result.actualSymbol + ' from MT5 search...');
                    setNativeInputValue(searchInput, result.actualSymbol);
                    dispatchEnter(searchInput);
                    let retryHit = await waitForSymbol(result.actualSymbol, 1800, null, VERIFIED_ALIASES, true);
                    if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };
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
                      await wait(250);
                      if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };
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

                await wait(250);
                if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };

                const chartState = verifyMt5ActiveChartSymbol(result.actualSymbol, VERIFIED_ALIASES);
                if (chartState.state === 'MISMATCH') {
                  clickElement(selectedRow);
                  await wait(250);
                  if (isCancelled()) return { success: false, platform: 'MT5', cancelled: true };
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
                  requestedSymbol: requested,
                  actualSymbol: result.actualSymbol,
                  matchRank: result.matchRank,
                  matchType: result.matchType,
                  addedViaPlus: addedViaPlus,
                  alreadyChecked: alreadyChecked,
                  confirmedInWatchlist: confirmedInWatchlist,
                  resultClicked: true,
                  element: selectedRow,
                  discoveredSymbols: discoverAllBrokerSymbols(),
                  message: 'Matching MT5 symbol verified and selected before trading'
                };
              }

              // --- SEPARATE MT4 DISCOVERY (Market Watch -> Context Menu -> Symbols dialog / Show All -> Double Click row) ---
              // NEVER falls back to MT5 search-field method and NEVER requires literal "Show All"!
              async function discoverMT4Symbol(targetSymbol) {
                const requested = String(targetSymbol || '').trim();
                if (!requested) {
                  return { success: false, platform: 'MT4', error: 'No symbol was supplied', discoveredSymbols: [] };
                }

                setPhase('SYMBOL_SEARCHING', 'Scanning MT4 Market Watch for ' + requested + '...');
                let marketWatchPanel = findMarketWatchPanel();
                if (!marketWatchPanel) {
                  const waitMwStart = Date.now();
                  let clickedMwToggle = false;
                  while (!marketWatchPanel && Date.now() - waitMwStart < 1800) {
                    if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true };
                    if (!clickedMwToggle) {
                      const mwToggle = deepAll('button, [role="button"], [title*="Market Watch" i], [aria-label*="Market Watch" i], a').filter(isVisible).find(function(b) {
                        const t = ((b.innerText || b.textContent || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                        return t.includes('market watch');
                      });
                      if (mwToggle) {
                        singleClick(mwToggle);
                        clickedMwToggle = true;
                      }
                    }
                    await wait(250);
                    marketWatchPanel = findMarketWatchPanel();
                  }
                }

                let result = findMT4MarketWatchSymbolRow(requested, marketWatchPanel, VERIFIED_ALIASES);
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

                  setPhase('SYMBOL_SEARCHING', 'Opening MT4 Symbols / Show All for ' + requested + '...');
                  openMT4ContextMenu(marketWatchPanel);
                  await wait(300);
                  if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true };

                  const symbolsItem = findMT4SymbolsMenuItem();
                  const showAll = findMT4ShowAllMenuItem();

                  if (symbolsItem) {
                    usedSymbolsDialog = true;
                    if (!clickMT4MenuItem(symbolsItem)) {
                      return {
                        success: false,
                        platform: 'MT4',
                        requestedSymbol: requested,
                        error: 'Could not open MT4 Symbols command',
                        discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel)
                      };
                    }
                    await wait(250);
                    if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true };

                    let dialog = findMT4SymbolsDialog();
                    const dStart = Date.now();
                    while (!dialog && Date.now() - dStart < 1800) {
                      if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true };
                      await wait(200);
                      dialog = findMT4SymbolsDialog();
                    }
                    if (dialog) {
                      for (let pass = 0; pass < 3; pass++) {
                        const toggled = expandMT4SymbolGroups(dialog);
                        if (!toggled) break;
                        await wait(180);
                      }
                      if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true };

                      const dlgHit = findSymbolElement(requested, dialog, VERIFIED_ALIASES);
                      if (dlgHit) {
                        singleClick(dlgHit.element);
                        await wait(150);
                        if (!isMT4DialogRowAlreadyShown(dlgHit.element)) {
                          const showBtn = findExactVisibleText('Show', "button, [role='button']", dialog);
                          if (showBtn && !showBtn.disabled) {
                            singleClick(showBtn);
                          } else {
                            doubleClickMT4Row(dlgHit.element);
                          }
                          await wait(220);
                        }
                      }
                      closeMT4SymbolsDialog(dialog);
                    }
                  } else if (showAll) {
                    if (!clickMT4ShowAllMenuItem(showAll)) {
                      return {
                        success: false,
                        platform: 'MT4',
                        requestedSymbol: requested,
                        error: 'Could not activate MT4 Show All',
                        discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel)
                      };
                    }
                  } else {
                    return {
                      success: false,
                      platform: 'MT4',
                      requestedSymbol: requested,
                      error: 'MT4 Symbols or Show All menu option not found',
                      discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel)
                    };
                  }

                  setPhase('SYMBOL_SEARCHING', 'Refreshing MT4 Market Watch for ' + requested + '...');
                  await wait(350);
                  if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true };

                  const mwStart = Date.now();
                  const initialWaitMs = usedSymbolsDialog ? 2000 : 3500;
                  while (Date.now() - mwStart < initialWaitMs) {
                    if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true };
                    result = findMT4MarketWatchSymbolRow(requested, marketWatchPanel, VERIFIED_ALIASES);
                    if (result) break;
                    await wait(250);
                  }

                  if (!result && usedSymbolsDialog) {
                    setPhase('SYMBOL_SEARCHING', 'Running MT4 Show All fallback for ' + requested + '...');
                    openMT4ContextMenu(marketWatchPanel);
                    await wait(250);
                    if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true };
                    const fallbackShowAll = findMT4ShowAllMenuItem();
                    if (fallbackShowAll && clickMT4ShowAllMenuItem(fallbackShowAll)) {
                      await wait(300);
                      const fbStart = Date.now();
                      while (Date.now() - fbStart < 2500) {
                        if (isCancelled()) return { success: false, platform: 'MT4', cancelled: true };
                        result = findMT4MarketWatchSymbolRow(requested, marketWatchPanel, VERIFIED_ALIASES);
                        if (result) break;
                        await wait(250);
                      }
                    }
                  }
                }

                if (!result) {
                  return {
                    success: false,
                    platform: 'MT4',
                    requestedSymbol: requested,
                    error: 'error:Symbol ' + requested + ' not found',
                    discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel)
                  };
                }

                clickElement(result.element);
                const opened = doubleClickMT4Row(result.element);
                if (!opened) {
                  return {
                    success: false,
                    platform: 'MT4',
                    requestedSymbol: requested,
                    actualSymbol: result.actualSymbol,
                    error: 'Could not open the MT4 instrument row',
                    discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel)
                  };
                }

                return {
                  success: true,
                  platform: 'MT4',
                  requestedSymbol: requested,
                  actualSymbol: result.actualSymbol,
                  matchRank: result.matchRank,
                  matchType: result.matchType,
                  usedSymbolsDialog: usedSymbolsDialog,
                  doubleClickSent: true,
                  element: result.element,
                  discoveredSymbols: discoverAllBrokerSymbols(marketWatchPanel),
                  message: 'Matching MT4 Market Watch row selected; verify the order ticket before trading'
                };
              }

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

              function extractDealSummary(text, prevTickets, tradeIdx, statusText) {
                const combinedText = (text || '') + '\n' + collectTradeRowText();
                const newTicket = extractFirstNewTicket(combinedText, prevTickets);
                const dirWord = DIRECTION === 'SELL' ? 'sell' : 'buy';
                const detailRe = new RegExp('(#\\d{4,})[^\\n]{0,35}\\b(buy|sell)\\b[^\\n]{0,35}\\b([\\d.]+)\\b[^\\n]{0,25}\\bat\\s+([\\d.,\\s]+)', 'i');
                const detailMatch = combinedText.match(detailRe);
                if (detailMatch && (!prevTickets.has(detailMatch[1]))) {
                  return 'Trade ' + tradeIdx + '/' + COUNT + ' confirmed · ' + detailMatch[1] + ' ' + detailMatch[2].toLowerCase() + ' ' + detailMatch[3] + ' ' + activeSymbolName + ' at ' + detailMatch[4].trim();
                }
                if (newTicket) {
                  return 'Trade ' + tradeIdx + '/' + COUNT + ' confirmed · ' + newTicket + ' ' + dirWord + ' ' + LOT + ' ' + activeSymbolName;
                }
                if (statusText) {
                  return 'Trade ' + tradeIdx + '/' + COUNT + ' confirmed · ' + statusText.slice(0, 120);
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

                return {
                  volInput: volInput,
                  slInput: slInput,
                  tpInput: tpInput,
                  commentInput: commentInput
                };
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

              // Safety guard: distinguishes real result/order-entry modals from open positions/pending-orders rows
              function isInsidePositionOrOrderTable(el) {
                return !!(el && el.closest && el.closest('table, tbody, tr, [role="row"], [class*="position"], [class*="positions"], [class*="history"], [class*="deal"], [class*="orders"]'));
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
                return !!(el && el.closest && el.closest('[role="dialog"], [role="alertdialog"], [class*="modal"], [class*="dialog"], [class*="receipt"], [class*="popup"], [class*="trade-form"], [class*="order-ticket"], [class*="page-window"], [class*="alert"]'));
              }

              function findScopedResultOkButton() {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const btns = queryDeepAll('button, [role="button"], input[type="button"], input[type="submit"]').filter(isVisible);
                return btns.find(b => {
                  if (isInsidePositionOrOrderTable(b)) return false;
                  const txt = normalize(b.innerText || b.value || b.textContent || b.getAttribute('aria-label'));
                  if (txt !== 'OK') return false;
                  return isDialogLikeContainer(b) || btns.filter(x => !isInsidePositionOrOrderTable(x)).length <= 4;
                }) || null;
              }

              // RoboTrader Part 2 M exact recovered dismissal behavior, scoped safely so open positions/orders
              // are never closed.
              function dismissTradeResult() {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const buttons = queryDeepAll('button').filter(isVisible);
                const okButton = buttons.find(button => {
                  if (isInsidePositionOrOrderTable(button)) return false;
                  return normalize(button.innerText || button.textContent) === 'OK';
                });
                if (okButton) {
                  singleClick(okButton);
                  return 'dismissed:ok';
                }
                const closeButton = buttons.find(button => {
                  if (isInsidePositionOrOrderTable(button)) return false;
                  if (!isDialogLikeContainer(button)) return false;
                  const text = normalize(button.innerText || button.textContent || button.getAttribute('aria-label'));
                  if (isUnsafePositionActionLabel(text)) return false;
                  return text === 'Close' || text === 'Done' || text === 'Cancel';
                });
                if (closeButton) {
                  singleClick(closeButton);
                  return 'dismissed:close';
                }
                return 'dismissed:none';
              }

              function findStaleOrderEntryCloseButton() {
                const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
                const orderContainers = queryDeepAll('[role="dialog"], [role="alertdialog"], [class*="dialog"], [class*="modal"], [class*="trade-form"], [class*="order-ticket"], [class*="order-panel"], [class*="page-window"]').filter(isVisible);
                for (let i = 0; i < orderContainers.length; i++) {
                  const box = orderContainers[i];
                  const boxLower = (box.textContent || '').toLowerCase();
                  if ((boxLower.includes('market watch') || boxLower.includes('search symbol') || (boxLower.includes('balance') && boxLower.includes('equity'))) && !boxLower.includes('buy by market') && !boxLower.includes('sell by market')) continue;
                  const looksLikeOrderOrResult =
                    boxLower.includes('buy by market') ||
                    boxLower.includes('sell by market') ||
                    boxLower.includes('instant execution') ||
                    boxLower.includes('market execution') ||
                    !!box.querySelector('#volume, input[name="volume"], .trade-input');
                  if (!looksLikeOrderOrResult) continue;
                  const btns = Array.from(box.querySelectorAll('button, [role="button"], [aria-label*="close" i], [title*="close" i]')).filter(isVisible);
                  const closeBtn = btns.find(b => {
                    if (isInsidePositionOrOrderTable(b)) return false;
                    const t = normalize(b.innerText || b.textContent || b.getAttribute('aria-label') || b.getAttribute('title'));
                    if (isUnsafePositionActionLabel(t)) return false;
                    const tl = t.toLowerCase();
                    return tl === 'close' || tl === 'cancel' || tl === 'done' || tl === 'ok' || tl === '×' || tl === 'x' || tl === 'close order' || tl === 'close form';
                  });
                  if (closeBtn) return closeBtn;
                }
                return null;
              }

              function hasOpenOrderOrResultDialog() {
                if (findScopedResultOkButton()) return true;
                const staleClose = findStaleOrderEntryCloseButton();
                if (staleClose) return true;
                return false;
              }

              // Closes ONLY stale order-entry and result dialogs from a previous signal (never open positions/pending orders),
              // and waits to confirm the old modal/dialog state cleared before proceeding.
              async function clearStaleDialogsBeforeNewSignal() {
                for (let step = 0; step < 5; step++) {
                  if (isCancelled()) return false;
                  const resultAction = dismissTradeResult();
                  if (resultAction !== 'dismissed:none') {
                    await wait(120);
                    continue;
                  }
                  const orderCloseBtn = findStaleOrderEntryCloseButton();
                  if (orderCloseBtn) {
                    singleClick(orderCloseBtn);
                    await wait(120);
                    continue;
                  }
                  break;
                }
                const start = Date.now();
                while (Date.now() - start < 1200) {
                  if (isCancelled()) return false;
                  if (!hasOpenOrderOrResultDialog()) return true;
                  await wait(100);
                }
                return !hasOpenOrderOrResultDialog();
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

              function executeSequentialTrades() {
                let currentTrade = 0;
                let confirmedCount = 0;

                function runNextTrade() {
                  if (isCancelled()) return;
                  currentTrade++;
                  setPhase('PREPARE', 'Preparing trade ' + currentTrade + '/' + COUNT + ' (' + DIRECTION + ' ' + LOT + ' ' + activeSymbolName + ')...');
                  dismissTradeResult();
                  ensureOrderPanelAndSubmit(0);
                }

                function ensureOrderPanelAndSubmit(openWaitTicks) {
                  if (isCancelled()) return;
                  dismissTradeResult();

                  const fields = populateOrderFields();
                  const actionBtn = findOrderActionButton(DIRECTION, fields.volInput);

                  if (!actionBtn || !fields.volInput) {
                    if (openWaitTicks === 0 || openWaitTicks === 5) {
                      if (PLATFORM === 'MT5') {
                        const openRes = openMT5TradeForm();
                        if (openRes === 'loading:trade_form_trigger_missing') {
                          const reopenBtn = findStrictNewOrderButton();
                          if (reopenBtn) singleClick(reopenBtn);
                          else dispatchF9TradeFormShortcut();
                        }
                      } else {
                        const reopenBtn = findStrictNewOrderButton();
                        if (reopenBtn) singleClick(reopenBtn);
                        else if (lastSelectedSymbolElement && isVisible(lastSelectedSymbolElement)) doubleClickMT4Row(lastSelectedSymbolElement);
                        else dispatchF9TradeFormShortcut();
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
                        rawMessage: partialErr,
                        retried: false
                      });
                      setPhase('ERROR', partialErr);
                      log(partialErr, true, true);
                    } else {
                      const msg = 'Order controls not ready (' + activeSymbolName + ' ' + DIRECTION + ' ' + LOT + '). Open New Order in ' + PLATFORM + ' Web Terminal to inspect.';
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
                    }
                    return;
                  }

                  const refreshedFields = populateOrderFields();
                  const finalBtn = findOrderActionButton(DIRECTION, refreshedFields.volInput) || actionBtn;

                  // Pre-click visible error node + ticket footer banner inspection (Screenshot 4 / RoboTrader Part 2 K)
                  const preError = findVisibleAlertOrErrorText(null) || findOrderFormErrorBanner(refreshedFields.volInput);
                  if (preError && isBrokerErrorText(preError)) {
                    const errMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected before submit: ' + preError;
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
                  function waitUntilReadyAndSubmitOnce() {
                    if (isCancelled()) return;
                    // Never force-enable a disabled trade button! Respect terminal disabled state and inspect validation errors.
                    if (isButtonBusyOrDisabled(finalBtn)) {
                      const bannerErr = findOrderFormErrorBanner(refreshedFields.volInput);
                      const invalidFieldErr = findInvalidFieldMessage() || bannerErr;
                      if (invalidFieldErr) {
                        const errMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected (' + confirmedCount + '/' + COUNT + ' opened): ' + invalidFieldErr;
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

                    setPhase('PREPARED', 'Verified & Prepared Trade ' + currentTrade + '/' + COUNT + ' (' + DIRECTION + ' ' + LOT + ' ' + verification.verifiedSymbol + ')');

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
                      const maxPolls = MAX_CONFIRM_POLLS;

                      function pollConfirmation() {
                        if (isCancelled()) return;
                        polls++;

                        const curText = collectDeepText();
                        const curLower = curText.toLowerCase();
                        const okBtn = findScopedResultOkButton();
                        const rawStatusText = extractMt5StatusText(okBtn);
                        const statusText = (rawStatusText && !baseAlertTexts.has(rawStatusText)) ? rawStatusText : '';

                        // 1. RoboTrader Part 2 L: inspect visible status/message/error/.alert nodes first for broker rejections
                        if (statusText && isBrokerErrorText(statusText)) {
                          dismissTradeResult();
                          const errMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected (' + confirmedCount + '/' + COUNT + ' opened): ' + statusText.slice(0, 160);
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
                            rawMessage: statusText,
                            retried: false
                          });
                          setPhase('ERROR', errMsg);
                          log(errMsg, true, true);
                          return;
                        }

                        // 2. Also inspect deep terminal text diff for broker rejection phrases
                        for (const term of BROKER_ERROR_TERMS) {
                          if (!baseErrorSet.has(term) && curLower.includes(term)) {
                            const lines = curText.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
                            const snippet = (lines.find(l => l.toLowerCase().includes(term)) || term).slice(0, 140);
                            dismissTradeResult();
                            const errMsg = 'Trade ' + currentTrade + '/' + COUNT + ' rejected (' + confirmedCount + '/' + COUNT + ' opened): ' + snippet;
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
                              rawMessage: snippet,
                              retried: false
                            });
                            setPhase('ERROR', errMsg);
                            log(errMsg, true, true);
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
                          confirmedCount++;
                          const summary = extractDealSummary(curText, baseTickets, currentTrade, statusText);
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
                          setPhase('TRADE_CONFIRMED:' + currentTrade + ':' + COUNT, summary);
                          log(summary, false, false);
                          dismissTradeResult();

                          if (currentTrade >= COUNT) {
                            const finalMsg = COUNT > 1
                              ? 'All ' + COUNT + '/' + COUNT + ' trades confirmed · ' + summary
                              : summary;
                            setPhase('DONE', finalMsg);
                            log(finalMsg, false, true);
                          } else {
                            log('Waiting 2s before Trade ' + (currentTrade + 1) + '/' + COUNT + '...', false, false);
                            schedule(runNextTrade, 2000);
                          }
                          return;
                        }

                        if (polls >= maxPolls) {
                          // RoboTrader no-blind-retry rule: report ambiguous timeout as UNKNOWN and stop immediately!
                          const unknownMsg = 'Trade ' + currentTrade + '/' + COUNT + ' status UNKNOWN (' + confirmedCount + '/' + COUNT + ' confirmed): error:Unable to confirm trade submission. The trade was not retried.';
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

              function waitForOrderControlsThenRun(tries) {
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
                if (tries === 0 || tries === 5) {
                  if (PLATFORM === 'MT5') {
                    const formStatus = openMT5TradeForm();
                    if (formStatus === 'loading:trade_form_trigger_missing') {
                      const newOrderBtn = findStrictNewOrderButton();
                      if (newOrderBtn) singleClick(newOrderBtn);
                      else dispatchF9TradeFormShortcut();
                    }
                  } else {
                    const newOrderBtn = findStrictNewOrderButton();
                    if (newOrderBtn) singleClick(newOrderBtn);
                    else if (lastSelectedSymbolElement && isVisible(lastSelectedSymbolElement)) doubleClickMT4Row(lastSelectedSymbolElement);
                    else dispatchF9TradeFormShortcut();
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

                setPhase('SYMBOL_SEARCHING', 'Searching ' + SYMBOL + ' on ' + PLATFORM + '...');

                // Ensure any stale order-entry or result modal from a previous signal is cleared first
                // (never closing open broker positions or pending orders).
                const dialogsCleared = await clearStaleDialogsBeforeNewSignal();
                if (isCancelled()) return;
                if (!dialogsCleared) {
                  const errMsg = 'Previous order or result dialog could not be cleared cleanly before ' + SYMBOL;
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

                waitForOrderControlsThenRun(0);
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
                  if (window.BtechBridge && typeof window.BtechBridge.onTradeRequiresAuth === 'function') {
                    window.BtechBridge.onTradeRequiresAuth();
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
                  if (window.BtechBridge && typeof window.BtechBridge.onTradeRequiresAuth === 'function') {
                    window.BtechBridge.onTradeRequiresAuth();
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
                openMT4ContextMenu: openMT4ContextMenu,
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
                clearStaleDialogsBeforeNewSignal: clearStaleDialogsBeforeNewSignal,
                isBrokerErrorText: isBrokerErrorText,
                findOrderFormErrorBanner: findOrderFormErrorBanner,
                inspectOrderExecutionType: inspectOrderExecutionType,
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
