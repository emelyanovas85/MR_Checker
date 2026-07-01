/**
 * Custom GitLab MCP Server for GitLab 13.11.1
 *
 * Протокол: MCP stdio (транслируется в Streamable HTTP через supergateway)
 * API: GitLab REST API v4
 *
 * Переменные окружения:
 *   MR_MCP_GITLAB_HOST  — базовый URL, например http://10.1.5.6
 *   MR_MCP_GITLAB_TOKEN — PRIVATE-TOKEN для аутентификации
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const GITLAB_HOST = (process.env.MR_MCP_GITLAB_HOST || "").replace(/\/$/, "");
const GITLAB_TOKEN = process.env.MR_MCP_GITLAB_TOKEN || "";

if (!GITLAB_HOST || !GITLAB_TOKEN) {
  process.stderr.write(
    "[gitlab-mcp] ERROR: MR_MCP_GITLAB_HOST and MR_MCP_GITLAB_TOKEN must be set\n"
  );
  process.exit(1);
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

// ---------------------------------------------------------------------------
// MCP Server
// ---------------------------------------------------------------------------

const server = new McpServer({
  name: "gitlab-mr",
  version: "1.0.0",
});

// ---------------------------------------------------------------------------
// Tool: list_mrs — список открытых MR
// ---------------------------------------------------------------------------
server.tool(
  "list_mrs",
  "Получить список открытых Merge Request-ов. Можно фильтровать по проекту или группе.",
  {
    project_id: z
      .union([z.string(), z.number()])
      .optional()
      .describe(
        "ID или namespace/name проекта (например, 'mygroup/myrepo'). Если не указан — возвращает MR по всем доступным проектам."
      ),
    state: z
      .enum(["opened", "closed", "merged", "all"])
      .optional()
      .default("opened")
      .describe("Статус MR. По умолчанию: opened."),
    per_page: z
      .number()
      .int()
      .min(1)
      .max(100)
      .optional()
      .default(20)
      .describe("Количество результатов на страницу (макс. 100)."),
  },
  async ({ project_id, state, per_page }) => {
    let path;
    if (project_id !== undefined && project_id !== null && project_id !== "") {
      path = `/projects/${encodeId(project_id)}/merge_requests?state=${state}&per_page=${per_page}`;
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
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(result, null, 2),
        },
      ],
    };
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
      .describe("ID или namespace/name проекта."),
    mr_iid: z.number().int().describe("IID (внутренний номер) MR в проекте."),
  },
  async ({ project_id, mr_iid }) => {
    const mr = await gitlabFetch(
      `/projects/${encodeId(project_id)}/merge_requests/${mr_iid}`
    );
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(mr, null, 2),
        },
      ],
    };
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
      .describe("ID или namespace/name проекта."),
    mr_iid: z.number().int().describe("IID MR."),
  },
  async ({ project_id, mr_iid }) => {
    // GitLab 13.11: /merge_requests/:iid/changes возвращает diffs в поле changes[]
    const data = await gitlabFetch(
      `/projects/${encodeId(project_id)}/merge_requests/${mr_iid}/changes`
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
        {
          type: "text",
          text: JSON.stringify(
            { mr_title: data.title, changes },
            null,
            2
          ),
        },
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
      .describe("ID или namespace/name проекта."),
    mr_iid: z.number().int().describe("IID MR."),
    per_page: z
      .number()
      .int()
      .min(1)
      .max(100)
      .optional()
      .default(50)
      .describe("Количество комментариев на страницу."),
  },
  async ({ project_id, mr_iid, per_page }) => {
    const notes = await gitlabFetch(
      `/projects/${encodeId(project_id)}/merge_requests/${mr_iid}/notes?per_page=${per_page}&order_by=created_at&sort=asc`
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
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(result, null, 2),
        },
      ],
    };
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
      .describe("ID или namespace/name проекта."),
    mr_iid: z.number().int().describe("IID MR."),
    body: z.string().min(1).describe("Текст комментария (поддерживает Markdown)."),
  },
  async ({ project_id, mr_iid, body }) => {
    const note = await gitlabFetch(
      `/projects/${encodeId(project_id)}/merge_requests/${mr_iid}/notes`,
      {
        method: "POST",
        body: JSON.stringify({ body }),
      }
    );
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            { id: note.id, author: note.author?.username, created_at: note.created_at },
            null,
            2
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
      .describe("ID или namespace/name проекта."),
    mr_iid: z.number().int().describe("IID MR."),
    body: z.string().min(1).describe("Текст комментария."),
    base_sha: z
      .string()
      .describe("SHA базового коммита (diff_refs.base_sha из GET /merge_requests/:iid)."),
    start_sha: z
      .string()
      .describe("SHA начального коммита (diff_refs.start_sha)."),
    head_sha: z
      .string()
      .describe("SHA коммита HEAD ветки MR (diff_refs.head_sha)."),
    new_path: z.string().describe("Путь к файлу в новой версии."),
    new_line: z
      .number()
      .int()
      .optional()
      .describe("Номер строки в новой версии файла."),
    old_path: z
      .string()
      .optional()
      .describe("Путь к файлу в старой версии (если отличается)."),
    old_line: z
      .number()
      .int()
      .optional()
      .describe("Номер строки в старой версии файла."),
  },
  async ({ project_id, mr_iid, body, base_sha, start_sha, head_sha, new_path, new_line, old_path, old_line }) => {
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
      `/projects/${encodeId(project_id)}/merge_requests/${mr_iid}/discussions`,
      {
        method: "POST",
        body: JSON.stringify({ body, position }),
      }
    );
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            { discussion_id: note.id, created: true },
            null,
            2
          ),
        },
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
      .describe("ID или namespace/name проекта."),
    mr_iid: z.number().int().describe("IID MR."),
  },
  async ({ project_id, mr_iid }) => {
    // GitLab 13.11 поддерживает POST /merge_requests/:iid/approve (EE и CE с v13.2)
    const res = await gitlabFetch(
      `/projects/${encodeId(project_id)}/merge_requests/${mr_iid}/approve`,
      { method: "POST", body: "{}" }
    );
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify({ approved: true, approvals: res?.approvals_required }, null, 2),
        },
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
      .describe("ID или полный путь проекта, например 'mygroup/myrepo'."),
  },
  async ({ project_id }) => {
    const project = await gitlabFetch(`/projects/${encodeId(project_id)}`);
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
            null,
            2
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
    search: z
      .string()
      .optional()
      .describe("Строка поиска по имени проекта."),
    per_page: z
      .number()
      .int()
      .min(1)
      .max(100)
      .optional()
      .default(20)
      .describe("Количество результатов на страницу."),
  },
  async ({ search, per_page }) => {
    const query = search ? `&search=${encodeURIComponent(search)}` : "";
    const projects = await gitlabFetch(
      `/projects?membership=true&per_page=${per_page}${query}`
    );
    const result = projects.map((p) => ({
      id: p.id,
      name: p.name,
      path_with_namespace: p.path_with_namespace,
      web_url: p.web_url,
      default_branch: p.default_branch,
    }));
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(result, null, 2),
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
  `[gitlab-mcp] MCP server started. GitLab host: ${GITLAB_HOST}\n`
);
