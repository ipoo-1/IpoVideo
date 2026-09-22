const state = {
    mode: "login",
    token: localStorage.getItem("ipovideo_token") || "",
    mediaId: null,
    taskId: null,
    eventSource: null,
    pollingTimer: null
};

const $ = (id) => document.getElementById(id);
const authPanel = $("authPanel");
const workspace = $("workspace");
const authForm = $("authForm");
const authMessage = $("authMessage");
const taskForm = $("taskForm");
const taskMessage = $("taskMessage");

function setMessage(element, text, type = "") {
    element.textContent = text || "";
    element.className = "message " + type;
}

async function request(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (state.token) headers.set("Authorization", "Bearer " + state.token);
    if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
        headers.set("Content-Type", "application/json");
    }
    const response = await fetch(path, {...options, headers});
    const payload = await response.json().catch(() => ({message: "服务端返回不是 JSON"}));
    if (!response.ok || payload.code !== 0) {
        throw new Error(payload.message || ("请求失败: HTTP " + response.status));
    }
    return payload.data;
}

function showWorkspace(loggedIn) {
    authPanel.hidden = loggedIn;
    workspace.hidden = !loggedIn;
    $("logoutButton").hidden = !loggedIn;
    $("userLabel").textContent = loggedIn ? "已登录" : "未登录";
}

async function restoreSession() {
    if (!state.token) return showWorkspace(false);
    try {
        const user = await request("/api/me");
        $("userLabel").textContent = user.username;
        showWorkspace(true);
    } catch {
        localStorage.removeItem("ipovideo_token");
        state.token = "";
        showWorkspace(false);
    }
}

document.querySelectorAll(".segment").forEach((button) => {
    button.addEventListener("click", () => {
        state.mode = button.dataset.mode;
        document.querySelectorAll(".segment").forEach((item) => item.classList.toggle("active", item === button));
        $("authSubmit").textContent = state.mode === "login" ? "登录并进入" : "注册并登录";
        setMessage(authMessage, "");
    });
});

authForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    setMessage(authMessage, "正在处理...");
    const credentials = {username: $("username").value.trim(), password: $("password").value};
    try {
        if (state.mode === "register") {
            await request("/api/auth/register", {method: "POST", body: JSON.stringify(credentials)});
        }
        const login = await request("/api/auth/login", {method: "POST", body: JSON.stringify(credentials)});
        state.token = login.token;
        localStorage.setItem("ipovideo_token", state.token);
        $("userLabel").textContent = login.user.username;
        setMessage(authMessage, "");
        showWorkspace(true);
    } catch (error) {
        setMessage(authMessage, error.message, "error");
    }
});

$("logoutButton").addEventListener("click", () => {
    disconnectTaskStream();
    state.token = "";
    localStorage.removeItem("ipovideo_token");
    showWorkspace(false);
});

$("videoFile").addEventListener("change", () => {
    const file = $("videoFile").files[0];
    $("fileLabel").textContent = file ? file.name : "选择 MP4 / MOV 视频";
});

$("uploadButton").addEventListener("click", () => {
    const file = $("videoFile").files[0];
    if (!file) return setMessage(taskMessage, "请先选择视频", "error");
    const formData = new FormData();
    formData.append("file", file);
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/media/upload");
    xhr.setRequestHeader("Authorization", "Bearer " + state.token);
    xhr.upload.onprogress = (event) => {
        if (!event.lengthComputable) return;
        const percent = Math.round(event.loaded * 100 / event.total);
        $("uploadProgress").value = percent;
        $("uploadPercent").textContent = percent + "%";
    };
    xhr.onload = () => {
        try {
            const payload = JSON.parse(xhr.responseText);
            if (xhr.status >= 200 && xhr.status < 300 && payload.code === 0) {
                state.mediaId = payload.data.id;
                $("mediaIdLabel").textContent = state.mediaId;
                $("createTaskButton").disabled = false;
                setMessage(taskMessage, "视频上传完成，可以创建分析任务", "success");
            } else {
                setMessage(taskMessage, payload.message || "上传失败", "error");
            }
        } catch {
            setMessage(taskMessage, "上传响应解析失败", "error");
        }
    };
    xhr.onerror = () => setMessage(taskMessage, "网络错误，上传失败", "error");
    xhr.send(formData);
});

taskForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!state.mediaId) return setMessage(taskMessage, "请先上传视频", "error");
    setMessage(taskMessage, "正在创建任务...");
    try {
        const task = await request("/api/tasks", {
            method: "POST",
            body: JSON.stringify({mediaId: state.mediaId, goal: $("goal").value.trim()})
        });
        state.taskId = task.id;
        $("taskIdLabel").textContent = task.id;
        $("refreshButton").disabled = false;
        updateTaskState(task);
        await subscribeTask(task.id);
        setMessage(taskMessage, "任务已创建，正在接收服务端进度", "success");
    } catch (error) {
        setMessage(taskMessage, error.message, "error");
    }
});

async function subscribeTask(taskId) {
    disconnectTaskStream();
    try {
        const ticket = await request("/api/tasks/" + taskId + "/ticket", {method: "POST"});
        const source = new EventSource("/api/tasks/" + taskId + "/events?ticket=" + encodeURIComponent(ticket));
        state.eventSource = source;
        source.addEventListener("task", (event) => {
            const data = JSON.parse(event.data);
            updateTaskState({status: data.progress >= 100 ? "SUCCESS" : "RUNNING", currentStage: data.stage, progress: data.progress});
            $("taskEventMessage").textContent = data.message || "";
            if (data.progress >= 100) loadResult();
        });
        source.onerror = () => startPolling(taskId);
    } catch (error) {
        setMessage(taskMessage, error.message, "error");
        startPolling(taskId);
    }
}

function startPolling(taskId) {
    if (state.pollingTimer) return;
    state.pollingTimer = setInterval(async () => {
        try {
            const task = await request("/api/tasks/" + taskId);
            updateTaskState(task);
            if (task.status === "SUCCESS" || task.status === "FAILED") {
                clearInterval(state.pollingTimer);
                state.pollingTimer = null;
                loadResult();
            }
        } catch {
            clearInterval(state.pollingTimer);
            state.pollingTimer = null;
        }
    }, 1500);
}

function disconnectTaskStream() {
    if (state.eventSource) state.eventSource.close();
    state.eventSource = null;
    if (state.pollingTimer) clearInterval(state.pollingTimer);
    state.pollingTimer = null;
}

function updateTaskState(task) {
    const status = task.status || "PENDING";
    const progress = Number(task.progress || 0);
    $("taskStatus").textContent = status;
    $("taskStage").textContent = task.currentStage || "-";
    $("taskProgress").value = progress;
    $("taskPercent").textContent = progress + "%";
    const badge = $("healthBadge");
    badge.textContent = status;
    badge.className = "status-badge " + (status === "SUCCESS" ? "status-success" : status === "FAILED" ? "status-failed" : "status-running");
}

$("refreshButton").addEventListener("click", loadResult);

async function loadResult() {
    if (!state.taskId) return;
    try {
        const task = await request("/api/tasks/" + state.taskId);
        updateTaskState(task);
        if (task.status !== "SUCCESS") return;
        renderResult(task.result);
    } catch (error) {
        $("resultContent").textContent = error.message;
    }
}

function renderResult(raw) {
    const container = $("resultContent");
    container.innerHTML = "";
    let result;
    try { result = JSON.parse(raw); } catch { result = null; }
    if (!result) {
        container.textContent = raw || "任务成功，但没有返回结果";
        return;
    }
    const title = document.createElement("h4");
    title.textContent = result.title || "分析结论";
    container.append(title);
    appendList(container, "核心结论", result.conclusions);
    appendList(container, "建议", result.suggestions);
}

function appendList(container, heading, items) {
    if (!Array.isArray(items) || items.length === 0) return;
    const title = document.createElement("h4");
    title.textContent = heading;
    container.append(title);
    const list = document.createElement("ul");
    items.forEach((item) => {
        const li = document.createElement("li");
        li.textContent = item;
        list.append(li);
    });
    container.append(list);
}

restoreSession();
