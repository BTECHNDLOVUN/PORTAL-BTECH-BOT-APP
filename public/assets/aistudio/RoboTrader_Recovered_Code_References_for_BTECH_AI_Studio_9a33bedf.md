# BTECH Complete RoboTrader Reference and Google AI Studio Prompt

## Important source-status note

The uploaded `RoboTrader.apks` archive was unpacked and its compiled DEX files were statically inspected. The following names, URLs, selectors and JavaScript fragments were found in that package. This is **static analysis**, not a live test of RoboTrader against a broker.

Some JavaScript methods are stored in the DEX as several string fragments with values inserted by Kotlin at runtime. Where a complete script could not be recovered as one contiguous string, this document labels the code as a **reconstruction/reference adaptation**, not the exact complete original source. Do not claim that a partial fragment is a complete original method.

The snippets are supplied to explain the observed implementation and guide BTECH's own compatible implementation. They must not be copied blindly into production. Web terminal DOM structures can change, broker-specific terminals may differ, and automated order entry must be checked against the broker's terms and supported interfaces.

---

# Part 1 — What the APK actually shows

## 1. Application and main execution paths

The uploaded application contains the package namespace:

`com.theecoder.robotrader`

Important recovered classes and methods include:

### MT4

- `com.theecoder.robotrader.TradingRobot`
- `TradingRobot.tradeMT4(...)`
- `TradingRobot$tradeMT4$1$onPageFinished$1`
- `MetatraderFragment.verifyLoginDetails(...)`

The MT4 trading method creates/uses an Android `WebView`, loads the MetaTrader web terminal and runs JavaScript inside that same WebView. The APK calls both `WebView.loadUrl("javascript:...")` and `WebView.evaluateJavascript(...)`.

Recovered MT4 terminal URL:

`https://metatraderweb.app/trade?version=4`

Recovered login route pattern:

`/trade?version=4&login=<login>&server=<server>`

Recovered MT4 page fields/IDs:

- `login`
- `server`
- `password`
- `volume`
- `sl`
- `tp`
- `comment`

### MT5

- `com.theecoder.robotrader.services.Mt5Service`
- `Mt5Service.buildTerminalUrl(...)`
- `Mt5Service.authenticateForTradingFlow(...)`
- `Mt5Service.authenticateScript(...)`
- `Mt5Service.startAuthenticationLoop(...)`
- `Mt5Service.openTradeForm(...)`
- `Mt5Service.searchSymbolForTrading(...)`
- `Mt5Service.setTradeProperties(...)`
- `Mt5Service.verifyMinimumVolumeScript(...)`
- `Mt5Service.executeTrade(...)`
- `Mt5Service.executeTradeScript(...)`
- `Mt5Service.pollTradeResult(...)`
- `Mt5Service.dismissTradeResult(...)`
- `Mt5Service.removeExistingSession(...)`

Recovered MT5 base host:

`https://trade.metatrader5.com`

Recovered MT5 route pattern:

`/terminal?login=<login>&server=<server>`

**Do not mix the MT4 and MT5 implementations.** The APK uses separate execution paths and separate terminal URLs.

## 2. WebView configuration evidence

The APK's login-verification WebView includes references to:

- JavaScript enabled
- DOM storage enabled
- Third-party cookies accepted for the WebView
- `CookieManager.removeAllCookies(...)` during its verification setup
- Desktop-style user agent:

`Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36`

- Wide viewport / overview-mode settings
- `WebViewClient` and page-finished handling

RoboTrader clearing cookies during a fresh account-verification flow does **not** mean BTECH should delete cookies on every launch. BTECH should preserve a valid session during ordinary app use and expose a deliberate clear-session / sign-out / fresh-login action.

## 3. MT4 sequence indicated by recovered methods and strings

The MT4 path indicates this broad sequence:

1. Load MT4 terminal in a WebView.
2. Wait for page callbacks and terminal state.
3. Fill or interact with login/server/password fields.
4. Check the terminal's Market Watch area.
5. Open the context menu on the first Market Watch row.
6. Select a positional menu item, apparently the Show All symbols action.
7. Iterate Market Watch table rows and compare a first-cell symbol value.
8. Double-click the matching row.
9. Configure Volume, Stop Loss, Take Profit and Comment fields.
10. Select a trading direction and submit through a visible terminal control.
11. Inspect terminal status/result content and report completion or failure.

Recovered MT4 progress messages include:

- `>>Pressing Login`
- `>>Checking all available assets.`
- `>>Viewing all available assets.`
- `>>Selecting `
- `Setting LotSize, TP and SL.`
- `Submitting`
- `Trade completed`
- `Trade failed`

The APK contains fixed CSS selectors and positional menu/button selectors. They are brittle and can break when the web terminal changes.

## 4. MT5 sequence indicated by its service methods

The MT5 code is organized into explicit helper methods. The recovered scripts indicate:

1. Build the MT5 terminal URL.
2. Run an authentication loop.
3. Inspect login/password fields and the `Connect to account` button.
4. Classify authenticated, invalid-credentials, progress and waiting states.
5. Open the trade form using a `Show Trade Form (F9)` title or `New Order` text.
6. Search using an input whose placeholder is `Search symbol`.
7. Select an exact symbol result.
8. Find Volume, Stop Loss, Take Profit and Comment fields by their labels.
9. Dispatch input/change/keyboard/blur events and verify values.
10. Check whether the volume reached the terminal's minimum using its decrement button state.
11. Find `Buy by Market` or `Sell by Market`, check field state, then click.
12. Poll terminal result/status elements and classify success or broker errors.
13. Avoid blind retry if the app cannot confirm whether a submission occurred.

The MT5 service includes the message:

`error:Unable to confirm trade submission. The trade was not retried.`

This no-blind-retry principle is important for BTECH because a second attempt after an ambiguous timeout could create a duplicate order.

## 5. Symbol-search finding: RoboTrader was not guaranteed to find every symbol

The APK contains an explicit MT5 error string:

`error:Symbol <symbol> not found`

Its MT5 symbol script looks for a `Search symbol` input, types the requested name, looks for `No symbols found`, checks for an exact matching text node, and tries to click its nearest button or `[role="button"]` ancestor.

This is **not** a universal broker symbol resolver. It does not prove that every symbol available at the broker will always be found. It can fail if:

- the terminal has not loaded its search results yet;
- the broker uses a suffix/prefix such as `.a`, `m`, `.pro`, or `.cash`;
- the terminal list has not loaded or the symbol is hidden;
- the page DOM changes;
- the exact symbol text appears in a non-clickable node;
- the account/platform does not expose that instrument;
- the requested symbol differs from the broker's actual instrument name.

The correct BTECH fix is to discover the terminal's actual available symbols and persist an explicit, broker/account/platform-specific mapping. Do not silently guess a different instrument.

## 6. Database and platform clue

The APK's local database includes a `symbols` table with fields including:

- `id`
- `name`
- `lotSize`
- `action`
- `platform`
- `numberOfTrades`

It also contains this message:

`Automated Trading Failed, please add a platform to this symbol.`

This supports the observation that symbol records include a platform designation. It does not by itself prove how the complete symbol mapping UI works.

## 7. Broker/terminal URLs

The APK's MT4 terminal host is `metatraderweb.app`; its MT5 host is `trade.metatrader5.com`.

JustMarkets' official WebTrader entry point is:

`https://webtrader.justmarkets.com/`

The previously inspected JustMarkets WebTrader page embedded a `metatraderweb.app` terminal and exposed JustMarkets-specific server options. Treat the broker's official launch destination and account server as authoritative. Do not assume XM uses the same WebTrader host, server list or URL parameters.

---

# Part 2 — Recovered JavaScript excerpts and carefully labelled reconstructions

## A. MT4 — exact Market Watch context-menu dispatch excerpt

This is the recovered MT4 JavaScript fragment that dispatches a right-click/context-menu sequence on the first Market Watch row. It is kept close to the extracted APK string:

```javascript
var element = document.querySelector(
  'body > div.page-window.market-watch.compact > div > div.b > div.page-block > div > table > tbody > tr:nth-child(1)'
);

var ev1 = new MouseEvent("mousedown", {
  bubbles: true,
  cancelable: false,
  view: window,
  button: 2,
  buttons: 2,
  clientX: element.getBoundingClientRect().x,
  clientY: element.getBoundingClientRect().y
});
element.dispatchEvent(ev1);

var ev2 = new MouseEvent("mouseup", {
  bubbles: true,
  cancelable: false,
  view: window,
  button: 2,
  buttons: 0,
  clientX: element.getBoundingClientRect().x,
  clientY: element.getBoundingClientRect().y
});
element.dispatchEvent(ev2);

var ev3 = new MouseEvent("contextmenu", {
  bubbles: true,
  cancelable: false,
  view: window,
  button: 2,
  buttons: 0,
  clientX: element.getBoundingClientRect().x,
  clientY: element.getBoundingClientRect().y
});
element.dispatchEvent(ev3);
```

The exact positional selector is evidence of the implementation, not a robust selector recommendation. BTECH must inspect the live terminal and verify the element before acting.

## B. MT4 — exact positional Show All menu excerpt

The APK's extracted string selects the seventh context-menu child:

```javascript
var sall = document.querySelector(
  'body > div.page-menu.context.expanded > div > div > span.box > span > div:nth-child(7)'
);
sall.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
sall.click();
```

This appears to be the Show All operation based on the MT4 workflow and progress messages, but the extracted selector itself does not preserve the visible menu label. BTECH must inspect the menu text at runtime and verify the selected menu item's label before clicking. Never assume the seventh item always means Show All.

## C. MT4 — exact button selectors found

The APK includes these exact selectors/actions:

```javascript
document.querySelector(
  'body > div > div > div.b > button:nth-child(18)'
).click();

document.querySelector(
  'body > div > div > div.b > button:nth-child(19)'
).click();

document.querySelectorAll('button.input-button')
  .item(3)
  .removeAttribute('disabled');

document.querySelectorAll('button.input-button')
  .item(3)
  .click();
```

The source used direct, positional selectors and removed a disabled attribute before clicking. BTECH must **not** copy the forced-enable step. It should respect the terminal's disabled state and inspect the actual button label, enabled state, and validation result. The 18th/19th child selectors must not be used without verifying the live element's text and function.

## D. MT4 — recovered Market Watch row-iteration prefix (incomplete)

The DEX string ends partway through this loop, after the comparison begins. The missing comparison value and remaining event dispatch could not be recovered from this string alone:

```javascript
var tableB = document.querySelector(
  'body > div.page-window.market-watch.compact > div > div.b > div.page-block > div > table > tbody'
);
var allTRs = tableB.querySelectorAll('tr');
var clickEvent = document.createEvent('MouseEvents');
clickEvent.initEvent('dblclick', true, true);

for (var i = 0; i < allTRs.length; i++) {
  var a = allTRs[i].getElementsByTagName('td')[0];
  if (a.textContent.trimLeft(0) === /* runtime symbol value not recovered */) {
    // Original continuation not recovered from this DEX string.
  }
}
```

Do not fill in the missing original lines and call them recovered source. Implement a fresh, live-DOM-verified BTECH row matcher instead.

## E. MT4 — recovered field IDs and partial form-setting code

The APK references these MT4 terminal IDs:

```javascript
var volumeInput = document.getElementById('volume');
var stopLossInput = document.getElementById('sl');
var takeProfitInput = document.getElementById('tp');
var commentInput = document.querySelector('#comment');
```

It focuses inputs, assigns values and dispatches `input`/`change` events. Its DEX strings show this partial pattern, with values inserted by Kotlin at runtime:

```javascript
var volumeInput = document.getElementById('volume');
var stopLossInput = document.getElementById('sl');
var takeProfitInput = document.getElementById('tp');

volumeInput.focus();
volumeInput.value = /* volume supplied by native code */;
volumeInput.dispatchEvent(new Event('change', {
  bubbles: true,
  cancelable: true
}));

stopLossInput.dispatchEvent(new MouseEvent('click', { bubbles: true }));
stopLossInput.value = '0';
stopLossInput.dispatchEvent(new Event('change', {
  bubbles: true,
  cancelable: true
}));

// The APK subsequently sets the supplied stop-loss and take-profit values,
// and assigns the supplied comment to #comment.
```

This is a partial reconstruction from split strings, not a complete original function. The APK also references `#server` and `#password` in its login-form interaction.

## F. MT5 — authentication-state script (reconstruction from split DEX strings)

The APK's `authenticateScript` contains the following observed selectors and state tests:

- login: `input[autocomplete='username'], input[name='login']`
- password: `input[type='password'], input[name='password']`
- connect button text: `Connect to account`
- success indicator: visible text beginning `Balance:`
- invalid credentials: `Invalid account or password`
- progress: `[role='progressbar']`

The following is a readable reconstruction of that state-checking logic. `LOGIN_VALUE` and `PASSWORD_VALUE` must be supplied from native code using safe JSON string quoting; never concatenate raw credentials into JavaScript.

```javascript
(function() {
  const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
  const dispatchValue = (input, value) => {
    if (!input) return false;
    input.focus();
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
    input.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
    return true;
  };

  const loginValue = LOGIN_VALUE;       // safely JSON-quoted native argument
  const passwordValue = PASSWORD_VALUE; // safely JSON-quoted native argument

  const actionElements = Array.from(document.querySelectorAll(
    'button, [role="button"], input[type="submit"], input[type="button"]'
  ));
  const allNodes = Array.from(document.querySelectorAll('body *'));
  const progressElement = document.querySelector("[role='progressbar']");
  const loginInput = document.querySelector(
    "input[autocomplete='username'], input[name='login']"
  );
  const passwordInput = document.querySelector(
    "input[type='password'], input[name='password']"
  );
  const connectButton = actionElements.find(element =>
    normalize(
      element.innerText || element.textContent || element.value ||
      element.getAttribute('aria-label') || element.getAttribute('title')
    ).includes('Connect to account')
  );

  const balanceText = allNodes
    .map(node => normalize(node.innerText || node.textContent))
    .find(text => text.includes('Balance:')) || '';
  const balanceMatch = balanceText.match(/Balance:\s*([0-9\s.,\u00A0]+)/i);
  const balanceOnlyText = balanceMatch
    ? 'Balance: ' + balanceMatch[1].trim()
    : '';
  const hasInvalidText = allNodes.some(node =>
    normalize(node.innerText || node.textContent)
      .includes('Invalid account or password')
  );
  const errorElement = document.querySelector('.error');
  const errorText = normalize(
    errorElement ? (errorElement.innerText || errorElement.textContent) : ''
  );

  if (balanceOnlyText) return 'success:' + balanceOnlyText;
  if (errorText || hasInvalidText) {
    return 'error:' + (errorText || 'Invalid account or password');
  }

  const progressVisible = !!progressElement && (() => {
    const style = window.getComputedStyle(progressElement);
    const rect = progressElement.getBoundingClientRect();
    return style.display !== 'none' &&
      style.visibility !== 'hidden' &&
      style.opacity !== '0' && rect.width > 0 && rect.height > 0;
  })();

  const hasAuthUi = !!passwordInput || !!connectButton || !!loginInput;
  if (hasAuthUi) {
    if (!loginInput || !dispatchValue(loginInput, loginValue)) {
      return progressVisible
        ? 'loading:progress_waiting_for_auth_result'
        : 'loading:login_input_missing';
    }
    if (!passwordInput || !dispatchValue(passwordInput, passwordValue)) {
      return progressVisible
        ? 'loading:progress_with_auth_ui'
        : 'loading:password_input_missing';
    }
    if (!connectButton) {
      return progressVisible
        ? 'loading:progress_waiting_for_connect'
        : 'loading:connect_button_missing';
    }
    connectButton.click();
    return progressVisible ? 'submitted:progress_visible' : 'submitted';
  }

  if (progressVisible) return 'loading:progress_role';
  return 'loading:waiting_for_auth_result';
})();
```

This reconstruction includes the APK's observed login-state logic but uses explicit null checks for BTECH reliability. Do not assume the `Balance:` text alone is a sufficient authentication proof in every broker skin; verify the authenticated account/terminal state as well.

## G. MT5 — symbol search/selection script (reconstruction of the extracted method)

The original method is `searchSymbolScript(targetSymbol)`. Its strings show the exact `Search symbol` placeholder, `No symbols found` check, exact text-node comparison and clickable ancestor selection.

```javascript
(function() {
  const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
  const targetSymbol = TARGET_SYMBOL; // safely JSON-quoted native argument

  const dispatchValue = (input, value) => {
    if (!input) return false;
    input.focus();
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
    input.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
    return true;
  };

  const searchInput = document.querySelector(
    "input[placeholder='Search symbol']"
  );
  if (!searchInput) return 'loading:search_box_missing';

  dispatchValue(searchInput, targetSymbol);

  const allNodes = Array.from(document.querySelectorAll('body *'));
  const noSymbolsFound = allNodes.some(node =>
    normalize(node.innerText || node.textContent).includes('No symbols found')
  );
  if (noSymbolsFound) return 'error:Symbol ' + targetSymbol + ' not found';

  const exactMatchNode = allNodes.find(node =>
    normalize(node.innerText || node.textContent) === targetSymbol
  );
  if (!exactMatchNode) return 'loading:waiting_for_symbol_results';

  const clickableTarget =
    exactMatchNode.closest('button') ||
    exactMatchNode.closest('[role="button"]') ||
    exactMatchNode.parentElement?.closest('button') ||
    exactMatchNode.parentElement?.closest('[role="button"]');

  if (!clickableTarget) return 'loading:symbol_row_not_clickable_yet';
  clickableTarget.click();
  return 'clicked:' + targetSymbol;
})();
```

Important limitations of this recovered approach:

- It uses exact text matching; it does not resolve broker aliases/suffixes.
- It checks for `No symbols found`, but the search results may still be loading.
- It may match a nested or unrelated exact text node.
- It relies on the current `Search symbol` placeholder and terminal DOM.
- It does not, by itself, prove that the requested symbol was selected and active in the order form.

BTECH should add explicit result readiness, row identity, selected-symbol verification and broker-specific mappings.

## H. MT5 — open trade form script (exact recovered string)

```javascript
(function() {
  const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
  const buttons = Array.from(document.querySelectorAll('button'));
  const allNodes = Array.from(document.querySelectorAll('body *'));

  const buyOrSellButton = buttons.find(button => {
    const text = normalize(button.innerText || button.textContent);
    return text === 'Buy by Market' || text === 'Sell by Market';
  });
  if (buyOrSellButton) return 'ready';

  const titleTarget = allNodes.find(node =>
    normalize(node.getAttribute && node.getAttribute('title'))
      .includes('Show Trade Form (F9)')
  );
  if (titleTarget) {
    const clickable = titleTarget.closest('button') ||
      titleTarget.closest('[role="button"]') || titleTarget;
    clickable.click();
    return 'opened:title';
  }

  const newOrderTarget = allNodes.find(node =>
    normalize(node.innerText || node.textContent) === 'New Order'
  );
  if (newOrderTarget) {
    const clickable = newOrderTarget.closest('button') ||
      newOrderTarget.closest('[role="button"]') ||
      newOrderTarget.parentElement?.closest('button') ||
      newOrderTarget.parentElement?.closest('[role="button"]');
    if (clickable) {
      clickable.click();
      return 'opened:text';
    }
  }
  return 'loading:trade_form_trigger_missing';
})();
```

## I. MT5 — trade-field setting script (reconstruction using recovered method fragments)

The APK's `setTradeFieldScript(fieldKind, label, fieldValue)` locates labels under `.trade-input`, uses `Comment` in a `.comment` container, and dispatches `beforeinput`, `input`, `change`, Tab key and blur events. The dynamic strings for the field kind, label and value were injected by Kotlin.

```javascript
(function() {
  const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
  const normalizedDigits = value =>
    normalize(value).replace(/\u00A0/g, ' ').replace(/,/g, '.');

  const fieldKind = FIELD_KIND;   // one of the expected kinds; JSON-quoted
  const label = FIELD_LABEL;      // e.g. Volume, Stop Loss, Take Profit
  const fieldValue = FIELD_VALUE; // JSON-quoted string

  const dispatchValue = (input, value) => {
    if (!input) return false;
    const desiredValue = String(value || '').replace(/,/g, '.');
    input.focus();
    input.value = '';
    input.dispatchEvent(new InputEvent('input', {
      bubbles: true, cancelable: true,
      data: null, inputType: 'deleteContentBackward'
    }));
    input.value = desiredValue;
    input.dispatchEvent(new InputEvent('beforeinput', {
      bubbles: true, cancelable: true,
      data: desiredValue, inputType: 'insertText'
    }));
    input.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
    input.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
    input.dispatchEvent(new KeyboardEvent('keydown', {
      bubbles: true, key: 'Tab', code: 'Tab'
    }));
    input.dispatchEvent(new KeyboardEvent('keyup', {
      bubbles: true, key: 'Tab', code: 'Tab'
    }));
    input.dispatchEvent(new Event('blur', { bubbles: true, cancelable: true }));

    const actualValue = normalizedDigits(input.value);
    const expectedValue = normalizedDigits(desiredValue);
    const actualNumeric = Number(actualValue);
    const expectedNumeric = Number(expectedValue);
    const sameNumeric = Number.isFinite(actualNumeric) &&
      Number.isFinite(expectedNumeric) &&
      Math.abs(actualNumeric - expectedNumeric) < 0.00001;
    return actualValue === expectedValue || sameNumeric;
  };

  const findTradeInputByLabel = labelText => {
    const labels = Array.from(document.querySelectorAll(
      '.trade-input .label, .trade-input [slot="label"], .trade-input span'
    ));
    const exactLabel = labels.find(node => normalize(node.textContent) === labelText);
    if (exactLabel) {
      return exactLabel.closest('.trade-input')?.querySelector('input, textarea') || null;
    }
    const prefixedLabel = labels.find(node =>
      normalize(node.textContent).startsWith(labelText)
    );
    if (prefixedLabel) {
      return prefixedLabel.closest('.trade-input')?.querySelector('input, textarea') || null;
    }
    return null;
  };

  const findCommentInput = () => {
    const labels = Array.from(document.querySelectorAll(
      '.comment .label, .comment [class*="label"]'
    ));
    const commentLabel = labels.find(node =>
      normalize(node.textContent) === 'Comment'
    );
    return commentLabel?.closest('.comment')?.querySelector('input, textarea') || null;
  };

  const input = fieldKind === 'comment'
    ? findCommentInput()
    : findTradeInputByLabel(label);

  if (!input) return 'loading:' + label + '_field_not_ready';
  if (!dispatchValue(input, fieldValue)) return 'error:Invalid ' + label + ' value';
  return 'filled';
})();
```

This mirrors the extracted field-finding and event-dispatch approach. Modern web apps may use framework-controlled inputs; BTECH must verify the terminal actually accepted the value and not rely solely on `input.value`.

## J. MT5 — minimum-volume verification (exact recovered string)

```javascript
(function() {
  const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
  const labels = Array.from(document.querySelectorAll(
    '.trade-input .label, .trade-input [slot="label"], .trade-input span'
  ));
  const volumeLabel = labels.find(node =>
    normalize(node.textContent) === 'Volume'
  ) || labels.find(node =>
    normalize(node.textContent).startsWith('Volume')
  );
  const volumeInput = volumeLabel
    ?.closest('.trade-input')
    ?.querySelector('input, textarea');

  if (!volumeInput) return 'loading:Volume_field_not_ready';

  const decrementButton = volumeInput.previousElementSibling;
  const actualVolume = Number(
    normalize(volumeInput.value).replace(/\u00A0/g, ' ').replace(/,/g, '.')
  );
  const isAtMinimum = decrementButton?.matches('button') && decrementButton.disabled;
  return Number.isFinite(actualVolume) && actualVolume > 0 && isAtMinimum
    ? 'filled'
    : 'loading:minimum_volume_not_ready';
})();
```

This checks a UI state (the decrement button is disabled) as an indicator that the displayed volume is at the terminal's minimum. BTECH must additionally validate broker symbol volume minimum, maximum and step where those values are available, and must not use this UI check as the only validation.

## K. MT5 — order-submission script (reconstruction from split DEX strings)

The extracted method `executeTradeScript(expectedVolume, tradeSide)` checks the trade button, visible errors, volume synchronization and disabled state before clicking. The source strings were split and Kotlin inserted the two arguments. This is a reconstruction of the visible logic:

```javascript
(function() {
  const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
  const isVisible = element => {
    if (!element) return false;
    const style = window.getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return style.display !== 'none' &&
      style.visibility !== 'hidden' &&
      style.opacity !== '0' && rect.width > 0 && rect.height > 0;
  };

  const expectedVolume = EXPECTED_VOLUME; // safely quoted native value
  const shouldUseMinimumVolume = SHOULD_USE_MINIMUM_VOLUME; // boolean
  const tradeSide = TRADE_SIDE_LABEL; // normalize to Buy or Sell
  const targetText = tradeSide + ' by Market';

  const buttons = Array.from(document.querySelectorAll('button'));
  const labels = Array.from(document.querySelectorAll(
    '.trade-input .label, .trade-input [slot="label"], .trade-input span'
  ));
  const volumeLabel = labels.find(node => normalize(node.textContent) === 'Volume') ||
    labels.find(node => normalize(node.textContent).startsWith('Volume')) || null;
  const volumeInput = volumeLabel
    ?.closest('.trade-input')?.querySelector('input, textarea') || null;
  const tradeButton = buttons.find(button =>
    normalize(button.innerText || button.textContent) === targetText
  );
  const okButtonVisible = buttons.some(button =>
    normalize(button.innerText || button.textContent) === 'OK'
  );

  if (!tradeButton) {
    return okButtonVisible
      ? 'loading:trade_result_visible'
      : 'loading:trade_button_missing';
  }

  const visibleErrorNode = Array.from(document.querySelectorAll(
    '.error, [role="alert"], [aria-live="assertive"]'
  )).find(node =>
    isVisible(node) && normalize(node.innerText || node.textContent)
  );
  if (visibleErrorNode) {
    return 'error:' + normalize(visibleErrorNode.innerText || visibleErrorNode.textContent);
  }

  if (volumeInput) {
    const actualVolume = normalize(volumeInput.value)
      .replace(/\u00A0/g, ' ').replace(/,/g, '.');
    const actualNumeric = Number(actualVolume);
    const expectedNumeric = Number(expectedVolume);
    const sameVolume = shouldUseMinimumVolume
      ? Number.isFinite(actualNumeric) && actualNumeric > 0
      : Number.isFinite(actualNumeric) && Number.isFinite(expectedNumeric) &&
        Math.abs(actualNumeric - expectedNumeric) < 0.00001;
    if (actualVolume && !sameVolume) return 'loading:volume_syncing';
  }

  const isDisabled = tradeButton.disabled ||
    tradeButton.getAttribute('aria-disabled') === 'true' ||
    tradeButton.matches(':disabled');
  if (isDisabled) {
    const invalidInput = document.querySelector(
      '.trade-input input[aria-invalid="true"], .trade-input input:invalid'
    );
    const invalidLabel = invalidInput
      ? normalize(invalidInput.closest('.trade-input')
          ?.querySelector('.label, [slot="label"], span')?.textContent)
      : '';
    return 'error:' + (invalidLabel
      ? 'Invalid ' + invalidLabel + ' value'
      : 'Trade cannot be submitted. Check Volume, Stop Loss and Take Profit.');
  }

  tradeButton.click();
  return 'submitted';
})();
```

The result `submitted` means the button was clicked, **not** that the broker accepted the order. The native code must then poll and verify the actual terminal result.

## L. MT5 — result polling (exact recovered string)

```javascript
(function() {
  const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
  const isVisible = element => {
    if (!element) return false;
    const style = window.getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return style.display !== 'none' &&
      style.visibility !== 'hidden' &&
      style.opacity !== '0' && rect.width > 0 && rect.height > 0;
  };
  const isBrokerError = text => {
    const value = text.toLowerCase();
    return [
      'invalid', 'error', 'failed', 'not enough money', 'no money',
      'market closed', 'trade disabled', 'trading disabled', 'off quotes',
      'requote', 'price changed', 'unsupported filling mode',
      'request rejected', 'timeout'
    ].some(phrase => value.includes(phrase));
  };

  const buttons = Array.from(document.querySelectorAll('button'));
  const okButton = buttons.find(button =>
    isVisible(button) && normalize(button.innerText || button.textContent) === 'OK'
  );
  const statusSelectors = [
    '.status', '.message', '.error', '[role="alert"]', '[aria-live="assertive"]'
  ];
  const statusNode = Array.from(document.querySelectorAll(statusSelectors.join(',')))
    .find(node => {
      const text = normalize(node.innerText || node.textContent);
      return isVisible(node) && text && text !== 'OK' && text.length < 500;
    });
  let statusText = statusNode
    ? normalize(statusNode.innerText || statusNode.textContent)
    : '';

  if (!statusText && okButton) {
    let parent = okButton.parentElement;
    for (let i = 0; i < 6 && parent; i += 1) {
      const candidate = normalize(parent.innerText || parent.textContent);
      if (candidate && candidate !== 'OK' && candidate.length < 500) {
        statusText = candidate;
        break;
      }
      parent = parent.parentElement;
    }
  }

  statusText = statusText.replace(/\s+OK$/, '').trim();
  if (statusText && isBrokerError(statusText)) {
    if (okButton) okButton.click();
    return 'error:' + statusText;
  }
  if (statusText && okButton) return 'success:' + statusText;
  return 'loading:waiting_for_trade_result';
})();
```

BTECH must independently verify the resulting order or position and capture a ticket/order identifier when the terminal exposes one. A generic `success:` status string alone may not be sufficient for high-reliability execution.

## M. MT5 — dismiss result dialog (exact recovered string)

```javascript
(function() {
  const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
  const buttons = Array.from(document.querySelectorAll('button'));
  const okButton = buttons.find(button =>
    normalize(button.innerText || button.textContent) === 'OK'
  );
  if (okButton) {
    okButton.click();
    return 'dismissed:ok';
  }
  const closeButton = buttons.find(button => {
    const text = normalize(button.innerText || button.textContent);
    return text === 'Close' || text === 'Done' || text === 'Cancel';
  });
  if (closeButton) {
    closeButton.click();
    return 'dismissed:close';
  }
  return 'dismissed:none';
})();
```

## N. MT5 — remove-session button check (exact recovered string)

```javascript
(function() {
  const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
  const buttons = Array.from(document.querySelectorAll('button'));
  const removeButton = buttons.find(button =>
    normalize(button.innerText || button.textContent) === 'Remove'
  );
  if (!removeButton) return 'false';
  removeButton.click();
  return 'true';
})();
```

This is a UI action in RoboTrader's session-removal flow. Do not call it automatically when BTECH is trying to preserve a valid session.

---

---

# Short implementation instruction for Google AI Studio

**Work directly in the BTECH Finance Android project that is already open. Do not ask for a repository clone and do not redesign the BTECH UI.**

Use this document as the RoboTrader APK reference. First compare the recovered RoboTrader MT4 and MT5 classes, methods, selectors, scripts and result-handling sequence with the existing BTECH implementation. Then port the same observed approach into the corresponding existing BTECH functions with the smallest possible changes.

1. Keep MT4 and MT5 paths separate. MT4 must follow the recovered Market Watch/context-menu/table-row approach. MT5 must follow the recovered `Mt5Service` sequence: authentication loop, `Show Trade Form (F9)`/`New Order`, `Search symbol`, labelled order fields, minimum-volume verification, market-order click, result polling and result dismissal.
2. Do not invent a replacement discovery framework, symbol database or UI. Reuse BTECH's existing WebView, scripts, ViewModel, bridge, account models and screens wherever possible. Only add code required to make the observed RoboTrader flow work reliably in BTECH.
3. Compare every recovered selector with the actual terminal DOM. The APK contains brittle positional selectors such as `nth-child(7)`. Preserve them as evidence, but verify the target element and visible label at runtime before clicking. Never force-enable a disabled trade button.
4. Reproduce RoboTrader's per-platform order-form preparation and response polling. A button click is not a successful trade. Capture the broker's actual accepted/rejected/unknown result and pass it back to Kotlin through the existing JavaScript bridge.
5. Stop the remaining trade-count sequence immediately after a definitive rejection. Do not count an `OK` button by itself as success. On an ambiguous timeout, stop with `UNKNOWN` and do not automatically retry or risk a duplicate order.
6. Before processing a new signal, close only stale order-entry and result dialogs from the previous signal. Do not close existing open positions or pending broker orders. Confirm the old UI state has cleared before opening the next order form.
7. Preserve the current BTECH authentication, balance/equity display, floating overlay, sound behaviour and all unrelated features. Do not add Portal or Trade Channel integration in this task.
8. Test MT4 and MT5 separately on demo accounts. Report which APK-observed steps were successfully ported, which actual DOM selectors were verified, what errors were correctly returned, whether multi-order counts stop correctly after rejection, and the build result. Do not claim live broker success based only on JavaScript syntax or simulated DOM tests.

**Important source-status rule:** Part 2 labels each excerpt as exact recovered text, incomplete recovered fragment, or reconstruction from split DEX strings. Keep those labels. Do not tell me that a reconstruction is the verbatim original RoboTrader source, and do not invent missing original lines.
