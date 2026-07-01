/**
 * Custom GitLab MCP Server for GitLab 13.11.1
 *
 * Протокол: MCP stdio (транслируется в Streamable HTTP через supergateway)
 * API: GitLab REST API v4
 *
 * Переменные окружения:
 *   MR_MCP_GITLAB_HOST       — базовый URL, например http://10.1.5.6
 *   MR_MCP_GITLAB_TOKEN      — PRIVATE-TOKEN для аутентификации
 *   MR_MCP_GITLAB_PROJECT_ID — (необязательно) дефолтный project_id или namespace/name.
 *                              Если задан — все инструменты используют его по умолчанию,
 *                              и LLM не обязан указывать project_id явно.
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const GITLAB_HOST    = (process.env.MR_MCP_GITLAB_HOST || "").replace(/\/$/, "");
const GITLAB_TOKEN   = process.env.MR_MCP_GITLAB_TOKEN || "";
const DEFAULT_PROJECT = process.env.MR_MCP_GITLAB_PROJECT_ID || "";

if (!GITLAB_HOST || !GITLAB_TOKEN) {
  process.stderr.write(
    "[gitlab-mcp] ERROR: MR_MCP_GITLAB_HOST and MR_MCP_GITLAB_TOKEN must be set\n"
  );
  process.exit(1);
}

if (DEFAULT_PROJECT) {
  process.stderr.write(
    `[gitlab-mcp] Default project: ${DEFAULT_PROJECT}\n`
  );
}

// ---------------------------------------------------------------------------
// GitLab API helper
// ---------------------------------------------------------------------------

async function gitlabFetch(path, options = {}) {
  const url = `${GITLAB_HOST}/api/v4${path}`;
  const res = await fetch(url, {
    ...options,
    headers: {
      "PRIVATE-TOKEN": GITLAB_TOKEN,
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`GitLab API ${res.status} ${res.statusText}: ${text}`);
  }
  return text ? JSON.parse(text) : null;
}

function encodeId(id) {
  return encodeURIComponent(String(id));
}

/**
 * Возвращает project_id из аргумента инструмента или из DEFAULT_PROJECT.
 * Бросает ошибку если не задан ни там, ни там.
 */
function resolveProject(project_id) {
  const pid = project_id || DEFAULT_PROJECT;
  if (!pid) {
    throw new Error(
      "project_id не указан и MR_MCP_GITLAB_PROJECT_ID не задан. " +
      "Укажите project_id явно или задайте --project при деплое."
    );
  }
  return pid;
}

// ---------------------------------------------------------------------------
// MCP Server
// ---------------------------------------------------------------------------

const server = new McpServer({
  name: "gitlab-mr",
  version: "1.1.0",
});

// ---------------------------------------------------------------------------
// Tool: list_mrs — список MR
// ---------------------------------------------------------------------------
server.tool(
  "list_mrs",
  `Получить список Merge Request-ов. Если project_id не указан — используется дефолтный проект${DEFAULT_PROJECT ? ` (${DEFAULT_PROJECT})` : ""}.`,
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(
        `ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "все доступные проекты"}.`
      ),
    state: z
      .enum(["opened", "closed", "merged", "all"])
      .optional()
      .default("opened")
      .describe("Статус MR. По умолчанию: opened."),
    per_page: z
      .number().int().min(1).max(100)
      .optional().default(20)
      .describe("Количество результатов на страницу (макс. 100)."),
  },
  async ({ project_id, state, per_page }) => {
    let path;
    const pid = project_id || DEFAULT_PROJECT;
    if (pid) {
      path = `/projects/${encodeId(pid)}/merge_requests?state=${state}&per_page=${per_page}`;
    } else {
      path = `/merge_requests?scope=all&state=${state}&per_page=${per_page}`;
    }
    const mrs = await gitlabFetch(path);
    const result = mrs.map((mr) => ({
      id: mr.iid,
      project_id: mr.project_id,
      title: mr.title,
      state: mr.state,
      author: mr.author?.username,
      source_branch: mr.source_branch,
      target_branch: mr.target_branch,
      web_url: mr.web_url,
      created_at: mr.created_at,
      updated_at: mr.updated_at,
    }));
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

// ---------------------------------------------------------------------------
// Tool: get_mr — детали конкретного MR
// ---------------------------------------------------------------------------
server.tool(
  "get_mr",
  "Получить детальную информацию по одному Merge Request.",
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    mr_iid: z.number().int().describe("IID (внутренний номер) MR в проекте."),
  },
  async ({ project_id, mr_iid }) => {
    const pid = resolveProject(project_id);
    const mr = await gitlabFetch(`/projects/${encodeId(pid)}/merge_requests/${mr_iid}`);
    return { content: [{ type: "text", text: JSON.stringify(mr, null, 2) }] };
  }
);

// ---------------------------------------------------------------------------
// Tool: get_mr_diff — диф изменений MR
// ---------------------------------------------------------------------------
server.tool(
  "get_mr_diff",
  "Получить список изменённых файлов и диф для Merge Request.",
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    mr_iid: z.number().int().describe("IID MR."),
  },
  async ({ project_id, mr_iid }) => {
    const pid = resolveProject(project_id);
    const data = await gitlabFetch(
      `/projects/${encodeId(pid)}/merge_requests/${mr_iid}/changes`
    );
    const changes = (data.changes || []).map((c) => ({
      old_path: c.old_path,
      new_path: c.new_path,
      new_file: c.new_file,
      renamed_file: c.renamed_file,
      deleted_file: c.deleted_file,
      diff: c.diff,
    }));
    return {
      content: [
        { type: "text", text: JSON.stringify({ mr_title: data.title, changes }, null, 2) },
      ],
    };
  }
);

// ---------------------------------------------------------------------------
// Tool: get_mr_notes — комментарии к MR
// ---------------------------------------------------------------------------
server.tool(
  "get_mr_notes",
  "Получить все комментарии (notes) к Merge Request.",
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    mr_iid: z.number().int().describe("IID MR."),
    per_page: z
      .number().int().min(1).max(100)
      .optional().default(50)
      .describe("Количество комментариев на страницу."),
  },
  async ({ project_id, mr_iid, per_page }) => {
    const pid = resolveProject(project_id);
    const notes = await gitlabFetch(
      `/projects/${encodeId(pid)}/merge_requests/${mr_iid}/notes?per_page=${per_page}&order_by=created_at&sort=asc`
    );
    const result = notes.map((n) => ({
      id: n.id,
      author: n.author?.username,
      body: n.body,
      created_at: n.created_at,
      system: n.system,
      resolvable: n.resolvable,
      resolved: n.resolved,
    }));
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

// ---------------------------------------------------------------------------
// Tool: add_mr_note — добавить комментарий к MR
// ---------------------------------------------------------------------------
server.tool(
  "add_mr_note",
  "Добавить комментарий к Merge Request.",
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    mr_iid: z.number().int().describe("IID MR."),
    body: z.string().min(1).describe("Текст комментария (поддерживает Markdown)."),
  },
  async ({ project_id, mr_iid, body }) => {
    const pid = resolveProject(project_id);
    const note = await gitlabFetch(
      `/projects/${encodeId(pid)}/merge_requests/${mr_iid}/notes`,
      { method: "POST", body: JSON.stringify({ body }) }
    );
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            { id: note.id, author: note.author?.username, created_at: note.created_at },
            null, 2
          ),
        },
      ],
    };
  }
);

// ---------------------------------------------------------------------------
// Tool: add_mr_diff_note — review-комментарий к строке кода
// ---------------------------------------------------------------------------
server.tool(
  "add_mr_diff_note",
  "Добавить review-комментарий к конкретной строке кода в MR (inline comment).",
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    mr_iid: z.number().int().describe("IID MR."),
    body: z.string().min(1).describe("Текст комментария."),
    base_sha: z.string().describe("SHA базового коммита (diff_refs.base_sha из GET /merge_requests/:iid)."),
    start_sha: z.string().describe("SHA начального коммита (diff_refs.start_sha)."),
    head_sha: z.string().describe("SHA коммита HEAD ветки MR (diff_refs.head_sha)."),
    new_path: z.string().describe("Путь к файлу в новой версии."),
    new_line: z.number().int().optional().describe("Номер строки в новой версии файла."),
    old_path: z.string().optional().describe("Путь к файлу в старой версии (если отличается)."),
    old_line: z.number().int().optional().describe("Номер строки в старой версии файла."),
  },
  async ({ project_id, mr_iid, body, base_sha, start_sha, head_sha, new_path, new_line, old_path, old_line }) => {
    const pid = resolveProject(project_id);
    const position = {
      position_type: "text",
      base_sha,
      start_sha,
      head_sha,
      new_path,
      ...(new_line !== undefined ? { new_line } : {}),
      ...(old_path !== undefined ? { old_path } : { old_path: new_path }),
      ...(old_line !== undefined ? { old_line } : {}),
    };
    const note = await gitlabFetch(
      `/projects/${encodeId(pid)}/merge_requests/${mr_iid}/discussions`,
      { method: "POST", body: JSON.stringify({ body, position }) }
    );
    return {
      content: [
        { type: "text", text: JSON.stringify({ discussion_id: note.id, created: true }, null, 2) },
      ],
    };
  }
);

// ---------------------------------------------------------------------------
// Tool: approve_mr — одобрить MR
// ---------------------------------------------------------------------------
server.tool(
  "approve_mr",
  "Одобрить (approve) Merge Request.",
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    mr_iid: z.number().int().describe("IID MR."),
  },
  async ({ project_id, mr_iid }) => {
    const pid = resolveProject(project_id);
    const res = await gitlabFetch(
      `/projects/${encodeId(pid)}/merge_requests/${mr_iid}/approve`,
      { method: "POST", body: "{}" }
    );
    return {
      content: [
        { type: "text", text: JSON.stringify({ approved: true, approvals_required: res?.approvals_required }, null, 2) },
      ],
    };
  }
);

// ---------------------------------------------------------------------------
// Tool: get_project — информация о проекте
// ---------------------------------------------------------------------------
server.tool(
  "get_project",
  "Получить информацию о проекте GitLab по его ID или namespace/name.",
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или полный путь проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
  },
  async ({ project_id }) => {
    const pid = resolveProject(project_id);
    const project = await gitlabFetch(`/projects/${encodeId(pid)}`);
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            {
              id: project.id,
              name: project.name,
              path_with_namespace: project.path_with_namespace,
              web_url: project.web_url,
              default_branch: project.default_branch,
              visibility: project.visibility,
            },
            null, 2
          ),
        },
      ],
    };
  }
);

// ---------------------------------------------------------------------------
// Tool: list_projects — поиск проектов
// ---------------------------------------------------------------------------
server.tool(
  "list_projects",
  "Найти проекты GitLab по имени или вернуть список всех доступных.",
  {
    search: z.string().optional().describe("Строка поиска по имени проекта."),
    per_page: z
      .number().int().min(1).max(100)
      .optional().default(20)
      .describe("Количество результатов на страницу."),
  },
  async ({ search, per_page }) => {
    const query = search ? `&search=${encodeURIComponent(search)}` : "";
    const projects = await gitlabFetch(`/projects?membership=true&per_page=${per_page}${query}`);
    const result = projects.map((p) => ({
      id: p.id,
      name: p.name,
      path_with_namespace: p.path_with_namespace,
      web_url: p.web_url,
      default_branch: p.default_branch,
    }));
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

// ---------------------------------------------------------------------------
// Tool: list_pipelines — список пайплайнов проекта
// ---------------------------------------------------------------------------
server.tool(
  "list_pipelines",
  `Получить список CI/CD пайплайнов проекта. По умолчанию проект: ${DEFAULT_PROJECT || "обязателен"}.`,
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    status: z
      .enum(["running", "pending", "success", "failed", "canceled", "skipped", "created", "manual"])
      .optional()
      .describe("Фильтр по статусу пайплайна."),
    ref: z.string().optional().describe("Фильтр по ветке или тегу."),
    per_page: z
      .number().int().min(1).max(100)
      .optional().default(20)
      .describe("Количество результатов на страницу."),
  },
  async ({ project_id, status, ref, per_page }) => {
    const pid = resolveProject(project_id);
    const params = new URLSearchParams({ per_page: String(per_page) });
    if (status) params.set("status", status);
    if (ref) params.set("ref", ref);
    const pipelines = await gitlabFetch(`/projects/${encodeId(pid)}/pipelines?${params}`);
    const result = pipelines.map((p) => ({
      id: p.id,
      status: p.status,
      ref: p.ref,
      sha: p.sha,
      web_url: p.web_url,
      created_at: p.created_at,
      updated_at: p.updated_at,
    }));
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

// ---------------------------------------------------------------------------
// Tool: get_pipeline — детали пайплайна и список джобов
// ---------------------------------------------------------------------------
server.tool(
  "get_pipeline",
  "Получить детальную информацию о пайплайне и его джобах.",
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    pipeline_id: z.number().int().describe("ID пайплайна."),
  },
  async ({ project_id, pipeline_id }) => {
    const pid = resolveProject(project_id);
    const [pipeline, jobs] = await Promise.all([
      gitlabFetch(`/projects/${encodeId(pid)}/pipelines/${pipeline_id}`),
      gitlabFetch(`/projects/${encodeId(pid)}/pipelines/${pipeline_id}/jobs?per_page=50`),
    ]);
    const result = {
      id: pipeline.id,
      status: pipeline.status,
      ref: pipeline.ref,
      sha: pipeline.sha,
      web_url: pipeline.web_url,
      created_at: pipeline.created_at,
      finished_at: pipeline.finished_at,
      duration: pipeline.duration,
      jobs: (jobs || []).map((j) => ({
        id: j.id,
        name: j.name,
        stage: j.stage,
        status: j.status,
        duration: j.duration,
        failure_reason: j.failure_reason || null,
        web_url: j.web_url,
      })),
    };
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

// ---------------------------------------------------------------------------
// Tool: list_labels — метки проекта
// ---------------------------------------------------------------------------
server.tool(
  "list_labels",
  `Получить список меток (labels) проекта. По умолчанию проект: ${DEFAULT_PROJECT || "обязателен"}.`,
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    per_page: z
      .number().int().min(1).max(100)
      .optional().default(50)
      .describe("Количество результатов на страницу."),
  },
  async ({ project_id, per_page }) => {
    const pid = resolveProject(project_id);
    const labels = await gitlabFetch(`/projects/${encodeId(pid)}/labels?per_page=${per_page}`);
    const result = labels.map((l) => ({
      id: l.id,
      name: l.name,
      color: l.color,
      description: l.description,
      open_merge_requests_count: l.open_merge_requests_count,
    }));
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

// ---------------------------------------------------------------------------
// Tool: list_branches — ветки проекта
// ---------------------------------------------------------------------------
server.tool(
  "list_branches",
  `Получить список веток проекта. По умолчанию проект: ${DEFAULT_PROJECT || "обязателен"}.`,
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    search: z.string().optional().describe("Фильтр по имени ветки."),
    per_page: z
      .number().int().min(1).max(100)
      .optional().default(50)
      .describe("Количество результатов на страницу."),
  },
  async ({ project_id, search, per_page }) => {
    const pid = resolveProject(project_id);
    const params = new URLSearchParams({ per_page: String(per_page) });
    if (search) params.set("search", search);
    const branches = await gitlabFetch(`/projects/${encodeId(pid)}/repository/branches?${params}`);
    const result = branches.map((b) => ({
      name: b.name,
      default: b.default,
      protected: b.protected,
      merged: b.merged,
      commit_sha: b.commit?.id,
      commit_title: b.commit?.title,
      committed_at: b.commit?.committed_date,
    }));
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

// ---------------------------------------------------------------------------
// Tool: get_file_content — содержимое файла из репозитория
// ---------------------------------------------------------------------------
server.tool(
  "get_file_content",
  `Получить содержимое файла из репозитория GitLab (decode base64). По умолчанию проект: ${DEFAULT_PROJECT || "обязателен"}.`,
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(`ID или namespace/name проекта. По умолчанию: ${DEFAULT_PROJECT || "обязателен"}.`),
    file_path: z.string().describe("Путь к файлу в репозитории, например 'src/main.js'."),
    ref: z.string().optional().default("main").describe("Ветка, тег или SHA коммита. По умолчанию: main."),
  },
  async ({ project_id, file_path, ref }) => {
    const pid = resolveProject(project_id);
    const data = await gitlabFetch(
      `/projects/${encodeId(pid)}/repository/files/${encodeURIComponent(file_path)}?ref=${encodeURIComponent(ref)}`
    );
    // GitLab возвращает содержимое файла в base64
    const content = Buffer.from(data.content, "base64").toString("utf-8");
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            {
              file_path: data.file_path,
              ref: data.ref,
              size: data.size,
              encoding: data.encoding,
              last_commit_id: data.last_commit_id,
              content,
            },
            null, 2
          ),
        },
      ],
    };
  }
);

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------

const transport = new StdioServerTransport();
await server.connect(transport);

// Держим процесс живым — без этого node завершается сразу после connect().
// supergateway в --stateful режиме ожидает долгоживущий дочерний процесс.
process.stdin.resume();

process.stderr.write(
  `[gitlab-mcp] MCP server started. GitLab host: ${GITLAB_HOST}` +
  (DEFAULT_PROJECT ? `, default project: ${DEFAULT_PROJECT}` : "") +
  "\n"
);
