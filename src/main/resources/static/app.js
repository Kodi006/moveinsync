(() => {
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const titles = { overview: "Overview", employees: "Employees", ingest: "Ingest events", reconciliation: "Reconciliation" };
  const apiKeyInput = $("#api-key");
  const settingsDialog = $("#settings-dialog");
  let apiKey = sessionStorage.getItem("rosterApiKey") || "";
  apiKeyInput.value = apiKey;

  function toast(message, type = "success") {
    const item = document.createElement("div");
    item.className = `toast ${type === "error" ? "error" : ""}`;
    item.textContent = message;
    $("#toast-region").append(item);
    window.setTimeout(() => item.remove(), 3900);
  }

  function showPage(name) {
    if (!titles[name]) return;
    $$(".page").forEach(page => page.classList.toggle("active", page.id === `page-${name}`));
    $$(".nav-link").forEach(link => link.classList.toggle("active", link.dataset.page === name));
    $("#page-title").textContent = titles[name];
    $("#sidebar").classList.remove("open");
    history.replaceState(null, "", `#${name}`);
    if (name === "reconciliation") loadRuns();
  }

  function keyRequired() {
    if (apiKey) return true;
    toast("Add your API key to connect to the roster service.", "error");
    settingsDialog.showModal();
    apiKeyInput.focus();
    return false;
  }

  async function request(path, options = {}) {
    if (!keyRequired()) throw new Error("API key required");
    const response = await fetch(path, {
      ...options,
      headers: { ...(options.body ? { "Content-Type": "application/json" } : {}), "X-API-Key": apiKey, ...options.headers }
    });
    const text = await response.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch { body = text; }
    if (!response.ok) {
      const error = new Error(body?.error || `Request failed (${response.status})`);
      error.status = response.status;
      throw error;
    }
    return body;
  }

  async function checkHealth() {
    const status = $("#service-status");
    const label = $("#service-status-label");
    try {
      const response = await fetch("/actuator/health", { cache: "no-store" });
      if (!response.ok) throw new Error("offline");
      status.classList.add("online");
      status.classList.remove("offline");
      label.textContent = "Service online";
    } catch {
      status.classList.add("offline");
      status.classList.remove("online");
      label.textContent = "Service unavailable";
    }
  }

  function textCell(label, value, status = false) {
    const cell = document.createElement("div");
    cell.className = "record-cell";
    const title = document.createElement("span");
    title.textContent = label;
    const content = document.createElement("strong");
    content.textContent = value == null || value === "" ? "—" : String(value);
    if (status && value) content.className = "status-chip";
    cell.append(title, content);
    return cell;
  }

  $("#employee-search").addEventListener("submit", async event => {
    event.preventDefault();
    if (!keyRequired()) return;
    const id = $("#employee-id").value.trim();
    const result = $("#employee-result");
    result.className = "result-area empty-state";
    result.replaceChildren();
    const loading = document.createElement("p");
    loading.textContent = "Looking up employee…";
    result.append(loading);
    try {
      const employee = await request(`/api/v1/employees/${encodeURIComponent(id)}`);
      result.className = "result-area";
      result.replaceChildren();
      const grid = document.createElement("div");
      grid.className = "employee-record";
      [
        ["Employee ID", employee.employeeId], ["Status", employee.status, true], ["Name", employee.name],
        ["Email", employee.email], ["Manager ID", employee.managerId], ["Office", employee.officeCode],
        ["Pickup point", employee.pickupPoint], ["Special assistance", employee.specialAssistance],
        ["HR version", employee.hrVersion], ["Transport version", employee.transportVersion]
      ].forEach(([label, value, isStatus]) => grid.append(textCell(label, value, isStatus)));
      result.append(grid);
    } catch (error) {
      result.className = "result-area empty-state";
      result.replaceChildren();
      const heading = document.createElement("strong");
      const message = document.createElement("p");
      heading.textContent = error.status === 404 ? "Employee not found" : "Could not load employee";
      message.textContent = error.status === 401 ? "Check your API key in connection settings." : error.status === 404 ? "No matching employee exists in this tenant." : error.message;
      result.append(heading, message);
    }
  });

  const ownerFields = {
    HR: [ ["name", "Name", "text"], ["email", "Email", "email"], ["managerId", "Manager ID", "text"], ["officeCode", "Office code", "text"], ["status", "Employment status", "select"] ],
    TRANSPORT: [ ["pickupPoint", "Pickup point", "text"], ["specialAssistance", "Special assistance", "text"] ]
  };

  function renderPayloadFields() {
    const container = $("#payload-fields");
    container.replaceChildren();
    const deactivating = $("#event-type").value === "DEACTIVATE";
    container.classList.toggle("hidden", deactivating);
    $(".field-group-label").classList.toggle("hidden", deactivating);
    if (deactivating) return;
    ownerFields[$("#event-owner").value].forEach(([name, label, type]) => {
      const field = document.createElement("label");
      field.className = "field";
      const title = document.createElement("span");
      title.textContent = label;
      let input;
      if (type === "select") {
        input = document.createElement("select");
        [["", "Keep current"], ["ACTIVE", "Active"], ["INACTIVE", "Inactive"]].forEach(([value, text]) => {
          const option = document.createElement("option"); option.value = value; option.textContent = text; input.append(option);
        });
      } else {
        input = document.createElement("input"); input.type = type; input.placeholder = "Optional";
      }
      input.dataset.payloadField = name;
      field.append(title, input);
      container.append(field);
    });
  }

  $("#event-owner").addEventListener("change", renderPayloadFields);
  $("#event-type").addEventListener("change", renderPayloadFields);
  renderPayloadFields();

  function addResultRows(results) {
    const container = $("#ingest-results");
    container.classList.remove("hidden");
    container.replaceChildren();
    const table = document.createElement("table"); table.className = "result-table";
    const head = document.createElement("thead");
    const headingRow = document.createElement("tr");
    ["Employee", "Outcome", "Reason"].forEach(label => { const th = document.createElement("th"); th.textContent = label; headingRow.append(th); });
    head.append(headingRow);
    const body = document.createElement("tbody");
    (results || []).forEach(result => {
      const row = document.createElement("tr");
      const employee = document.createElement("td"); employee.textContent = result.employeeId || "—";
      const outcomeCell = document.createElement("td");
      const outcome = document.createElement("span"); outcome.className = `outcome ${(result.outcome || "").toLowerCase()}`; outcome.textContent = result.outcome || "—"; outcomeCell.append(outcome);
      const reason = document.createElement("td"); reason.textContent = result.reason || "—";
      row.append(employee, outcomeCell, reason); body.append(row);
    });
    table.append(head, body); container.append(table);
  }

  async function submitEvents(batch) {
    try {
      const response = await request("/api/v1/roster/events", { method: "POST", body: JSON.stringify(batch) });
      addResultRows(response.results);
      const applied = response.results.filter(row => row.outcome === "APPLY").length;
      toast(`${applied} event${applied === 1 ? "" : "s"} applied.`);
    } catch (error) {
      toast(error.status === 401 ? "API key not accepted. Check connection settings." : error.message, "error");
    }
  }

  $("#event-form").addEventListener("submit", event => {
    event.preventDefault();
    const payload = {};
    $$('[data-payload-field]', $("#payload-fields")).forEach(input => {
      if (input.value !== "") payload[input.dataset.payloadField] = input.value;
    });
    submitEvents({ events: [{
      tenantId: $("#event-tenant-id").value.trim(),
      employeeId: $("#event-employee-id").value.trim(),
      owner: $("#event-owner").value,
      version: Number($("#event-version").value),
      eventType: $("#event-type").value,
      payload
    }] });
  });

  $("#submit-json").addEventListener("click", () => {
    try {
      const batch = JSON.parse($("#batch-json").value);
      if (!batch || !Array.isArray(batch.events)) throw new Error('Expected an object with an "events" array.');
      submitEvents(batch);
    } catch (error) { toast(error.message, "error"); }
  });

  const initialBatch = { events: [{ tenantId: "demo-tenant", employeeId: "e-100", owner: "HR", version: 1, eventType: "UPSERT", payload: { name: "Asha Rao", email: "asha@example.test", officeCode: "BLR", status: "ACTIVE" } }] };
  $("#batch-json").value = JSON.stringify(initialBatch, null, 2);

  $$(".segment").forEach(segment => segment.addEventListener("click", () => {
    $$(".segment").forEach(item => item.classList.toggle("active", item === segment));
    const jsonMode = segment.dataset.editor === "json";
    $("#event-form-editor").classList.toggle("hidden", jsonMode);
    $("#json-batch-editor").classList.toggle("hidden", !jsonMode);
  }));

  function stateClass(status) {
    const normalized = String(status || "").toLowerCase();
    if (normalized.includes("blocked")) return "blocked";
    if (normalized === "failed") return "failed";
    if (normalized === "approved") return "approved";
    return "";
  }

  function metric(label, value) {
    const item = document.createElement("div"); item.className = "run-stat";
    const caption = document.createElement("span"); caption.textContent = label;
    const number = document.createElement("strong"); number.textContent = value == null ? "—" : String(value);
    item.append(caption, number); return item;
  }

  function renderRuns(runs) {
    const container = $("#runs-list");
    container.replaceChildren();
    $("#run-count").textContent = `${runs.length} RUN${runs.length === 1 ? "" : "S"}`;
    if (!runs.length) {
      container.innerHTML = '<div class="empty-state"><span class="empty-symbol">⟳</span><strong>No reconciliation runs yet</strong><p>Run history will appear after the first scheduled CSV comparison.</p></div>';
      return;
    }
    runs.forEach(run => {
      const card = document.createElement("article"); card.className = "run-card";
      const main = document.createElement("div"); main.className = "run-main";
      const id = document.createElement("strong"); id.textContent = `Run #${run.id}`;
      const date = document.createElement("small"); date.textContent = run.startedAt ? new Date(run.startedAt).toLocaleString() : "Date unavailable";
      const state = document.createElement("span"); state.className = `run-state ${stateClass(run.status)}`; state.textContent = String(run.status || "UNKNOWN").replaceAll("_", " ");
      main.append(id, date, state);
      card.append(main, metric("ROWS", run.totalRows), metric("MISSING", run.missingCount), metric("MISSING %", run.missingPercentage == null ? "—" : `${Number(run.missingPercentage).toFixed(1)}%`));
      const actions = document.createElement("div"); actions.className = "run-actions";
      if (run.status === "BLOCKED_SAFETY_BRAKE") {
        const approve = document.createElement("button"); approve.className = "button button-quiet"; approve.type = "button"; approve.textContent = "Approve candidates";
        approve.addEventListener("click", () => approveRun(run.id, approve)); actions.append(approve);
      }
      card.append(actions); container.append(card);
    });
  }

  async function loadRuns() {
    if (!apiKey) {
      $("#runs-list").innerHTML = '<div class="empty-state"><span class="empty-symbol">⌑</span><strong>Connect to view run history</strong><p>Add an API key using the button in the top-right corner.</p></div>';
      $("#run-count").textContent = "KEY REQUIRED";
      return;
    }
    $("#runs-list").innerHTML = '<div class="empty-state"><strong>Loading runs…</strong></div>';
    try { renderRuns(await request("/api/v1/reconciliation/runs")); }
    catch (error) {
      $("#runs-list").innerHTML = "";
      const message = document.createElement("div"); message.className = "empty-state";
      const strong = document.createElement("strong"); strong.textContent = error.status === 401 ? "API key not accepted" : "Could not load runs";
      const detail = document.createElement("p"); detail.textContent = error.status === 401 ? "Check your connection settings." : error.message;
      message.append(strong, detail); $("#runs-list").append(message);
    }
  }

  async function approveRun(id, button) {
    if (!keyRequired()) return;
    const operatorId = window.prompt("Enter your operator ID to record this approval:");
    if (!operatorId || !operatorId.trim()) return;
    button.disabled = true;
    try {
      const result = await request(`/api/v1/reconciliation/runs/${id}/approve`, { method: "POST", body: JSON.stringify({ operatorId: operatorId.trim() }) });
      toast(`Approval recorded. ${result.deactivatedCount} employee(s) deactivated; ${result.skippedCount} skipped.`);
      loadRuns();
    } catch (error) {
      toast(error.status === 403 ? "This API key does not have the operator role." : error.status === 409 ? "This run can no longer be approved." : error.message, "error");
      button.disabled = false;
    }
  }

  $$(".nav-link").forEach(link => link.addEventListener("click", () => showPage(link.dataset.page)));
  $$("[data-go]").forEach(button => button.addEventListener("click", () => showPage(button.dataset.go)));
  $("#refresh-runs").addEventListener("click", loadRuns);
  $("#mobile-menu").addEventListener("click", () => $("#sidebar").classList.toggle("open"));
  $("#open-settings").addEventListener("click", () => settingsDialog.showModal());
  $("#settings-form").addEventListener("submit", event => {
    event.preventDefault();
    apiKey = apiKeyInput.value.trim();
    sessionStorage.setItem("rosterApiKey", apiKey);
    settingsDialog.close();
    toast(apiKey ? "API key saved for this tab." : "API key cleared.");
    if ($("#page-reconciliation").classList.contains("active")) loadRuns();
  });
  $("#clear-key").addEventListener("click", () => {
    apiKey = ""; apiKeyInput.value = ""; sessionStorage.removeItem("rosterApiKey"); settingsDialog.close();
    toast("API key cleared.");
  });

  const pageFromHash = location.hash.slice(1);
  showPage(titles[pageFromHash] ? pageFromHash : "overview");
  checkHealth();
  window.setInterval(checkHealth, 30000);
})();
