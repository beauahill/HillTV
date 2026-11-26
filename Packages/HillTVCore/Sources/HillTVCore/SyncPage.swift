import Foundation

/// The page a phone opens after scanning the TV's QR code. Static: every
/// value the TV or a file supplies is put on the page as text by the script,
/// never as HTML.
public enum SyncPage {
    public static let html = #"""
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>HillTV</title>
<style>
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body { margin: 0; background: #0D1117; color: #E8ECF4; font: 16px/1.5 -apple-system, system-ui, sans-serif; }
main { max-width: 480px; margin: 0 auto; padding: 24px 16px 48px; }
h1 { font-size: 24px; margin: 0 0 4px; }
h1 span { color: #5B9BFF; }
h2 { font-size: 17px; margin: 0 0 8px; }
p { margin: 0 0 12px; color: #A6B0C2; }
section { background: #151B26; border-radius: 14px; padding: 16px; margin-top: 16px; }
label { display: block; font-size: 14px; color: #A6B0C2; margin: 10px 0 4px; }
input[type=text], input[type=password], input[type=url], input[inputmode] {
  width: 100%; padding: 12px; border-radius: 10px; border: 1px solid #283448;
  background: #1D2636; color: #E8ECF4; font-size: 16px; }
.check { display: flex; gap: 10px; align-items: center; margin: 8px 0; color: #E8ECF4; font-size: 16px; }
.check input { width: 20px; height: 20px; }
button, .button { display: block; width: 100%; margin-top: 14px; padding: 13px; border: 0; border-radius: 10px;
  background: #5B9BFF; color: #07101F; font-size: 16px; font-weight: 600; text-align: center; text-decoration: none; }
.secondary { background: #283448; color: #E8ECF4; }
.note { font-size: 14px; margin-top: 10px; }
.ok { color: #6BD39B; }
.bad { color: #F2888B; }
[hidden] { display: none !important; }
</style>
</head>
<body>
<main>
  <h1>Hill<span>TV</span></h1>
  <p id="intro">Settings for the TV that showed you this page.</p>

  <section id="codeBox" hidden>
    <h2>Code from the TV</h2>
    <p>Type the six-digit code shown on the TV's Sync screen.</p>
    <input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6">
    <button id="codeGo">Continue</button>
    <p class="note bad" id="codeMsg"></p>
  </section>

  <div id="app" hidden>
    <section>
      <h2>Sign in this TV</h2>
      <p id="signedInAs"></p>
      <form id="signIn">
        <label for="server">Server</label>
        <input id="server" name="url" type="url" autocomplete="url" autocapitalize="none" autocorrect="off" placeholder="http://line.example.com:8080">
        <label for="username">Username</label>
        <input id="username" name="username" type="text" autocomplete="username" autocapitalize="none" autocorrect="off">
        <label for="password">Password</label>
        <input id="password" name="password" type="password" autocomplete="current-password">
        <button type="submit" id="signInGo">Sign in this TV</button>
      </form>
      <p class="note" id="signInMsg"></p>
    </section>

    <section id="settingsBox">
      <h2>Settings</h2>
      <p>Hidden categories, favorites and channel order, for copying to another TV on the same server. The file has your server and username, never your password.</p>
      <a class="button secondary" id="save" download="hilltv-settings.json">Save this TV's settings to your phone</a>
      <label for="file">Load a settings file into this TV</label>
      <input id="file" type="file" accept=".json,application/json">
      <p class="note" id="fileMsg"></p>
      <label class="check"><input type="checkbox" id="optHidden" checked> Hidden categories (replaces this TV's)</label>
      <label class="check"><input type="checkbox" id="optFavorites"> Favorites (replaces this TV's)</label>
      <label class="check"><input type="checkbox" id="optOrder"> Channel order (replaces this TV's)</label>
      <button id="load">Load into this TV</button>
      <p class="note" id="loadMsg"></p>
    </section>
  </div>
</main>
<script>
(function () {
  var params = new URLSearchParams(location.search);
  var code = params.get("code") || "";
  var signedIn = false;
  var $ = function (id) { return document.getElementById(id); };

  function say(el, text, ok) {
    el.textContent = text || "";
    el.className = "note " + (ok ? "ok" : "bad");
  }

  function api(path, options) {
    options = options || {};
    options.headers = Object.assign({ "X-HillTV-Code": code }, options.headers || {});
    return fetch(path, options).then(function (response) {
      return response.json().catch(function () { return { ok: false, message: "The TV sent an answer this page can't read." }; })
        .then(function (body) { body.status = response.status; return body; });
    }, function () {
      return { ok: false, status: 0, message: "Can't reach the TV. Is its Sync screen still open, and is this phone on the same Wi-Fi?" };
    });
  }

  function showState(state) {
    $("codeBox").hidden = true;
    $("app").hidden = false;
    $("save").href = "/api/settings?code=" + encodeURIComponent(code);
    if (state.signedIn) {
      $("signedInAs").textContent = "This TV is signed in as " + state.username + " on " + state.server + ". Signing in with another login adds it as a second account on this TV and switches to it.";
      if (!$("server").value) { $("server").value = state.server; }
      if (!$("username").value) { $("username").value = state.username; }
    } else {
      $("signedInAs").textContent = "This TV isn't signed in yet.";
    }
    signedIn = !!state.signedIn;
    $("save").hidden = !signedIn;
    $("load").textContent = signedIn ? "Load into this TV" : "Sign in above, then load";
  }

  function refresh() {
    return api("/api/state").then(function (state) {
      if (state.status === 200) { showState(state); return true; }
      $("app").hidden = true;
      $("codeBox").hidden = false;
      if (code) { say($("codeMsg"), state.message, false); }
      return false;
    });
  }

  $("codeGo").addEventListener("click", function () {
    code = $("code").value.trim();
    refresh();
  });

  $("signIn").addEventListener("submit", function (event) {
    event.preventDefault();
    $("signInGo").disabled = true;
    say($("signInMsg"), "Signing in. This can take a few seconds.", true);
    api("/api/sign-in", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ server: $("server").value, username: $("username").value, password: $("password").value })
    }).then(function (result) {
      $("signInGo").disabled = false;
      say($("signInMsg"), result.message, result.ok);
      if (result.ok) { $("password").value = ""; refresh(); }
    });
  });

  // A file from another TV fills in the server and username, so a new TV
  // needs only the password typed (or filled in by the phone).
  $("file").addEventListener("change", function () {
    var file = $("file").files[0];
    if (!file) { return; }
    file.text().then(function (text) {
      var data;
      try { data = JSON.parse(text); } catch (e) { data = null; }
      if (!data || data.format !== "hilltv.settings" || !data.account) {
        say($("fileMsg"), "That isn't a HillTV settings file.", false);
        return;
      }
      if (typeof data.account.server === "string" && !$("server").value) { $("server").value = data.account.server; }
      if (typeof data.account.username === "string" && !$("username").value) { $("username").value = data.account.username; }
      say($("fileMsg"), signedIn
        ? "File ready. Pick what to load below."
        : "Server and username filled in from the file. Add the password and sign in, then load.", true);
    });
  });

  $("load").addEventListener("click", function () {
    var file = $("file").files[0];
    if (!file) { say($("loadMsg"), "Pick a settings file first.", false); return; }
    if (!signedIn) { say($("loadMsg"), "Sign this TV in first, then load.", false); return; }
    var query = "?hidden=" + ($("optHidden").checked ? 1 : 0)
      + "&favorites=" + ($("optFavorites").checked ? 1 : 0)
      + "&order=" + ($("optOrder").checked ? 1 : 0);
    file.text().then(function (text) {
      return api("/api/settings" + query, { method: "POST", headers: { "Content-Type": "application/json" }, body: text });
    }).then(function (result) {
      say($("loadMsg"), result.message, result.ok);
    });
  });

  if (code) { refresh(); } else { $("codeBox").hidden = false; }
})();
</script>
</body>
</html>
"""#
}
